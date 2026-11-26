package com.baotin.threat;

import com.baotin.threat.infrastructure.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.lifecycle.Startables;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ThreatServiceApplicationTests {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.0"));
    static final RSAKey KEY;
    static final HttpServer PROVIDERS;
    static final AtomicInteger MODEL_STATUS = new AtomicInteger(200);
    static final AtomicReference<String> LAST_TEXT = new AtomicReference<>();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static {
        try {
            Startables.deepStart(java.util.stream.Stream.of(POSTGRES, KAFKA)).join();
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE baotin_threat_runtime LOGIN PASSWORD 'TEST-ONLY-runtime-password'");
                statement.execute("CREATE SCHEMA threat AUTHORIZATION " + POSTGRES.getUsername());
                statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
                statement.execute("GRANT USAGE ON SCHEMA threat TO baotin_threat_runtime");
                statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA threat GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO baotin_threat_runtime");
            }
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            PROVIDERS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PROVIDERS.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            PROVIDERS.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            PROVIDERS.createContext("/internal/v1/inference/", exchange -> {
                try {
                    if (!"Bearer TEST-ONLY-service-credential-0123456789".equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401, -1); return; }
                    boolean text = exchange.getRequestURI().getPath().endsWith("text");
                    JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    if (text) LAST_TEXT.set(request.path("content").asText());
                    int status = MODEL_STATUS.get();
                    if (status != 200) { exchange.sendResponseHeaders(status, -1); return; }
                    Map<String, Double> probabilities = text ? Map.of("HAM", .1, "SPAM", .9) : Map.of("benign", .1, "phishing_bank", .5, "phishing_ecommerce", .1, "phishing_gaming", .1, "phishing_gov", .1, "phishing_other", .1);
                    byte[] body = JSON.writeValueAsBytes(Map.of("modelName", text ? "phobert-sms" : "canine-url", "modelVersion", "test-1", "modelChecksum", "a".repeat(64), "label", text ? "SPAM" : "phishing_bank", "confidence", text ? .9 : .5, "riskProbability", .9, "probabilities", probabilities, "latencyMs", 1));
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            PROVIDERS.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "baotin_threat_runtime");
        registry.add("spring.datasource.password", () -> "TEST-ONLY-runtime-password");
        registry.add("spring.flyway.user", POSTGRES::getUsername); registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("scan.hash-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("scan.content-key", () -> Base64.getEncoder().encodeToString(new byte[32])); registry.add("scan.retain-raw-text", () -> true);
        registry.add("scan.model-uri", () -> "http://127.0.0.1:" + PROVIDERS.getAddress().getPort());
        registry.add("scan.model-token", () -> "TEST-ONLY-service-credential-0123456789");
        registry.add("scan.jwks-uri", () -> "http://127.0.0.1:" + PROVIDERS.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("scan.kafka-bootstrap-servers", KAFKA::getBootstrapServers); registry.add("scan.events-enabled", () -> "true");
        registry.add("scan.outbox-interval-ms", () -> "600000"); registry.add("scan.guest-limit", () -> 100); registry.add("scan.user-limit", () -> 200);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScanMaintenance maintenance;
    @Autowired com.baotin.threat.application.TargetService targets;
    @Autowired OutboxPublisher publisher;
    final HttpClient client = HttpClient.newHttpClient();
    final UUID owner = UUID.randomUUID(), other = UUID.randomUUID();

    String token(UUID subject, String issuer, String audience) throws Exception {
        return token(subject, issuer, audience, new String[0]);
    }
    String token(UUID subject, String issuer, String audience, String... roles) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject.toString()).jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("sid", UUID.randomUUID().toString()).claim("ver", 0).claim("token_use", "access");
        if (roles.length > 0) claims.claim("roles", List.of(roles));
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(new JOSEObjectType("at+jwt")).build(), claims.build());
        signed.sign(new RSASSASigner(KEY.toRSAPrivateKey())); return signed.serialize();
    }
    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(HttpResponse<String> response) { assertEquals(200, response.statusCode(), response.body()); return JSON.readTree(response.body()); }

    @Test @Order(1) void realHttpScanPersistsSnapshotAndChecksEveryHistoryOwner() throws Exception {
        String access = token(owner, "baotin-auth", "baotin-api");
        String raw = "  Tài khoản sắp bị khóa, nhập OTP 123456 tại https://example.test/login?token=PRIVATE#fragment  ";
        JsonNode result = success(request("POST", "/api/v1/scans/text", access, Map.of("content", raw)));
        assertEquals(raw, LAST_TEXT.get(), "Inference must receive exact original training input");
        assertTrue(result.path("aiAvailable").asBoolean()); assertTrue(result.path("targetId").isNull());
        assertEquals(1, result.path("urls").size()); assertEquals("https://example.test/login", result.path("urls").get(0).path("url").asText());
        assertFalse(result.toString().contains("123456")); assertFalse(result.toString().contains("PRIVATE"));
        var signals = JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM threat.outbox_events WHERE event_type='threat.updated' AND aggregate_id=?::uuid ORDER BY created_at DESC LIMIT 1", String.class, result.path("urls").get(0).path("targetId").asText()));
        assertEquals("baotin.threat.events.v1", jdbc.queryForObject("SELECT topic FROM threat.outbox_events WHERE id=?::uuid", String.class, signals.path("eventId").asText()));
        assertEquals("ThreatTarget", signals.path("aggregateType").asText());
        assertEquals(90, signals.path("payload").path("aiSignal").asDouble(), 1e-8);
        String id = result.path("scanId").asText();
        assertEquals(result, success(request("GET", "/api/v1/scans/history/" + id, access, null)));
        assertTrue(result.path("preview").asText().contains("Tài khoản sắp bị khóa"));
        assertTrue(result.path("preview").asText().contains("***"));
        assertFalse(result.path("preview").asText().contains("đã ẩn"));
        assertFalse(jdbc.queryForObject("SELECT result_snapshot::text FROM threat.scan_history WHERE id=?::uuid",String.class,id).contains("123456"));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM threat.public_message_templates",Integer.class)>0);
        assertFalse(jdbc.queryForObject("SELECT string_agg(redacted_text,' ') FROM threat.public_message_templates",String.class).contains("123456"));
        String foreign = token(other, "baotin-auth", "baotin-api");
        assertEquals(404, request("GET", "/api/v1/scans/history/" + id, foreign, null).statusCode());
        assertEquals(404, request("DELETE", "/api/v1/scans/history/" + id, foreign, null).statusCode());
        assertEquals(401, request("GET", "/api/v1/scans/history", null, null).statusCode());
        byte[] ciphertext = jdbc.queryForObject("SELECT encrypted_content FROM threat.scan_history WHERE id=?::uuid", byte[].class, id);
        assertNotNull(ciphertext); assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("123456"));
        jdbc.update("UPDATE threat.scan_history SET content_expires_at=now()-interval '1 second' WHERE id=?::uuid", id);
        maintenance.expireContent();
        assertNull(jdbc.queryForObject("SELECT encrypted_content FROM threat.scan_history WHERE id=?::uuid", byte[].class, id));
        assertEquals(204, request("DELETE", "/api/v1/scans/history/" + id, access, null).statusCode());
        assertEquals(404, request("GET", "/api/v1/scans/history/" + id, access, null).statusCode());
    }
    @Test @Order(2) void guestScanCreatesNoHistoryAndConcurrentTargetsHaveOneIdentity() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM threat.scan_history", Long.class);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 4; i++) jobs.add(executor.submit(() -> success(request("POST", "/api/v1/scans/url", null, Map.of("url", "HTTPS://EXAMPLE.TEST:443/same?utm_source=ignored")))));
            Set<String> ids = new HashSet<>(); for (var job : jobs) ids.add(job.get().path("targetId").asText());
            assertEquals(1, ids.size());
        }
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM threat.scan_history", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM threat.targets WHERE normalized_value='https://example.test/same'", Integer.class));
    }
    @Test @Order(3) void securityAndFiltersFailClosed() throws Exception {
        for (String access : List.of("bogus", token(owner, "wrong-issuer", "baotin-api"), token(owner, "baotin-auth", "wrong-audience")))
            assertEquals(401, request("POST", "/api/v1/scans/url", access, Map.of("url", "https://example.test")).statusCode());
        assertEquals(400, request("POST", "/api/v1/scans", null, Map.of("type", "PHONE", "content", "0901234567")).statusCode());
        assertEquals(400, request("POST", "/api/v1/scans/text", null, Map.of("content", "secret", "userId", owner)).statusCode());
        String access = token(owner, "baotin-auth", "baotin-api");
        assertEquals(400, request("GET", "/api/v1/scans/history?size=101", access, null).statusCode());
        assertEquals(400, request("GET", "/api/v1/scans/history?sort=password", access, null).statusCode());
        assertEquals(200, request("GET", "/actuator/health/liveness", null, null).statusCode());
        assertEquals(200, request("GET", "/actuator/health/readiness", null, null).statusCode());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute("CREATE TABLE threat.forbidden(id int)"));
    }
    @Test @Order(4) void outboxPublishesRealKafkaEventsWithoutRawTextAndCanRedeliverStableIds() throws Exception {
        success(request("POST", "/api/v1/scans/url", null, Map.of("url", "https://outbox.example.test/?token=PRIVATE")));
        Properties config = new Properties(); config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString()); config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()); config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            consumer.subscribe(List.of("baotin.scan.events.v1")); publisher.publish();
            List<JsonNode> events = new ArrayList<>(); long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (events.isEmpty() && System.nanoTime() < until) for (var record : consumer.poll(Duration.ofMillis(500))) {
                assertFalse(record.value().contains("PRIVATE")); assertFalse(record.value().contains("123456"));
                JsonNode event = JSON.readTree(record.value()); assertEquals(event.path("aggregateId").asText(), record.key()); events.add(event);
            }
            assertFalse(events.isEmpty());
            String id = events.getFirst().path("eventId").asText();
            jdbc.update("UPDATE threat.outbox_events SET status='PENDING',next_attempt_at=NULL WHERE id=?::uuid", id);
            publisher.publish();
            boolean duplicate = false; until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!duplicate && System.nanoTime() < until) for (var record : consumer.poll(Duration.ofMillis(500))) duplicate |= JSON.readTree(record.value()).path("eventId").asText().equals(id);
            assertTrue(duplicate, "Consumers must deduplicate the unchanged eventId after replay");
        }
    }
    @Test @Order(5) void guestRateLimitCannotBeBypassedWithForgedForwardedHeaders() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/scans/text")).header("Content-Type", "application/json")
                .header("X-BaoTin-Client-IP", "198.51.100.42").header("X-User-Id", owner.toString()).POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"hello\"}")).build();
        jdbc.update("UPDATE threat.rate_limits SET attempts=1000000");
        assertEquals(429, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        jdbc.update("DELETE FROM threat.rate_limits");
    }
    @Test @Order(7) void reportScanLinkValidatesOwnerTypeAndOriginalInput() throws Exception {
        String text="Original message input with OTP 123456";
        var scan=success(request("POST","/api/v1/scans/text",token(owner,"baotin-auth","baotin-api"),Map.of("content",text)));
        UUID id=UUID.fromString(scan.path("scanId").asText());
        assertEquals("MESSAGE_TEMPLATE",targets.resolve("MESSAGE_TEMPLATE",text,id,owner).type());
        assertThrows(NoSuchElementException.class,()->targets.resolve("MESSAGE_TEMPLATE",text,id,other));
        assertThrows(IllegalArgumentException.class,()->targets.resolve("URL","https://example.test/",id,owner));
        long before=jdbc.queryForObject("SELECT count(*) FROM threat.targets",Long.class);
        assertThrows(IllegalArgumentException.class,()->targets.resolve("MESSAGE_TEMPLATE","A different message",id,owner));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM threat.targets",Long.class));
    }
    @Test @Order(8) void reportResolverSharesTheScanTargetIdentity() throws Exception {
        String url = "https://report-identity.example/a%2Fb?b=2&a=1&token=PRIVATE";
        var scan = success(request("POST", "/api/v1/scans/url", null, Map.of("url", url)));
        var resolved = targets.resolve("URL", url);
        assertEquals(scan.path("targetId").asText(), resolved.id().toString());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM threat.targets WHERE id=?", Long.class, resolved.id()));
    }
    @Test @Order(9) void unavailableAiUsesExplicitFallbackWithoutInventingLabels() throws Exception {
        MODEL_STATUS.set(503);
        JsonNode result = success(request("POST", "/api/v1/scans/text", null, Map.of("content", "Tài khoản sắp bị khóa, xác minh ngay mã OTP")));
        assertFalse(result.path("aiAvailable").asBoolean()); assertTrue(result.path("degraded").asBoolean());
        assertTrue(result.path("textModel").isNull()); assertTrue(result.path("risk").path("score").asDouble() > 0);
    }
    @Test @Order(7) void capsEmbeddedWorkAndRetainsOnlyMaskedDangerousTemplates() throws Exception {
        long templatesBefore = jdbc.queryForObject("SELECT count(*) FROM threat.targets WHERE type='MESSAGE_TEMPLATE'", Long.class);
        String content = java.util.stream.IntStream.range(0, 7).mapToObj(i -> "https://limit-" + i + ".example.test/").collect(java.util.stream.Collectors.joining(" "));
        JsonNode result = success(request("POST", "/api/v1/scans/text", null, Map.of("content", content)));
        assertEquals(5, result.path("urls").size());
        assertTrue(result.path("warnings").toString().contains("EMBEDDED_URL_LIMIT"));
        assertEquals(templatesBefore + 1, jdbc.queryForObject("SELECT count(*) FROM threat.targets WHERE type='MESSAGE_TEMPLATE'", Long.class),
                "Dangerous submissions create only a masked public template, never raw SMS");
    }
    @Test @Order(6) void failedOutboxInsertRollsBackTargetsAndHistoryInTheSameTransaction() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION threat.test_reject_scan_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='scan.completed' THEN RAISE EXCEPTION 'test outbox failure'; END IF; RETURN NEW; END $$");
            statement.execute("CREATE TRIGGER test_outbox_failure BEFORE INSERT ON threat.outbox_events FOR EACH ROW EXECUTE FUNCTION threat.test_reject_scan_event()");
            try {
                long before = jdbc.queryForObject("SELECT count(*) FROM threat.scan_history", Long.class);
                assertEquals(503, request("POST", "/api/v1/scans/url", token(owner, "baotin-auth", "baotin-api"), Map.of("url", "https://rollback.example.test/")).statusCode());
                assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM threat.scan_history", Long.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM threat.targets WHERE normalized_value='https://rollback.example.test/'", Integer.class));
            } finally { statement.execute("DROP FUNCTION threat.test_reject_scan_event() CASCADE"); }
        }
    }
    @Test @Order(10) void adminScanStatsAreRoleProtectedAndCountGuestScans() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long before = jdbc.queryForObject("""
            SELECT coalesce(scans,0) FROM threat.scan_metrics_daily WHERE metric_date=?
            """, Long.class, java.sql.Date.valueOf(today));
        success(request("POST", "/api/v1/scans/text", null,
            Map.of("content", "Tin nhắn khách vãng lai cần được đếm")));
        String admin = token(owner, "baotin-auth", "baotin-api", "ADMIN");
        JsonNode stats = success(request("GET", "/api/v1/scans/admin/stats", admin, null));
        assertEquals(before + 1, stats.path("scansToday").asLong());
        assertTrue(stats.path("highRiskScansToday").asLong() >= 0);
        assertEquals(401, request("GET", "/api/v1/scans/admin/stats", null, null).statusCode());
        assertEquals(403, request("GET", "/api/v1/scans/admin/stats",
            token(owner, "baotin-auth", "baotin-api", "USER"), null).statusCode());
    }

    @AfterAll static void shutdown() { PROVIDERS.stop(0); KAFKA.stop(); POSTGRES.stop(); }
}
