package com.s3index.metadata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Deterministic application-level follower-outage simulation.
 * Tests orderly process restarts, not actual power loss or network partitions.
 * Requires only JDK 21 and the project's existing MetadataServer.
 */
public final class LeaderRestartFaultInjectionTest {
    private static final String SECRET = "local-test-secret";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static HttpResponse<String> request(int port, String method, String path, String value)
            throws Exception {
        HttpRequest.BodyPublisher payload = value == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(value);
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, payload)
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void expect(int port, String method, String path, String value,
                               int status, String body) throws Exception {
        HttpResponse<String> response = request(port, method, path, value);
        require(response.statusCode() == status,
                method + " " + path + ": expected HTTP " + status + ", got " + response.statusCode()
                        + " body=" + response.body());
        if (body != null) {
            require(body.equals(response.body()),
                    method + " " + path + ": expected body=" + body + ", got=" + response.body());
        }
    }

    public static void main(String[] args) throws Exception {
        Path leaderDir = Files.createTempDirectory("fault-leader-");
        Path followerDir = Files.createTempDirectory("fault-follower-");
        int followerPort;

        // Phase 1: establish a functioning replica pair, then take follower offline.
        try (MetadataServer follower = new MetadataServer(followerDir, 0, "follower", null, SECRET)) {
            follower.start();
            followerPort = follower.port();
            URI followerUri = URI.create("http://127.0.0.1:" + followerPort);
            try (MetadataServer leader = new MetadataServer(leaderDir, 0, "leader", followerUri, SECRET)) {
                leader.start();
                expect(leader.port(), "PUT", "/metadata/baseline", "before", 200, "Stored");
                expect(followerPort, "GET", "/metadata/baseline", null, 200, "before");
            }
        }
        // Follower is now down. Reopen leader, issue an ambiguous write and stop leader.
        URI followerUri = URI.create("http://127.0.0.1:" + followerPort);
        try (MetadataServer leader = new MetadataServer(leaderDir, 0, "leader", followerUri, SECRET)) {
            leader.start();
            expect(leader.port(), "PUT", "/metadata/pending", "recover-me", 503, null);
            expect(leader.port(), "GET", "/metadata/pending", null, 404, "Not found");
            expect(leader.port(), "GET", "/health", null, 503, "REPLICATION_UNCERTAIN");
            expect(leader.port(), "PUT", "/metadata/blocked", "no", 503, null);
        }

        // Phase 2: crash/restart analogue — old server is closed, on-disk intent reused.
        // Follower remains offline; a fresh leader must still be fenced.
        try (MetadataServer restartedLeader = new MetadataServer(leaderDir, 0, "leader", followerUri, SECRET)) {
            restartedLeader.start();
            expect(restartedLeader.port(), "GET", "/health", null, 503, "REPLICATION_UNCERTAIN");
            expect(restartedLeader.port(), "PUT", "/metadata/still-blocked", "no", 503, null);

            // Phase 3: same follower data and same socket address, then operator recovery.
            try (MetadataServer restartedFollower = new MetadataServer(followerDir, followerPort,
                    "follower", null, SECRET)) {
                restartedFollower.start();
                expect(restartedLeader.port(), "POST", "/admin/reconcile", null, 200, "RECONCILED");
                expect(restartedLeader.port(), "GET", "/metadata/pending", null, 200, "recover-me");
                expect(restartedFollower.port(), "GET", "/metadata/pending", null, 200, "recover-me");
                expect(restartedLeader.port(), "GET", "/health", null, 200, "OK");
                expect(restartedLeader.port(), "PUT", "/metadata/after-recovery", "resumed", 200, "Stored");
                expect(restartedFollower.port(), "GET", "/metadata/after-recovery", null, 200, "resumed");
            }
        }
        System.out.println("PASS: outage fencing; hidden pending write; durable restart fence; "
                + "operator reconciliation; replica agreement; resumed writes");
    }
}
