package com.s3index.metadata;

import com.s3index.kv.DurableKeyValueStore;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.*;
import java.util.*;
import java.nio.ByteBuffer;

/** Single-node HTTP metadata service; no replication or failover is claimed. */
public final class MetadataServer implements AutoCloseable {
    private final DurableKeyValueStore storage;
    private final String role;
    private final URI follower;
    private final HttpClient replicationClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ReentrantLock replicationOrder = new ReentrantLock();
    // Demo only: local transport is trusted. Not a production authentication mechanism.
    private final String replicationSecret;
    private volatile boolean replicationHealthy = true;
    private static final String INTENT = "__s3index_internal_v5_intent";
    private static final String SEQ = "__s3index_internal_v5_seq";
    private static final String LAST = "__s3index_internal_v5_last";
    private final long bootSequence;
    private record Op(long seq, String method, String key, byte[] body) {}
    private byte[] encode(Op op) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeLong(op.seq()); d.writeUTF(op.method()); d.writeUTF(op.key());
            d.writeInt(op.body() == null ? -1 : op.body().length);
            if (op.body() != null) d.write(op.body());
        }
        return out.toByteArray();
    }
    private Op decode(byte[] data) throws IOException {
        try (DataInputStream d = new DataInputStream(new ByteArrayInputStream(data))) {
            long n=d.readLong(); String method=d.readUTF(), key=d.readUTF(); int len=d.readInt();
            if (len < -1 || len > 1024*1024) throw new IOException("Invalid intent body");
            byte[] b=len == -1 ? null : d.readNBytes(len);
            if (len >= 0 && b.length != len) throw new EOFException();
            if (!method.equals("PUT") && !method.equals("DELETE")) throw new IOException("Invalid method");
            return new Op(n,method,key,b);
        }
    }
    private long number(String key) {
        return storage.get(key).map(b -> ByteBuffer.wrap(b).getLong()).orElse(0L);
    }
    private void setNumber(String key, long n) throws IOException {
        storage.put(key, ByteBuffer.allocate(8).putLong(n).array());
    }
    private Optional<Op> intent() throws IOException {
        Optional<byte[]> b=storage.get(INTENT);
        return b.isEmpty()?Optional.empty():Optional.of(decode(b.get()));
    }
    private void apply(Op op) throws IOException {
        if (op.method().equals("PUT")) storage.put(op.key(),op.body()); else storage.delete(op.key());
    }
    private boolean replicate(Op op) throws IOException, InterruptedException {
        HttpRequest request=HttpRequest.newBuilder(follower.resolve("/replica/apply/" +
                java.net.URLEncoder.encode(op.key(),StandardCharsets.UTF_8).replace("+","%20")))
                .timeout(Duration.ofSeconds(5)).header("X-Replica-Secret",replicationSecret)
                .header("X-Sequence",Long.toString(op.seq()))
                .method(op.method(), op.body()==null ? HttpRequest.BodyPublishers.noBody():
                        HttpRequest.BodyPublishers.ofByteArray(op.body())).build();
        return replicationClient.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200;
    }
    private boolean reconcile() throws IOException, InterruptedException {
        Optional<Op> pending=intent();
        if (pending.isEmpty()) { replicationHealthy=true; return true; }
        Op op=pending.get();
        if (!replicate(op)) return false;
        apply(op); // idempotent PUT/DELETE; only one outstanding operation
        storage.delete(INTENT);
        replicationHealthy=true;
        return true;
    }
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(16);
    public MetadataServer(Path directory, int port) throws IOException {
        this(directory, port, "standalone", null, null);
    }
    public MetadataServer(Path directory, int port, String role, URI follower, String secret) throws IOException {
        if (!role.equals("standalone") && !role.equals("leader") && !role.equals("follower"))
            throw new IllegalArgumentException("Unknown role");
        if (role.equals("leader") && (follower == null || secret == null || secret.isBlank()))
            throw new IllegalArgumentException("Leader requires follower URI and replication secret");
        if (role.equals("follower") && (secret == null || secret.isBlank()))
            throw new IllegalArgumentException("Follower requires replication secret");
        this.role = role;
        this.follower = follower;
        this.replicationSecret = secret;
        storage = new DurableKeyValueStore(directory);
        bootSequence = number(SEQ);
        if (role.equals("leader") && storage.get(INTENT).isPresent()) replicationHealthy=false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 128);
        server.setExecutor(workers);
        server.createContext("/health", exchange -> reply(exchange, replicationHealthy ? 200 : 503,
                replicationHealthy ? "OK" : "REPLICATION_UNCERTAIN"));
        server.createContext("/replica/apply", this::replicaApply);
        server.createContext("/admin/reconcile", exchange -> {
            if (!role.equals("leader") || !exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405,"Only leader POST is supported"); return;
            }
            replicationOrder.lock();
            try {
                try { reply(exchange,reconcile()?200:503,replicationHealthy?"RECONCILED":"RETRY_REQUIRED"); }
                catch (Exception e) { replicationHealthy=false; reply(exchange,503,"RETRY_REQUIRED"); }
            } finally { replicationOrder.unlock(); }
        });
        server.createContext("/role", exchange -> reply(exchange, 200, role));
        server.createContext("/metrics", exchange -> {
            var m = storage.metrics();
            String json = String.format(java.util.Locale.ROOT,
                "{\"reads\":%d,\"writes\":%d,\"read_nanos\":%d,\"write_lock_wait_nanos\":%d," +
                "\"wal_append_nanos\":%d,\"wal_force_nanos\":%d}",
                m.reads(), m.writes(), m.readNanos(), m.writeWaitNanos(),
                m.walAppendNanos(), m.walForceNanos());
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            reply(exchange, 200, json);
        });
        server.createContext("/metadata", this::metadata);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    private void metadata(HttpExchange ex) throws IOException {
        try {
            String prefix = "/metadata/";
            String rawPath = ex.getRequestURI().getRawPath();
            if (!rawPath.startsWith(prefix) || rawPath.length() == prefix.length()) { reply(ex, 400, "Expected /metadata/{key}"); return; }
            String key = URLDecoder.decode(rawPath.substring(prefix.length()), StandardCharsets.UTF_8);
            if (key.isBlank() || key.contains("/") || key.startsWith("__s3index_internal_v5_")) { reply(ex, 400, "Invalid key"); return; }
            switch (ex.getRequestMethod()) {
                case "GET" -> {
                    var val = storage.get(key);
                    if (val.isPresent()) reply(ex, 200, val.get()); else reply(ex, 404, "Not found");
                }
                case "PUT" -> {
                    if (role.equals("follower")) { reply(ex, 403, "Follower is read only"); return; }
                    byte[] body = ex.getRequestBody().readNBytes(1024 * 1024 + 1);
                    if (body.length > 1024 * 1024) { reply(ex, 413, "Body too large"); return; }
                    applyClientMutation(ex, "PUT", key, body);
                }
                case "DELETE" -> {
                    if (role.equals("follower")) { reply(ex, 403, "Follower is read only"); return; }
                    applyClientMutation(ex, "DELETE", key, null);
                }
                default -> { ex.getResponseHeaders().set("Allow", "GET, PUT, DELETE"); reply(ex, 405, "Method not allowed"); }
            }
        } catch (IllegalArgumentException e) { reply(ex, 400, "Invalid request"); }
        catch (Exception e) { reply(ex, 500, "Server error"); }
    }
    private void applyClientMutation(HttpExchange ex, String method, String key, byte[] body) throws IOException {
        replicationOrder.lock();
        try {
            if (!replicationHealthy) { reply(ex, 503, "Replication state uncertain; stop and reconcile nodes"); return; }
            if (role.equals("leader")) {
                long seq=number(SEQ)+1;
                Op op=new Op(seq, method, key, body);
                // Intent is durable BEFORE trying the follower. Failed requests are
                // not applied on the leader and are recovered by explicit reconciliation.
                setNumber(SEQ,seq);
                storage.put(INTENT,encode(op));
                try {
                    if (!replicate(op)) throw new IOException("Follower rejected write");
                    apply(op);
                    storage.delete(INTENT);
                } catch (IOException e) {
                    replicationHealthy=false;
                    reply(ex,503,"Replication uncertain; POST /admin/reconcile when peer is healthy");
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); replicationHealthy=false;
                    reply(ex,503,"Replication uncertain; reconcile"); return;
                }
            } else {
                if (method.equals("PUT")) storage.put(key,body); else storage.delete(key);
            }
            reply(ex, 200, method.equals("PUT") ? "Stored" : "Deleted");
        } finally { replicationOrder.unlock(); }
    }

    private void replicaApply(HttpExchange ex) throws IOException {
        if (!role.equals("follower")) { reply(ex, 404, "Not found"); return; }
        if (!java.util.Objects.equals(replicationSecret, ex.getRequestHeaders().getFirst("X-Replica-Secret"))) {
            reply(ex, 403, "Forbidden"); return;
        }
        String prefix = "/replica/apply/";
        String raw = ex.getRequestURI().getRawPath();
        if (!raw.startsWith(prefix) || raw.length() == prefix.length()) { reply(ex, 400, "Missing key"); return; }
        String key;
        try { key = URLDecoder.decode(raw.substring(prefix.length()), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { reply(ex, 400, "Bad key"); return; }
        if (key.isBlank() || key.contains("/")) { reply(ex, 400, "Bad key"); return; }
        replicationOrder.lock();
        try {
            long seq;
            try { seq=Long.parseLong(ex.getRequestHeaders().getFirst("X-Sequence")); }
            catch (Exception e) { reply(ex,400,"Sequence required"); return; }
            long last=number(LAST);
            if (seq <= last) { reply(ex,200,"Duplicate acknowledged"); return; }
            if (seq != last+1) { reply(ex,409,"Sequence gap"); return; }
            byte[] body=null;
            if (ex.getRequestMethod().equals("PUT")) {
                body=ex.getRequestBody().readNBytes(1024*1024+1);
                if (body.length>1024*1024) { reply(ex,413,"Body too large"); return; }
            } else if (!ex.getRequestMethod().equals("DELETE")) {
                reply(ex,405,"Method not allowed"); return;
            }
            apply(new Op(seq,ex.getRequestMethod(),key,body));
            setNumber(LAST,seq);
            reply(ex,200,"Applied");
        } catch (Exception e) { reply(ex,500,"Replica storage failed"); }
        finally { replicationOrder.unlock(); }
    }
    private static void reply(HttpExchange ex, int status, String message) throws IOException { reply(ex, status, message.getBytes(StandardCharsets.UTF_8)); }
    private static void reply(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }
    @Override public void close() throws IOException { server.stop(0); workers.shutdownNow(); storage.close(); }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "./runtime-data");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        String role = args.length > 2 ? args[2] : "standalone";
        URI peer = args.length > 3 && !args[3].equals("-") ? URI.create(args[3]) : null;
        String secret = System.getenv("S3_INDEX_REPLICA_SECRET");
        MetadataServer server = new MetadataServer(dir, port, role, peer, secret);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.close(); } catch (IOException ignored) {} }));
        server.start();
        System.out.println("Metadata server (" + role + ") listening on http://127.0.0.1:" + port);
    }
}
