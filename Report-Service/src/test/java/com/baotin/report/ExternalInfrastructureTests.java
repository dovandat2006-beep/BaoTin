package com.baotin.report;

import com.baotin.report.api.ReportModels.*;
import com.baotin.report.application.ReportService;
import com.baotin.report.domain.ReportStatus;
import com.baotin.report.infrastructure.*;
import com.sun.net.httpserver.HttpServer;
import io.minio.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real infrastructure; only Threat's HTTP contract is a fixture owned by this test. */
@Tag("external-infrastructure")
@SpringBootTest
class ExternalInfrastructureTests {
    static final String ACCESS = "report-integration";
    static final String SECRET = UUID.randomUUID().toString();
    static final UUID TARGET = UUID.randomUUID();
    static final UUID LEGACY_FIRST = UUID.randomUUID(), LEGACY_SECOND = UUID.randomUUID();
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("postgres:17.6-alpine"))
        .withEnv("POSTGRES_PASSWORD", SECRET).withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    // The upstream minio/minio image repository is no longer publicly available.
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("bitnamilegacy/minio:2025.4.22-debian-12-r0"))
        .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
        .withCommand("/opt/bitnami/minio/bin/minio", "server", "/tmp/report-evidence").withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/ready"));
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.0"));
    static final HttpServer THREAT;
    static MinioClient objects;
    static {
        try {
            POSTGRES.start();
            String url="jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/postgres";
            org.flywaydb.core.Flyway.configure().dataSource(url,"postgres",SECRET).schemas("report").defaultSchema("report").target("2").load().migrate();
            // Existing outbox rows deliberately have insertion order opposite to business order.
            try(var connection=java.sql.DriverManager.getConnection(url,"postgres",SECRET);
                var statement=connection.prepareStatement("INSERT INTO report.outbox_events(id,aggregate_id,event_type,payload,created_at,published_at,next_attempt_at) VALUES(?,?,'legacy','{}',?,?,?)")) {
                for(int i=0;i<2;i++) {
                    statement.setObject(1,i==0?LEGACY_SECOND:LEGACY_FIRST);statement.setObject(2,TARGET);
                    var time=java.sql.Timestamp.from(java.time.Instant.parse(i==0?"2001-01-01T00:00:00Z":"2000-01-01T00:00:00Z"));
                    statement.setTimestamp(3,time);statement.setTimestamp(4,time);statement.setTimestamp(5,time);statement.executeUpdate();
                }
            }
            try(var connection=java.sql.DriverManager.getConnection(url,"postgres",SECRET);var statement=connection.createStatement()) {
                statement.execute("CREATE ROLE report_runtime LOGIN PASSWORD '"+SECRET+"'");
                statement.execute("GRANT USAGE ON SCHEMA report TO report_runtime");
                statement.execute("GRANT SELECT,INSERT,UPDATE ON report.reports,report.evidence,report.idempotency_keys,report.outbox_events TO report_runtime");
                statement.execute("GRANT SELECT,INSERT ON report.status_history TO report_runtime");
                statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA report GRANT USAGE ON SEQUENCES TO report_runtime");
                statement.execute("CREATE SCHEMA other_service");
                statement.execute("CREATE TABLE other_service.private_data(id int)");
            }
            MINIO.start(); KAFKA.start();
            objects = MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).build();
            objects.makeBucket(MakeBucketArgs.builder().bucket("baotin-evidence").build());
            THREAT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            THREAT.createContext("/internal/v1/targets/resolve", exchange -> {
                // Exercise TargetClient serialization and the shared service credential.
                String credential = exchange.getRequestHeaders().getFirst("X-Service-Token");
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean allowed = SECRET.equals(credential) && request.contains("URL") && request.contains("phishing.example");
                byte[] body = ("{\"id\":\"" + TARGET + "\",\"type\":\"URL\",\"displayValue\":\"https://phishing.example/\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(allowed ? 200 : 403, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            THREAT.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/postgres");
        r.add("spring.datasource.username", () -> "report_runtime"); r.add("spring.datasource.password", () -> SECRET);
        r.add("spring.flyway.user", () -> "postgres"); r.add("spring.flyway.password", () -> SECRET);
        r.add("report.threat-uri", () -> "http://127.0.0.1:" + THREAT.getAddress().getPort());
        r.add("report.internal-token", () -> SECRET);
        r.add("report.minio.endpoint", ExternalInfrastructureTests::endpoint);
        r.add("report.minio.access-key", () -> ACCESS); r.add("report.minio.secret-key", () -> SECRET);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @AfterAll static void stop() { THREAT.stop(0); KAFKA.stop(); MINIO.stop(); POSTGRES.stop(); }
    @Autowired ReportService reports;
    @Autowired OutboxPublisher publisher;
    @Autowired EvidenceCleanup cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test void privateEvidenceAndOrderedLifecycleReachRealInfrastructure() throws Exception {
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("CREATE TABLE report.forbidden(id int)"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForList("SELECT * FROM other_service.private_data"));
        assertEquals(1L,jdbc.queryForObject("SELECT publication_order FROM report.outbox_events WHERE id=?",Long.class,LEGACY_FIRST));
        assertEquals(2L,jdbc.queryForObject("SELECT publication_order FROM report.outbox_events WHERE id=?",Long.class,LEGACY_SECOND));
        UUID owner = UUID.randomUUID(), moderator = UUID.randomUUID(), key = UUID.randomUUID();
        String correlation = UUID.randomUUID().toString();
        var draft = new Create("URL", "https://phishing.example/", "PHISHING_URL", "Suspicious website", "The website requests banking credentials.", null, null);
        var report = reports.create(owner, key, draft, correlation);
        assertTrue(jdbc.queryForObject("SELECT publication_order FROM report.outbox_events WHERE aggregate_id=?",Long.class,report.id())>2L);
        assertEquals(TARGET, report.threatTargetId());
        assertEquals(report, reports.create(owner, key, draft, correlation));
        byte[] bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jTz0AAAAASUVORK5CYII=");
        var evidence = reports.upload(report.id(), owner, bytes, "image/png", "screenshot.png");
        try (var stream = reports.download(report.id(), evidence.id(), owner, false).stream()) { assertArrayEquals(bytes, stream.readAllBytes()); }
        String objectKey = jdbc.queryForObject("SELECT object_key FROM report.evidence WHERE id=?", String.class, evidence.id());
        var anonymous = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint() + "/baotin-evidence/" + objectKey)).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(403, anonymous.statusCode());
        reports.deleteEvidence(report.id(), evidence.id(), owner);
        cleanup.cleanup();
        assertNotNull(jdbc.queryForObject("SELECT purged_at FROM report.evidence WHERE id=?", java.sql.Timestamp.class, evidence.id()));
        assertThrows(io.minio.errors.ErrorResponseException.class, () -> objects.statObject(StatObjectArgs.builder().bucket("baotin-evidence").object(objectKey).build()));
        var current = reports.detail(report.id(), owner, false);
        var pending = reports.review(report.id(), moderator, ReportStatus.PENDING_REVIEW, new Decision(current.version(), "Review evidence"), correlation);
        reports.review(report.id(), moderator, ReportStatus.VERIFIED, new Decision(pending.version(), "Confirmed phishing"), correlation);
        // The first batch may only send the earliest event of each aggregate.
        publisher.publish(); publisher.publish();
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM report.outbox_events WHERE published_at IS NULL", Long.class));
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> types = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            consumer.subscribe(List.of("baotin.report.events.v1"));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (types.size() < 2 && System.nanoTime() < deadline) for (var event : consumer.poll(Duration.ofSeconds(1))) {
                assertEquals(report.id().toString(), event.key());
                var envelope = json.readTree(event.value());
                assertEquals(correlation, envelope.path("correlationId").asText());
                assertFalse(event.value().contains("banking credentials"));
                assertFalse(event.value().contains("object_key"));
                types.add(envelope.path("eventType").asText());
            }
        }
        assertEquals(List.of("report.submitted", "report.verified"), types);
    }
}
