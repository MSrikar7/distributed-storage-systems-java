package com.s3index.metadata;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;

public final class ReconciliationTest {
    static void check(boolean condition, String m) { if (!condition) throw new AssertionError(m); }
    static HttpResponse<String> request(HttpClient c, int port, String method, String path, String body) throws Exception {
        HttpRequest r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
        return c.send(r,HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[] args) throws Exception {
        Path ld=Files.createTempDirectory("v5-leader"), fd=Files.createTempDirectory("v5-follower");
        HttpClient c=HttpClient.newHttpClient();
        int followerPort;
        try (var follower=new MetadataServer(fd,0,"follower",null,"secret")) {
            follower.start(); followerPort=follower.port();
            try (var leader=new MetadataServer(ld,0,"leader",URI.create("http://127.0.0.1:"+followerPort),"secret")) {
                leader.start();
                check(request(c,leader.port(),"PUT","/metadata/initial","ok").statusCode()==200,"initial put");
                check(request(c,followerPort,"GET","/metadata/initial",null).body().equals("ok"),"initial follower read");
            }
        }
        // Failure leaves intent on leader, NOT a locally-visible unreplicated value.
        try (var leader=new MetadataServer(ld,0,"leader",URI.create("http://127.0.0.1:"+followerPort),"secret")) {
            leader.start();
            check(request(c,leader.port(),"PUT","/metadata/uncertain","new").statusCode()==503,"expected 503");
            check(request(c,leader.port(),"GET","/metadata/uncertain",null).statusCode()==404,"uncommitted data must not be exposed on leader");
            check(request(c,leader.port(),"POST","/admin/reconcile",null).statusCode()==503,"peer still down");
        }
        try (var follower=new MetadataServer(fd,followerPort,"follower",null,"secret")) {
            follower.start();
            try (var leader=new MetadataServer(ld,0,"leader",URI.create("http://127.0.0.1:"+followerPort),"secret")) {
                leader.start();
                check(request(c,leader.port(),"GET","/health",null).statusCode()==503,"restart must retain fence");
                check(request(c,leader.port(),"PUT","/metadata/blocked","x").statusCode()==503,"writes remain blocked");
                check(request(c,leader.port(),"POST","/admin/reconcile",null).statusCode()==200,"reconciliation");
                check(request(c,leader.port(),"GET","/metadata/uncertain",null).body().equals("new"),"leader recovered");
                check(request(c,followerPort,"GET","/metadata/uncertain",null).body().equals("new"),"follower recovered");
                check(request(c,leader.port(),"PUT","/metadata/after","ok").statusCode()==200,"writes resumed");
                check(request(c,followerPort,"GET","/metadata/after",null).body().equals("ok"),"new seq replayed");
            }
        }
        System.out.println("PASS: uncertain write hidden on leader; durable intent; restart fencing; reconciliation; sequential replication");
    }
}
