package com.baotin.threat;

import com.baotin.threat.infrastructure.SignalClients;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SignalClientsTests {
    @Test void scanBudgetPreventsStartingRequestsThatCannotFinishWithinTheDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            var clients = clients(server, 3000);
            assertNull(clients.model("text", "input", "test", System.nanoTime() + 100_000_000L));
            assertEquals(0, calls.get());
            assertFalse(clients.reputation(java.util.UUID.randomUUID(), "test", System.nanoTime() - 1).unavailable(), "Disabled reputation must not create an outage warning");
        } finally { server.stop(0); }
    }
    @Test void responseBodyDeadlineAlsoBoundsProvidersThatSendHeadersAndThenStall() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/internal/v1/inference/text", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                Thread.sleep(1500);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var clients = clients(server, 150);
            long start = System.nanoTime();
            assertNull(clients.model("text", "http://169.254.169.254/latest/meta-data", "test-correlation", System.nanoTime() + 2_000_000_000L));
            assertTrue((System.nanoTime() - start) / 1e6 < 1000, "A header-only response must not disable the deadline");
        } finally { server.stop(0); }
    }
    @Test void rejectsOversizedAndMalformedModelSignalsInsteadOfInventingScores() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/inference/text", exchange -> {
            byte[] body = "x".repeat(40000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try { assertNull(clients(server, 500).model("text", "input", "test", System.nanoTime() + 2_000_000_000L)); } finally { server.stop(0); }
    }
    @Test void usesPublishedReputationTargetContractAndTreatsMissingScoreAsUnknown() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.UUID target = java.util.UUID.randomUUID();
        server.createContext("/internal/v1/reputation/targets/" + target, exchange -> {
            byte[] body = ("{\"threatTargetId\":\"" + target + "\",\"finalScore\":75}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("Bearer TEST-ONLY-private-reputation-jwt", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            var clients = new SignalClients(origin, "", origin, "TEST-ONLY-private-reputation-jwt", 500, JsonMapper.builder().build(), new SimpleMeterRegistry());
            assertEquals(75, clients.reputation(target, "test", System.nanoTime() + 2_000_000_000L).score());
            assertNull(clients.reputation(null, "test", System.nanoTime() + 2_000_000_000L).score());
            assertNull(clients.reputation(java.util.UUID.randomUUID(), "test", System.nanoTime() + 2_000_000_000L).score());
        } finally { server.stop(0); }
    }
    private SignalClients clients(HttpServer server, int timeout) {
        return new SignalClients("http://127.0.0.1:" + server.getAddress().getPort(), "TEST-ONLY-service-credential-0123456789", "", "", timeout, JsonMapper.builder().build(), new SimpleMeterRegistry());
    }

    @Test void serviceModeUsesOnlyPrivateReadHeaderAndKeepsModelBearerUnchanged() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var target = java.util.UUID.randomUUID();
        var repHeader = new java.util.concurrent.atomic.AtomicReference<String>();
        var repAuthorization = new java.util.concurrent.atomic.AtomicReference<String>();
        var modelAuthorization = new java.util.concurrent.atomic.AtomicReference<String>();
        var modelServiceHeader = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/internal/v1/reputation/targets/" + target, exchange -> {
            repHeader.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            repAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"threatTargetId\":\"" + target + "\",\"finalScore\":42.25}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/internal/v1/inference/text", exchange -> {
            modelAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            modelServiceHeader.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new SignalClients(origin, "test-only-model-credential", origin, "a".repeat(64),
                "service-token", 800, JsonMapper.builder().build(), new SimpleMeterRegistry());
            var reputation = client.reputation(target, "test", System.nanoTime() + 3_000_000_000L);
            assertEquals(42.25, reputation.score()); assertFalse(reputation.unavailable());
            assertEquals("a".repeat(64), repHeader.get()); assertNull(repAuthorization.get());
            assertNull(client.model("text", "input", "test", System.nanoTime() + 3_000_000_000L));
            assertEquals("Bearer test-only-model-credential", modelAuthorization.get()); assertNull(modelServiceHeader.get());
            var missing = client.reputation(java.util.UUID.randomUUID(), "test", System.nanoTime() + 3_000_000_000L);
            assertNull(missing.score()); assertFalse(missing.unavailable());
        } finally { server.stop(0); }
    }

    @Test void serviceModeRejectsMalformedKeysAndUnknownModesWithoutLeakingTheValue() {
        for (String key : java.util.List.of("", "short", "A".repeat(64), "a".repeat(63), " ")) {
            var exception = assertThrows(IllegalArgumentException.class, () -> new SignalClients(
                "http://localhost:8185", "", "http://localhost:8184", key, "service-token", 800,
                JsonMapper.builder().build(), new SimpleMeterRegistry()));
            assertEquals("Invalid Reputation service-key configuration", exception.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> new SignalClients("http://localhost:8185", "", "", "",
            "unknown", 800, JsonMapper.builder().build(), new SimpleMeterRegistry()));
    }
}
