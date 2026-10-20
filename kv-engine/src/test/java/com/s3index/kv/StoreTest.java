package com.s3index.kv;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class StoreTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("kv-tests");
        try (var store = new DurableKeyValueStore(dir)) {
            store.put("hello", "world".getBytes());
            var threads = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> results = new ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    final int id = i;
                    results.add(threads.submit(() -> {
                        try { store.put("key" + id, ("value" + id).getBytes()); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }));
                }
                for (Future<?> f : results) f.get();
            } finally { threads.shutdown(); }
            if (store.size() != 201) throw new AssertionError("Missing writes");
            store.delete("key0");
            var metrics = store.metrics();
            if (metrics.writes() != 202 || metrics.reads() < 0 || metrics.walForceNanos() <= 0)
                throw new AssertionError("Instrumentation counters missing");
        }
        try (var recovered = new DurableKeyValueStore(dir)) {
            if (recovered.size() != 200) throw new AssertionError("Recovery lost keys");
            if (!"world".equals(new String(recovered.get("hello").orElseThrow()))) throw new AssertionError();
            if (recovered.get("key0").isPresent()) throw new AssertionError("Deletion not recovered");
        }
        // Incomplete trailing record must not destroy preceding acknowledged writes.
        Files.write(dir.resolve("data.wal"), new byte[] {0, 0, 0, 25, 1}, StandardOpenOption.APPEND);
        try (var recovered = new DurableKeyValueStore(dir)) {
            if (recovered.size() != 200) throw new AssertionError("Torn-tail recovery failed");
        }
        System.out.println("PASS: concurrency, durability, deletion, truncated WAL recovery");
    }
}
