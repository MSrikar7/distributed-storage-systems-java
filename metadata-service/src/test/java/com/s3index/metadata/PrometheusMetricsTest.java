package com.s3index.metadata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/** Smoke test of Prometheus scraping without external dependencies. */
public final class PrometheusMetricsTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("s3index-v6-metrics-");
        try (MetadataServer server = new MetadataServer(dir, 0)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            var before = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics/prometheus")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            require(before.statusCode() == 200, "metrics HTTP status");
            require(before.headers().firstValue("content-type").orElse("").contains("text/plain"), "text exposition");
            require(before.body().contains("s3index_wal_batches_total"), "batch metric");
            require(before.body().contains("s3index_node_info{role=\"standalone\"} 1"), "node role");
            var put = client.send(HttpRequest.newBuilder(URI.create(base + "/metadata/v6-test"))
                    .PUT(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
            require(put.statusCode() == 200, "PUT response");
            var after = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics/prometheus")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            require(after.body().contains("s3index_writes_total 1"), "write counter");
            require(after.body().contains("s3index_replication_healthy 1"), "health metric");
            var invalid = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics/prometheus"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            require(invalid.statusCode() == 405, "metrics endpoint is read-only");
        }
        System.out.println("PrometheusMetricsTest PASS");
    }
    private static void require(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
