package com.baotin.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the shipped default Scan allowlist, not a test-only catalog override. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScanRoutesTests {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final HttpServer THREAT;
    static {
        try {
            REDIS.start(); THREAT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            THREAT.createContext("/api/v1/scans", exchange -> {
                boolean clean = exchange.getRequestHeaders().getFirst("X-User-Id") == null && exchange.getRequestHeaders().getFirst("X-Roles") == null;
                String client = exchange.getRequestHeaders().getFirst("X-BaoTin-Client-IP");
                byte[] body = ("{\"clean\":" + clean + ",\"client\":\"" + client + "\",\"path\":\"" + exchange.getRequestURI().getPath() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            }); THREAT.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost); registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("gateway.catalog.services.threat", () -> "http://127.0.0.1:" + THREAT.getAddress().getPort());
        registry.add("gateway.allowed-origins", () -> "http://localhost:4183");
    }
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();
    @Test void guestTextAndUrlReachThreatButInternalAndHistoryDoNot() throws Exception {
        for (String suffix : new String[]{"", "/text", "/url"}) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/scans" + suffix))
                    .header("Content-Type", "application/json").header("X-User-Id", "forged").header("X-Roles", "ADMIN").header("X-BaoTin-Client-IP", "203.0.113.66")
                    .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body()); assertTrue(response.body().contains("\"clean\":true"));
            assertFalse(response.body().contains("203.0.113.66"));
        }
        for (String path : new String[]{"/api/v1/scans/history", "/api/v1/scans/history/00000000-0000-4000-8000-000000000001", "/internal/v1/inference/text", "/api/v1/inference/text"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), path);
        }
    }
    @Test void scanCorsAllowsOnlyReviewedOrigin() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/scans/text"))
                .header("Origin", "http://localhost:4183").header("Access-Control-Request-Method", "POST").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); assertEquals("http://localhost:4183", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }
    @AfterAll static void close() { THREAT.stop(0); } // Redis is cleaned by Testcontainers after the Spring context closes.
}
