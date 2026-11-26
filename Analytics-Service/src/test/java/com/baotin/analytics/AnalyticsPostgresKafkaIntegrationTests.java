package com.baotin.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baotin.analytics.backfill.BackfillRunSummary;
import com.baotin.analytics.backfill.BackfillService;
import com.baotin.analytics.integration.ReportModerationClient;
import com.baotin.analytics.integration.ReportModerationView;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

@Tag("external-infrastructure")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AnalyticsPostgresKafkaIntegrationTests {

    private static final String SCAN_TOPIC = "baotin.scan.events.v1";
    private static final String REPORT_TOPIC = "baotin.report.events.v1";
    private static final String REPUTATION_TOPIC = "baotin.reputation.events.v1";
    private static final String GROUP_ID = "analytics-it-group";

    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    static final KafkaContainer BROKER = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        DB.start();
        BROKER.start();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", BROKER.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(SCAN_TOPIC, 1, (short) 1),
                    new NewTopic(REPORT_TOPIC, 1, (short) 1),
                    new NewTopic(REPUTATION_TOPIC, 1, (short) 1))).all().get();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("analytics.events.enabled", () -> true);
        registry.add("spring.kafka.bootstrap-servers", BROKER::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP_ID);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BackfillService backfillService;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReportModerationClient reportClient;

    @BeforeEach
    void clearProjection() {
        jdbc.execute("""
                TRUNCATE analytics.backfill_partition_checkpoints,
                         analytics.backfill_checkpoints,
                         analytics.inbox_events,
                         analytics.processed_scans,
                         analytics.known_targets,
                         analytics.report_lifecycle_state,
                         analytics.target_reputation_view,
                         analytics.daily_metrics
                """);
    }

    @Test
    void brokerPipelineProjectsEventsEnrichesQueueAndSupportsBoundedReplay() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OffsetDateTime occurredToday = today.atTime(10, 0).atOffset(ZoneOffset.UTC);
        OffsetDateTime occurredLater = today.atTime(11, 0).atOffset(ZoneOffset.UTC);

        UUID scanEventId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID targetEventId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID reportSubmittedEventId = UUID.randomUUID();
        UUID reportVerifiedEventId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID unscoredReportId = UUID.randomUUID();
        UUID reputationEventId = UUID.randomUUID();

        String scanEvent = """
                {"eventId":"%s","eventType":"scan.completed","eventVersion":1,"producer":"threat-service",
                 "aggregateType":"Scan","aggregateId":"%s","occurredAt":"%s",
                 "payload":{"scanId":"%s","scanType":"TEXT","riskLevel":"CRITICAL","authenticated":false}}
                """.formatted(scanEventId, scanId, occurredToday, scanId);

        String targetEvent = """
                {"eventId":"%s","eventType":"target.created","eventVersion":1,"producer":"threat-service",
                 "aggregateType":"ThreatTarget","aggregateId":"%s","occurredAt":"%s",
                 "payload":{"targetId":"%s","targetType":"URL"}}
                """.formatted(targetEventId, targetId, occurredToday, targetId);

        // Out-of-order: verified (version 1) arrives before submitted (version 0)
        String reportVerifiedEvent = """
                {"eventId":"%s","eventType":"report.verified","eventVersion":1,"producer":"report-service",
                 "aggregateType":"Report","aggregateId":"%s","occurredAt":"%s",
                 "payload":{"reportId":"%s","targetId":"%s","targetType":"URL","status":"VERIFIED","version":1}}
                """.formatted(reportVerifiedEventId, reportId, occurredLater, reportId, targetId);

        String reportSubmittedEvent = """
                {"eventId":"%s","eventType":"report.submitted","eventVersion":1,"producer":"report-service",
                 "aggregateType":"Report","aggregateId":"%s","occurredAt":"%s",
                 "payload":{"reportId":"%s","targetId":"%s","targetType":"URL","status":"SUBMITTED","version":0}}
                """.formatted(reportSubmittedEventId, reportId, occurredToday, reportId, targetId);

        String reputationEvent = """
                {"eventId":"%s","eventType":"reputation.score.changed","eventVersion":1,"producer":"reputation-service",
                 "aggregateType":"ThreatTarget","aggregateId":"%s","occurredAt":"%s",
                 "payload":{"threatTargetId":"%s","targetType":"URL","changedAt":"%s","newScore":92,"newLevel":"CRITICAL"}}
                """.formatted(reputationEventId, targetId, occurredLater, targetId, occurredLater);

        try (var producer = new KafkaProducer<String, String>(Map.of(
                "bootstrap.servers", BROKER.getBootstrapServers(),
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(SCAN_TOPIC, scanId.toString(), scanEvent)).get();
            // Duplicate delivery of identical scan event
            producer.send(new ProducerRecord<>(SCAN_TOPIC, scanId.toString(), scanEvent)).get();
            producer.send(new ProducerRecord<>(SCAN_TOPIC, targetId.toString(), targetEvent)).get();
            producer.send(new ProducerRecord<>(REPORT_TOPIC, reportId.toString(), reportVerifiedEvent)).get();
            producer.send(new ProducerRecord<>(REPORT_TOPIC, reportId.toString(), reportSubmittedEvent)).get();
            producer.send(new ProducerRecord<>(REPUTATION_TOPIC, targetId.toString(), reputationEvent)).get();
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Integer inboxCount = jdbc.queryForObject("SELECT COUNT(*) FROM analytics.inbox_events", Integer.class);
            if (inboxCount != null && inboxCount >= 5) {
                break;
            }
            Thread.sleep(100);
        }

        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM analytics.inbox_events", Integer.class));

        boolean offsetsCommitted = false;
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", BROKER.getBootstrapServers()))) {
            while (System.nanoTime() < deadline) {
                var offsets = admin.listConsumerGroupOffsets(GROUP_ID).partitionsToOffsetAndMetadata().get();
                long totalCommitted = offsets.values().stream().mapToLong(m -> m.offset()).sum();
                if (totalCommitted >= 6L) {
                    offsetsCommitted = true;
                    break;
                }
                Thread.sleep(100);
            }
        }
        assertTrue(offsetsCommitted, "Live consumer group must commit offsets after DB transactions succeed");

        mvc.perform(get("/api/v1/analytics/admin/trends?days=7")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.totals.totalScans").value(1))
                .andExpect(jsonPath("$.totals.textScans").value(1))
                .andExpect(jsonPath("$.totals.urlScans").value(0))
                .andExpect(jsonPath("$.totals.highRiskScans").value(1))
                .andExpect(jsonPath("$.totals.reportsCreated").value(1))
                .andExpect(jsonPath("$.totals.reportsVerified").value(1))
                .andExpect(jsonPath("$.totals.newThreats").value(1))
                .andExpect(jsonPath("$.riskBreakdown.CRITICAL").value(1))
                .andExpect(jsonPath("$.riskBreakdown.LOW").value(0))
                .andExpect(jsonPath("$.series.length()").value(7));

        var item1 = new ReportModerationView.Item(
                reportId, "URL", "https://phish.test/", "PHISHING_URL", "Phish", "VERIFIED", "HIGH", Instant.now(), 1, null);
        var item2 = new ReportModerationView.Item(
                unscoredReportId, "URL", "https://other.test/", "PHISHING_URL", "Other", "SUBMITTED", "LOW", Instant.now(), 0, null);
        when(reportClient.moderation(any(), any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(new ReportModerationView(
                        List.of(item1, item2),
                        0,
                        20,
                        2L,
                        1L,
                        new ReportModerationView.Counts(Map.of("VERIFIED", 1L, "SUBMITTED", 1L), Map.of("HIGH", 1L, "LOW", 1L), 1L)));

        mvc.perform(get("/api/v1/analytics/admin/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MODERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(reportId.toString()))
                .andExpect(jsonPath("$.items[0].riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.items[1].id").value(unscoredReportId.toString()))
                .andExpect(jsonPath("$.items[1].riskLevel").isEmpty());

        // Verify bounded Kafka replay does not double-count or alter live consumer group offsets
        Properties replayProps = new Properties();
        replayProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.getBootstrapServers());
        BackfillRunSummary replaySummary = backfillService.startKafkaReplayRun(
                replayProps,
                List.of(SCAN_TOPIC, REPORT_TOPIC, REPUTATION_TOPIC),
                10,
                Duration.ofMillis(500));
        assertEquals("COMPLETED", replaySummary.status());
        assertEquals(6L, replaySummary.processedCount());
        assertEquals(0L, replaySummary.appliedCount());
        assertEquals(6L, replaySummary.skippedCount());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT total_scans FROM analytics.daily_metrics WHERE metric_date = ?",
                Long.class,
                today));
    }
}
