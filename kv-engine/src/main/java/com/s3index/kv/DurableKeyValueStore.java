package com.s3index.kv;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

/** Single-process durable KV store with ordered, batched WAL fsync. Not a replicated database. */
public final class DurableKeyValueStore implements AutoCloseable {
    private static final int MAX_RECORD = 16 * 1024 * 1024;
    private static final int MAX_BATCH = 64;
    private static final long BATCH_WAIT_MILLIS = 1;
    private final Map<String, byte[]> values = new HashMap<>();
    private final ReentrantReadWriteLock mapLock = new ReentrantReadWriteLock();
    private final FileChannel log;
    private final BlockingQueue<Mutation> queue = new LinkedBlockingQueue<>();
    private final Set<Mutation> outstanding = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final Thread worker;
    private boolean accepting = true; // lifecycle lock
    private volatile boolean closed;
    private volatile IOException fatal;

    private final LongAdder reads = new LongAdder(), writes = new LongAdder();
    private final LongAdder readNanos = new LongAdder(), writeWaitNanos = new LongAdder();
    private final LongAdder walAppendNanos = new LongAdder(), walForceNanos = new LongAdder();
    private final LongAdder batches = new LongAdder();

    public record Metrics(long reads, long writes, long readNanos, long writeWaitNanos,
                          long walAppendNanos, long walForceNanos) {}
    public Metrics metrics() {
        return new Metrics(reads.sum(), writes.sum(), readNanos.sum(), writeWaitNanos.sum(),
                walAppendNanos.sum(), walForceNanos.sum());
    }
    public long batches() { return batches.sum(); }

    private record Mutation(byte type, String key, byte[] snapshot, ByteBuffer record,
                            long queuedAt, CompletableFuture<Void> result) {}
    private static final Mutation STOP = new Mutation((byte) 0, "", null, null, 0, null);

