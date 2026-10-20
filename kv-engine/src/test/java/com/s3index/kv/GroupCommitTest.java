package com.s3index.kv;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class GroupCommitTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("kv-group-commit-");
        final int workers = 16, requests = 800;
        long syncBatches;
        try (DurableKeyValueStore store = new DurableKeyValueStore(dir)) {
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                final int k = i;
                futures.add(executor.submit(() -> {
                    try {
                        gate.await();
                        store.put("k" + k, ("v" + k).getBytes());
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) f.get(45, TimeUnit.SECONDS);
            executor.shutdown();
            if (store.size() != requests) throw new AssertionError("Missing concurrent writes");
            syncBatches = store.batches();
            if (syncBatches <= 0 || syncBatches >= requests)
                throw new AssertionError("Expected batched fsync, got " + syncBatches);
            byte[] bytes = "original".getBytes();
            store.put("copy", bytes);
            bytes[0] = 'X';
            if (!"original".equals(new String(store.get("copy").orElseThrow())))
                throw new AssertionError("Input aliasing");
            store.delete("k7");
        }
        try (DurableKeyValueStore reopened = new DurableKeyValueStore(dir)) {
            if (reopened.size() != requests) throw new AssertionError("Recovery count mismatch");
            for (int i = 0; i < requests; i++) {
                if (i == 7) {
                    if (reopened.get("k7").isPresent()) throw new AssertionError("Deleted key recovered");
                } else if (!("v" + i).equals(new String(reopened.get("k" + i).orElseThrow()))) {
                    throw new AssertionError("Incorrect recovered value for k" + i);
                }
            }
            if (!"original".equals(new String(reopened.get("copy").orElseThrow())))
                throw new AssertionError("Aliased value recovered");
        }
        System.out.println("PASS: group commit: " + requests + " writes, " + syncBatches + " fsync batches, restart recovery");
    }
}
