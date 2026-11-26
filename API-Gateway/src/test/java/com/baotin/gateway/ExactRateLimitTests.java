package com.baotin.gateway;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Real Redis fixed-window counters, isolated by low limits and a long window. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExactRateLimitTests {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final HttpServer UPSTREAM;
    static final RSAKey KEY;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("rate-limit-test").generate();
            UPSTREAM=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            UPSTREAM.createContext("/jwks",exchange->{
                var bytes=new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
                try(var output=exchange.getResponseBody()){output.write(bytes);}
            });
            UPSTREAM.createContext("/api/v1/",exchange->{
                exchange.getRequestBody().readAllBytes();byte[] body="{\"forwarded\":true}".getBytes();
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()){output.write(body);}
            });UPSTREAM.start();REDIS.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String uri="http://127.0.0.1:"+UPSTREAM.getAddress().getPort();
        r.add("gateway.jwks-uri",()->uri+"/jwks");r.add("gateway.auth-uri",()->uri);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("gateway.auth-write-limit",()->"2");r.add("gateway.auth-read-limit",()->"1");
        r.add("gateway.rate-window-seconds",()->"86400");
    }
    @LocalServerPort int port;
    @AfterAll static void stop(){UPSTREAM.stop(0);}
    String jwt() throws Exception {
        var now=Instant.now();
        var claims=new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer("baotin-auth").audience("baotin-api")
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString()).claim("sid",UUID.randomUUID().toString()).claim("ver",1)
            .claim("token_use","access").claim("roles",List.of("USER")).build();
        var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(new JOSEObjectType("at+jwt")).build(),claims);
        token.sign(new RSASSASigner(KEY));return token.serialize();
    }
    HttpResponse<String> post(String path) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token).GET().build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    }
    @Test void writeLimitAllowsExactlyTwoRequestsThenRejects() throws Exception {
        assertEquals(200,post("/api/v1/auth/login").statusCode());
        assertEquals(200,post("/api/v1/auth/login").statusCode());
        var limited=post("/api/v1/auth/login");
        assertEquals(429,limited.statusCode());
        assertEquals("86400",limited.headers().firstValue("Retry-After").orElse(null));
        assertTrue(limited.body().contains("\"code\":\"RATE_LIMITED\""),limited.body());
    }
    @Test void readLimitAllowsExactlyOneRequestThenRejects() throws Exception {
        String access=jwt();
        assertEquals(200,get("/api/v1/auth/me",access).statusCode());
        var limited=get("/api/v1/auth/me",access);
        assertEquals(429,limited.statusCode());
        assertEquals("86400",limited.headers().firstValue("Retry-After").orElse(null));
        assertTrue(limited.body().contains("\"code\":\"RATE_LIMITED\""),limited.body());
    }
}