    public DurableKeyValueStore(Path dir) throws IOException {
        Files.createDirectories(dir);
        log = FileChannel.open(dir.resolve("data.wal"), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try { recover(); } catch (IOException | RuntimeException e) { log.close(); throw e; }
        worker = new Thread(this::process, "kv-wal-group-commit");
        worker.setDaemon(true);
        worker.start();
    }

    private void recover() throws IOException {
        long validEnd = 0;
        while (validEnd + 4 <= log.size()) {
            ByteBuffer length = ByteBuffer.allocate(4);
            readFullyAt(length, validEnd);
            length.flip();
            int n = length.getInt();
            if (n < 5 || n > MAX_RECORD) throw new IOException("Invalid WAL record length at " + validEnd);
            if (validEnd + 4L + n > log.size()) break;
            ByteBuffer data = ByteBuffer.allocate(n);
            readFullyAt(data, validEnd + 4);
            byte[] record = data.array();
            int expected = ByteBuffer.wrap(record, n - 4, 4).getInt();
            CRC32 crc = new CRC32(); crc.update(record, 0, n - 4);
            if ((int) crc.getValue() != expected) throw new IOException("WAL checksum mismatch at " + validEnd);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(record, 0, n - 4))) {
                byte type = in.readByte();
                int keySize = in.readInt();
                if (keySize <= 0 || keySize > n - 9) throw new IOException("Invalid key length");
                String key = new String(in.readNBytes(keySize), StandardCharsets.UTF_8);
                if (type == 1) {
                    int valueSize = in.readInt();
                    if (valueSize < 0 || valueSize != in.available()) throw new IOException("Invalid value length");
                    values.put(key, in.readNBytes(valueSize));
                } else if (type == 2 && in.available() == 0) values.remove(key);
                else throw new IOException("Invalid WAL type");
            }
            validEnd += 4L + n;
        }
        if (validEnd < log.size()) log.truncate(validEnd);
        log.position(validEnd);
    }
    private void readFullyAt(ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int n = log.read(target, offset);
            if (n <= 0) throw new EOFException("Incomplete WAL read");
            offset += n;
        }
    }

    public Optional<byte[]> get(String key) {
        Objects.requireNonNull(key);
        long started = System.nanoTime();
        mapLock.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Store closed");
            byte[] found = values.get(key);
            return found == null ? Optional.empty() : Optional.of(found.clone());
        } finally {
            mapLock.readLock().unlock();
            reads.increment(); readNanos.add(System.nanoTime() - started);
        }
    }
    public int size() {
        mapLock.readLock().lock();
        try { if (closed) throw new IllegalStateException("Store closed"); return values.size(); }
        finally { mapLock.readLock().unlock(); }
    }
    public void put(String key, byte[] value) throws IOException {
        Objects.requireNonNull(value);
        mutate((byte) 1, key, value.clone());
    }
    public void delete(String key) throws IOException { mutate((byte) 2, key, null); }

    private void mutate(byte type, String key, byte[] snapshot) throws IOException {
        Objects.requireNonNull(key);
        byte[] encodedKey = key.getBytes(StandardCharsets.UTF_8);
        if (encodedKey.length == 0) throw new IllegalArgumentException("Empty key");
        long payloadLength = 1L + 4 + encodedKey.length + (snapshot == null ? 0 : 4L + snapshot.length);
        if (payloadLength + 4 > MAX_RECORD) throw new IllegalArgumentException("Record exceeds 16 MiB");
        ByteBuffer buf = ByteBuffer.allocate((int) payloadLength + 8);
        buf.putInt((int) payloadLength + 4).put(type).putInt(encodedKey.length).put(encodedKey);
        if (snapshot != null) buf.putInt(snapshot.length).put(snapshot);
        CRC32 crc = new CRC32(); crc.update(buf.array(), 4, (int) payloadLength);
        buf.putInt((int) crc.getValue()).flip();
        Mutation change = new Mutation(type, key, snapshot, buf, System.nanoTime(), new CompletableFuture<>());
        synchronized (lifecycle) {
            if (!accepting) {
                if (fatal != null) throw new IOException("WAL worker failed", fatal);
                throw new IllegalStateException("Store closed");
            }
            outstanding.add(change);
            queue.add(change);
        }
        try {
            change.result.get();
        } catch (InterruptedException e) {
            // Preserve acknowledgement semantics: do not return before durability is known.
            boolean interrupted = true;
            for (;;) {
                try { change.result.get(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
                catch (ExecutionException failure) { Thread.currentThread().interrupt(); throw asIOException(failure.getCause()); }
            }
            if (interrupted) Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw asIOException(e.getCause());
        }
    }
    private static IOException asIOException(Throwable t) {
        return t instanceof IOException e ? e : new IOException("WAL commit failed", t);
    }

    private void process() {
        try {
            while (true) {
                Mutation first = queue.take();
                if (first == STOP) break;
                List<Mutation> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                // Give concurrent writers a short opportunity to join this fsync.
                Mutation next = queue.poll(BATCH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                if (next != null && next != STOP) batch.add(next);
                boolean stopAfter = next == STOP;
                while (batch.size() < MAX_BATCH && !stopAfter) {
                    next = queue.poll();
                    if (next == null) break;
                    if (next == STOP) { stopAfter = true; break; }
                    batch.add(next);
                }
                for (Mutation mutation : batch) {
                    writeWaitNanos.add(System.nanoTime() - mutation.queuedAt);
                    long start = System.nanoTime();
                    while (mutation.record.hasRemaining()) log.write(mutation.record);
                    walAppendNanos.add(System.nanoTime() - start);
                }
                long syncStart = System.nanoTime();
                log.force(true);
                walForceNanos.add(System.nanoTime() - syncStart);
                batches.increment();
                mapLock.writeLock().lock();
                try {
                    for (Mutation mutation : batch) {
                        if (mutation.type == 1) values.put(mutation.key, mutation.snapshot);
                        else values.remove(mutation.key);
                        writes.increment();
                    }
                } finally { mapLock.writeLock().unlock(); }
                for (Mutation mutation : batch) {
                    mutation.result.complete(null);
                    outstanding.remove(mutation);
                }
                if (stopAfter) break;
            }
        } catch (Throwable failure) {
            IOException io = asIOException(failure);
            fatal = io;
            synchronized (lifecycle) { accepting = false; }
            for (Mutation mutation : outstanding) mutation.result.completeExceptionally(io);
            outstanding.clear();
            queue.clear();
        }
    }

    @Override public void close() throws IOException {
        synchronized (lifecycle) {
            if (closed) return;
            accepting = false;
            queue.add(STOP);
        }
        try { worker.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted closing WAL worker", e); }
        mapLock.writeLock().lock();
        try { closed = true; log.close(); }
        finally { mapLock.writeLock().unlock(); }
        if (fatal != null) throw fatal;
    }
}
