package com.s3index.load;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Benchmark request latency and throughput with a configurable thread count. */
public final class LoadTester {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LoadTester <base-url> [threads=8] [requests=1000]");
            System.exit(2);
        }
        String base = args[0].replaceAll("/$", "");
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int requests = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        String mode = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "write";
        if (!Set.of("read", "write", "mixed").contains(mode))
            throw new IllegalArgumentException("Mode must be read, write or mixed");
        if (threads < 1 || threads > 256 || requests < 1 || requests > 1000000) throw new IllegalArgumentException("Invalid thread/request count");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        // Preload read keys before timing to avoid benchmarking 404s.
        if (!mode.equals("write")) {
            for (int k = 0; k < Math.min(requests, 100); k++) {
                var req = HttpRequest.newBuilder(URI.create(base + "/metadata/bench-read-" + k))
                    .PUT(HttpRequest.BodyPublishers.ofString("value-" + k)).build();
                if (client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() != 200)
                    throw new IllegalStateException("Preload failed");
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger(), errors = new AtomicInteger();
        ConcurrentLinkedQueue<Long> latency = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    gate.await();
                    for (int id; (id = sequence.getAndIncrement()) < requests;) {
                        long begin = System.nanoTime();
                        try {
                            boolean doRead = mode.equals("read") || (mode.equals("mixed") && id % 5 != 0);
                            String key = doRead ? "bench-read-" + (id % Math.min(requests, 100)) : "bench-" + id;
                            var builder = HttpRequest.newBuilder(URI.create(base + "/metadata/" + key))
                                    .timeout(Duration.ofSeconds(15));
                            var req = doRead ? builder.GET().build() :
                                    builder.PUT(HttpRequest.BodyPublishers.ofString("value-" + id)).build();
                            int status = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                            if (status != 200) errors.incrementAndGet();
                        } catch (Exception e) { errors.incrementAndGet(); }
                        finally { latency.add(System.nanoTime() - begin); }
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
        }
        gate.countDown();
        try { for (Future<?> future : futures) future.get(); }
        finally { pool.shutdownNow(); }
        double seconds = (System.nanoTime() - start) / 1e9;
        long[] samples = latency.stream().mapToLong(Long::longValue).sorted().toArray();
        if (samples.length == 0) throw new IllegalStateException("No samples");
        System.out.printf(Locale.ROOT, "mode=%s requests=%d threads=%d failures=%d elapsed_s=%.3f throughput_rps=%.2f p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f%n",
                mode, samples.length, threads, errors.get(), seconds, samples.length / seconds,
                percentile(samples, .50), percentile(samples, .95), percentile(samples, .99));
    }
    private static double percentile(long[] x, double pct) { return x[(int) Math.ceil(x.length * pct) - 1] / 1e6; }
}
