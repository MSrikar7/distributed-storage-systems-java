package com.s3index.metadata;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

public final class ReplicationTest {
    private static HttpResponse<String> send(HttpClient c, int port, String method, String key, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/metadata/"+key))
            .timeout(Duration.ofSeconds(8))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return c.send(req, HttpResponse.BodyHandlers.ofString());
    }
    private static void require(boolean b, String message) { if (!b) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path leaderDir = Files.createTempDirectory("replication-leader-");
        Path followerDir = Files.createTempDirectory("replication-follower-");
        var client = HttpClient.newHttpClient();
        int formerFollowerPort;
        try (var follower = new MetadataServer(followerDir, 0, "follower", null, "test-secret")) {
            follower.start(); formerFollowerPort = follower.port();
            try (var leader = new MetadataServer(leaderDir, 0, "leader", URI.create("http://127.0.0.1:"+follower.port()), "test-secret")) {
                leader.start();
                require(send(client, leader.port(), "PUT", "a", "hello").statusCode()==200, "leader ack");
                require(send(client, follower.port(), "GET", "a", null).body().equals("hello"), "replicated PUT");
                require(send(client, follower.port(), "PUT", "bad", "x").statusCode()==403, "follower write denied");
                require(send(client, leader.port(), "DELETE", "a", null).statusCode()==200, "delete ack");
                require(send(client, follower.port(), "GET", "a", null).statusCode()==404, "replicated DELETE");
                require(send(client, leader.port(), "PUT", "persist", "durable").statusCode()==200, "persist ack");
            }
        }
        // Safe manual role change only after original leader has been stopped.
        try (var promoted = new MetadataServer(followerDir, 0)) {
            promoted.start();
            require(send(client, promoted.port(), "GET", "persist", null).body().equals("durable"), "restart recovery");
        }
        // Unavailable follower: leader must refuse subsequent writes after ambiguous failure.
        try (var leader = new MetadataServer(leaderDir, 0, "leader", URI.create("http://127.0.0.1:"+formerFollowerPort), "test-secret")) {
            leader.start();
            require(send(client, leader.port(), "PUT", "uncertain", "x").statusCode()==503, "unavailable follower");
            require(send(client, leader.port(), "PUT", "blocked", "x").statusCode()==503, "fenced new writes");
        }
        System.out.println("PASS: replication, follower read-only, delete, restart, manual promotion, fail-closed writes");
    }
}
