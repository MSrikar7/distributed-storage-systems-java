package com.s3index.metadata;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;

public class MetadataTest {
    private static HttpResponse<String> request(HttpClient c, int port, String method, String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metadata/" + key));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("metadata-tests");
        var client = HttpClient.newHttpClient();
        try (var server = new MetadataServer(dir, 0)) {
            server.start(); int port = server.port();
            if (request(client, port, "PUT", "object-1", "etag123").statusCode() != 200) throw new AssertionError();
            if (!request(client, port, "GET", "object-1", null).body().equals("etag123")) throw new AssertionError();
            if (request(client, port, "GET", "missing", null).statusCode() != 404) throw new AssertionError();
        }
        try (var server = new MetadataServer(dir, 0)) {
            server.start(); int port = server.port();
            if (!request(client, port, "GET", "object-1", null).body().equals("etag123")) throw new AssertionError("Restart recovery");
            if (request(client, port, "DELETE", "object-1", null).statusCode() != 200) throw new AssertionError();
            if (request(client, port, "GET", "object-1", null).statusCode() != 404) throw new AssertionError();
        }
        System.out.println("PASS: HTTP CRUD, missing keys, restart recovery");
    }
}
