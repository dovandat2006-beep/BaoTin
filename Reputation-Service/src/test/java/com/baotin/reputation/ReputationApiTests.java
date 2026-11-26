package com.baotin.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baotin.reputation.api.ReputationResponse;
import com.baotin.reputation.application.EventIngestor;
import com.baotin.reputation.application.OutboxStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReputationApiTests {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("reputation.threat-read-token", () -> "a".repeat(64));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    EventIngestor ingestor;

    @Autowired
    OutboxStore outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void clean() {
        jdbc.execute("""
            TRUNCATE reputation.target_reputation, reputation.report_signals,
                     reputation.score_history, reputation.inbox_events, reputation.outbox_events
            """);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/internal/v1/reputation/targets/{id}", UUID.randomUUID()))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void serviceKeyReadsRealScoreButCannotRecalculateOrEscapeReadPath() throws Exception {
        UUID target = UUID.randomUUID();
        ingestor.ingest(threatEvent(UUID.randomUUID(), target, "80", Instant.now()));
        String path = "/internal/v1/reputation/targets/" + target;
        int history = jdbc.queryForObject("SELECT count(*) FROM reputation.score_history", Integer.class);
        mvc.perform(get(path).header("X-Service-Token", "a".repeat(64)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.finalScore").value(20.0));
        mvc.perform(get("/internal/v1/reputation/targets/{id}", UUID.randomUUID())
                .header("X-Service-Token", "a".repeat(64)))
            .andExpect(status().isNotFound());
        mvc.perform(post(path + "/recalculate").header("X-Service-Token", "a".repeat(64))
                .contentType(MediaType.APPLICATION_JSON).content("{\"signals\":{\"ruleSignal\":100}}"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get(path + "/recalculate").header("X-Service-Token", "a".repeat(64)))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.score_history", Integer.class)).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT rule_signal FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, target)).isEqualByComparingTo("80");
    }

    @Test
    void serviceKeyRejectsWrongEmptyDuplicateAndMixedCredentials() throws Exception {
        String path = "/internal/v1/reputation/targets/" + UUID.randomUUID();
        for (String invalid : java.util.List.of("", "b".repeat(64), "A".repeat(64), "a".repeat(63), " a".repeat(32))) {
            mvc.perform(get(path).header("X-Service-Token", invalid))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        mvc.perform(get(path).header("X-Service-Token", "a".repeat(64), "a".repeat(64)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Service-Token", "a".repeat(64) + "," + "a".repeat(64)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Service-Token", "a".repeat(64)).header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Service-Token", "a".repeat(64)).header("Authorization", ""))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void readHeaderNeverGrantsHealthOrUserAdminPermissions() throws Exception {
        mvc.perform(get("/actuator/health/liveness").header("X-Service-Token", "wrong"))
            .andExpect(status().isOk()); // The private filter must not be registered globally.
        String path = "/internal/v1/reputation/targets/" + UUID.randomUUID();
        mvc.perform(post(path + "/recalculate").with(admin()).header("X-Service-Token", "a".repeat(64))
                .contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"URL\"}"))
            .andExpect(status().isForbidden()); // A pre-existing context cannot elevate the service key.
        mvc.perform(get(path).header("X-Service-Token", "wrong").header("X-User-Role", "ADMIN"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void moderatorCanReadButOnlyAdminCanRecalculate() throws Exception {
        UUID targetId = UUID.randomUUID();
        mvc.perform(post("/internal/v1/reputation/targets/{id}/recalculate", targetId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"targetType":"URL","signals":{"reportSignal":60,"ruleSignal":40,
                     "aiSignal":30,"campaignSignal":20},"totalReportCount":10,
                     "verifiedReportCount":6,"reason":"seed"}
                    """))
            .andExpect(status().isOk());

        mvc.perform(get("/internal/v1/reputation/targets/{id}", targetId)
                .with(role("ROLE_MODERATOR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.finalScore").value(41.5))
            .andExpect(jsonPath("$.riskLevel").value("MEDIUM"));

        mvc.perform(post("/internal/v1/reputation/targets/{id}/recalculate", targetId)
                .with(role("ROLE_MODERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(get("/internal/v1/reputation/targets/{id}", targetId)
                .with(role("ROLE_USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminRecalculationCreatesTargetHistoryAndOutbox() throws Exception {
        UUID targetId = UUID.randomUUID();
        mvc.perform(post("/internal/v1/reputation/targets/{id}/recalculate", targetId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"targetType":"URL","signals":{"reportSignal":60,"ruleSignal":40,
                     "aiSignal":30,"campaignSignal":20},"totalReportCount":10,
                     "verifiedReportCount":6,"reason":"seed"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.threatTargetId").value(targetId.toString()))
            .andExpect(jsonPath("$.targetType").value("URL"))
            .andExpect(jsonPath("$.reportSignal").value(60.0))
            .andExpect(jsonPath("$.ruleSignal").value(40.0))
            .andExpect(jsonPath("$.aiSignal").value(30.0))
            .andExpect(jsonPath("$.campaignSignal").value(20.0))
            .andExpect(jsonPath("$.finalScore").value(41.5))
            .andExpect(jsonPath("$.riskLevel").value("MEDIUM"))
            .andExpect(jsonPath("$.confidence").value(1.0))
            .andExpect(jsonPath("$.totalReportCount").value(10))
            .andExpect(jsonPath("$.verifiedReportCount").value(6))
            .andExpect(jsonPath("$.calculationVersion").value("v1"))
            .andExpect(jsonPath("$.version").value(1));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM reputation.score_history WHERE threat_target_id=?",
            Integer.class, targetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM reputation.outbox_events WHERE aggregate_id=?",
            Integer.class, targetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId)).isEqualByComparingTo("41.50");

        String payload = jdbc.queryForObject(
            "SELECT payload::text FROM reputation.outbox_events WHERE aggregate_id=?", String.class, targetId);
        JsonNode event = json.readTree(payload);
        assertThat(event.path("eventType").asText()).isEqualTo("reputation.score.changed");
        assertThat(event.path("producer").asText()).isEqualTo("reputation-service");
        assertThat(event.path("payload").path("oldScore").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(event.path("payload").path("newScore").decimalValue()).isEqualByComparingTo("41.50");
        assertThat(event.path("payload").path("newLevel").asText()).isEqualTo("MEDIUM");

        mvc.perform(get("/internal/v1/reputation/targets/{id}", targetId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.finalScore").value(41.5));
    }

    @Test
    void duplicateReportEventIsIdempotent() {
        UUID targetId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        String event = reportEvent("report.verified", UUID.randomUUID(), reportId, targetId, "80", Instant.now());

        ingestor.ingest(event);
        ingestor.ingest(event);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.inbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.report_signals", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT total_report_count FROM reputation.target_reputation WHERE threat_target_id=?",
            Integer.class, targetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT verified_report_count FROM reputation.target_reputation WHERE threat_target_id=?",
            Integer.class, targetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.score_history", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void staleRevokeDoesNotChangeScore() {
        UUID targetId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Instant now = Instant.now();
        ingestor.ingest(reportEvent("report.verified", UUID.randomUUID(), reportId, targetId, "90",
            now.minusSeconds(3600)));
        BigDecimal before = jdbc.queryForObject(
            "SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId);

        ingestor.ingest(reportEvent("report.revoked", UUID.randomUUID(), reportId, targetId, "90",
            now.minusSeconds(7200)));

        BigDecimal after = jdbc.queryForObject(
            "SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId);
        assertThat(after).isEqualByComparingTo(before);
        assertThat(jdbc.queryForObject(
            "SELECT verified_report_count FROM reputation.target_reputation WHERE threat_target_id=?",
            Integer.class, targetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.inbox_events", Integer.class)).isEqualTo(2);
    }

    @Test
    void outOfOrderThreatSignalIsIgnored() {
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        ingestor.ingest(threatEvent(UUID.randomUUID(), targetId, "80", now.minusSeconds(3600)));
        BigDecimal before = jdbc.queryForObject(
            "SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId);

        ingestor.ingest(threatEvent(UUID.randomUUID(), targetId, "0", now.minusSeconds(7200)));

        assertThat(jdbc.queryForObject(
            "SELECT rule_signal FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId)).isEqualByComparingTo("80.00");
        assertThat(jdbc.queryForObject(
            "SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId)).isEqualByComparingTo(before);
    }

    @Test
    void reportRejectedMarksContributionRevokedAndReducesScore() {
        UUID targetId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Instant now = Instant.now();
        ingestor.ingest(reportEvent("report.verified", UUID.randomUUID(), reportId, targetId, "100",
            now.minusSeconds(60)));
        BigDecimal verified = jdbc.queryForObject(
            "SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId);
        assertThat(verified).isGreaterThan(BigDecimal.ZERO);

        ingestor.ingest(reportEvent("report.rejected", UUID.randomUUID(), reportId, targetId, "100", now));

        assertThat(jdbc.queryForObject(
            "SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject(
            "SELECT verified_report_count FROM reputation.target_reputation WHERE threat_target_id=?",
            Integer.class, targetId)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT status FROM reputation.report_signals WHERE report_id=?", String.class, reportId))
            .isEqualTo("REVOKED");
    }

    @Test
    void newTargetWithoutTypeIsRejected() throws Exception {
        mvc.perform(post("/internal/v1/reputation/targets/{id}/recalculate", UUID.randomUUID()).with(admin()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TARGET_TYPE_REQUIRED"));
    }

    @Test
    void invalidTargetTypeIsRejected() throws Exception {
        mvc.perform(post("/internal/v1/reputation/targets/{id}/recalculate", UUID.randomUUID())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"WALLET\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TARGET_TYPE"));
    }

    @Test
    void outboxClaimAndPublishAreLeased() throws Exception {
        UUID targetId = UUID.randomUUID();
        mvc.perform(post("/internal/v1/reputation/targets/{id}/recalculate", targetId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"PHONE\",\"signals\":{\"ruleSignal\":90}}"))
            .andExpect(status().isOk());

        var rows = outbox.claim(10);
        assertThat(rows).hasSize(1);
        Map<String, Object> claimed = jdbc.queryForMap(
            "SELECT status,attempts FROM reputation.outbox_events WHERE id=?", rows.getFirst().id());
        assertThat(claimed.get("status")).isEqualTo("PROCESSING");
        assertThat(((Number) claimed.get("attempts")).intValue()).isEqualTo(1);

        assertThat(outbox.published(rows.getFirst().id(), rows.getFirst().leaseId())).isTrue();
        Map<String, Object> published = jdbc.queryForMap(
            "SELECT status,published_at FROM reputation.outbox_events WHERE id=?", rows.getFirst().id());
        assertThat(published.get("status")).isEqualTo("PUBLISHED");
        assertThat(published.get("published_at")).isNotNull();
    }

    @Test
    void campaignEventUpdatesOnlyCampaignSignal() {
        UUID targetId = UUID.randomUUID();
        ingestor.ingest(campaignEvent(UUID.randomUUID(), targetId, "70", Instant.now()));

        assertThat(jdbc.queryForObject(
            "SELECT campaign_signal FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId)).isEqualByComparingTo("70.00");
        assertThat(jdbc.queryForObject(
            "SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?",
            BigDecimal.class, targetId)).isEqualByComparingTo("10.50");
    }

    @Test
    void missingEventTypeIsRejected() {
        UUID eventId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        assertThatThrownBy(() -> ingestor.ingest("""
            {"eventId":"%s","eventVersion":1,"occurredAt":"2026-10-22T00:00:00Z",
             "producer":"report-service","aggregateType":"Report","aggregateId":"%s",
             "correlationId":"%s","causationId":null,
             "payload":{"reportId":"%s","threatTargetId":"%s","targetType":"URL","severity":80}}
            """.formatted(eventId, targetId, UUID.randomUUID(), UUID.randomUUID(), targetId)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventType");
    }

    @Test
    void unknownEventTypeIsAckedWithoutInboxNoise() {
        UUID eventId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ingestor.ingest("""
            {"eventId":"%s","eventType":"future.event","eventVersion":1,
             "occurredAt":"2026-10-22T00:00:00Z","producer":"future-service",
             "aggregateType":"ThreatTarget","aggregateId":"%s",
             "correlationId":"%s","causationId":null,"payload":{"newField":true}}
            """.formatted(eventId, targetId, UUID.randomUUID()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.inbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.target_reputation", Integer.class)).isZero();
    }

    @Test
    void expiredOutboxLeaseIsReclaimed() throws Exception {
        UUID targetId = UUID.randomUUID();
        mvc.perform(post("/internal/v1/reputation/targets/{id}/recalculate", targetId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"EMAIL\",\"signals\":{\"campaignSignal\":80}}"))
            .andExpect(status().isOk());

        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM reputation.outbox_events WHERE aggregate_id=?", UUID.class, targetId);
        jdbc.update("""
            UPDATE reputation.outbox_events
            SET status='PROCESSING',lease_id=?,lease_until=now()-interval '1 minute'
            WHERE id=?
            """, UUID.randomUUID(), eventId);

        assertThat(outbox.claim(10)).hasSize(1);
    }

    @Test
    void staleOutboxWorkerCannotOverwriteReclaimedLease() throws Exception {
        UUID targetId = UUID.randomUUID();
        mvc.perform(post("/internal/v1/reputation/targets/{id}/recalculate", targetId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"EMAIL\",\"signals\":{\"campaignSignal\":80}}"))
            .andExpect(status().isOk());

        var first = outbox.claim(1).getFirst();
        jdbc.update("""
            UPDATE reputation.outbox_events
            SET lease_until=now()-interval '1 minute'
            WHERE id=?
            """, first.id());
        var second = outbox.claim(1).getFirst();

        assertThat(first.leaseId()).isNotEqualTo(second.leaseId());
        assertThat(outbox.published(first.id(), first.leaseId())).isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT status FROM reputation.outbox_events WHERE id=?", String.class, first.id()))
            .isEqualTo("PROCESSING");
        assertThat(outbox.published(second.id(), second.leaseId())).isTrue();
    }

    @Test
    void responseJsonRoundTripsForCache() {
        ReputationResponse response = new ReputationResponse(UUID.randomUUID(), "URL",
            new BigDecimal("60.00"), new BigDecimal("40.00"), new BigDecimal("30.00"),
            new BigDecimal("20.00"), new BigDecimal("41.50"), "MEDIUM", new BigDecimal("1.00000"),
            10, 6, "v1", Instant.parse("2026-10-22T00:00:00Z"), 3);

        String raw = json.writeValueAsString(response);
        ReputationResponse decoded = json.readValue(raw, ReputationResponse.class);

        assertThat(decoded.threatTargetId()).isEqualTo(response.threatTargetId());
        assertThat(decoded.finalScore()).isEqualByComparingTo("41.50");
        assertThat(decoded.calculatedAt()).isEqualTo(response.calculatedAt());
        assertThat(decoded.version()).isEqualTo(3);
    }

    @Test
    void canonicalReportLifecyclePreservesIdempotencyAndLegacyOrdering() {
        UUID targetId = UUID.randomUUID(), reportId = UUID.randomUUID();
        Instant now = Instant.now();
        String verified = canonicalReportEvent("report.verified", reportId, targetId, "URL", "VERIFIED", now.minusSeconds(60));
        ingestor.ingest(verified);
        ingestor.ingest(verified);
        assertThat(jdbc.queryForObject("SELECT weight FROM reputation.report_signals WHERE report_id=?", BigDecimal.class, reportId))
            .isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.score_history", Integer.class)).isEqualTo(1);
        ingestor.ingest(canonicalReportEvent("report.rejected", reportId, targetId, "URL", "REJECTED", now));
        ingestor.ingest(canonicalReportEvent("report.verified", reportId, targetId, "URL", "VERIFIED", now.minusSeconds(120)));
        assertThat(jdbc.queryForObject("SELECT status FROM reputation.report_signals WHERE report_id=?", String.class, reportId))
            .isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT verified_report_count FROM reputation.target_reputation WHERE threat_target_id=?", Integer.class, targetId)).isZero();
    }

    @Test
    void canonicalMessageReportUsesOtherWeightsWithoutChangingPublicTargetTypes() {
        UUID targetId = UUID.randomUUID();
        ingestor.ingest(canonicalReportEvent("report.verified", UUID.randomUUID(), targetId, "MESSAGE_TEMPLATE", "VERIFIED", Instant.now()));
        assertThat(jdbc.queryForObject("SELECT target_type FROM reputation.target_reputation WHERE threat_target_id=?", String.class, targetId))
            .isEqualTo("OTHER");
        assertThat(jdbc.queryForObject("SELECT final_score FROM reputation.target_reputation WHERE threat_target_id=?", BigDecimal.class, targetId))
            .isEqualByComparingTo("17.50");
    }

    @Test
    void canonicalNonTerminalEventsNeverCreateVerifiedContributions() {
        for (String type : java.util.List.of("report.submitted", "report.need_more_info"))
            ingestor.ingest(canonicalReportEvent(type, UUID.randomUUID(), UUID.randomUUID(), "URL", "SUBMITTED", Instant.now()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.report_signals", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.inbox_events", Integer.class)).isZero();
    }

    @Test
    void canonicalInvalidStatusTypeOrVersionRollsBackInbox() {
        String event = canonicalReportEvent("report.verified", UUID.randomUUID(), UUID.randomUUID(), "URL", "VERIFIED", Instant.now());
        for (String invalid : java.util.List.of(event.replace("\"status\":\"VERIFIED\"", "\"status\":\"SUBMITTED\""),
                event.replace("\"targetType\":\"URL\"", "\"targetType\":\"PHONE\""), event.replace("\"version\":1", "\"version\":-1")))
            assertThatThrownBy(() -> ingestor.ingest(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation.inbox_events", Integer.class)).isZero();
    }

    private static String canonicalReportEvent(String eventType, UUID reportId, UUID targetId,
                                               String targetType, String status, Instant occurredAt) {
        return """
            {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s",
             "producer":"report-service","aggregateType":"Report","aggregateId":"%s",
             "correlationId":"%s","causationId":null,
             "payload":{"reportId":"%s","targetId":"%s","targetType":"%s","reporterUserId":"%s","status":"%s","version":1}}
            """.formatted(UUID.randomUUID(), eventType, occurredAt, reportId, UUID.randomUUID(),
            reportId, targetId, targetType, UUID.randomUUID(), status);
    }

    private static RequestPostProcessor admin() {
        return role("ROLE_ADMIN");
    }

    private static RequestPostProcessor role(String authority) {
        return jwt().authorities(new SimpleGrantedAuthority(authority));
    }

    private static String reportEvent(String eventType, UUID eventId, UUID reportId, UUID targetId,
                                      String severity, Instant occurredAt) {
        return """
            {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s",
             "producer":"report-service","aggregateType":"Report","aggregateId":"%s",
             "correlationId":"%s","causationId":null,
             "payload":{"reportId":"%s","threatTargetId":"%s","targetType":"URL","severity":%s}}
            """.formatted(eventId, eventType, occurredAt, reportId, UUID.randomUUID(),
            reportId, targetId, severity);
    }

    private static String threatEvent(UUID eventId, UUID targetId, String ruleSignal, Instant occurredAt) {
        return """
            {"eventId":"%s","eventType":"threat.updated","eventVersion":1,"occurredAt":"%s",
             "producer":"threat-service","aggregateType":"ThreatTarget","aggregateId":"%s",
             "correlationId":"%s","causationId":null,
             "payload":{"threatTargetId":"%s","targetType":"URL","ruleSignal":%s}}
            """.formatted(eventId, occurredAt, targetId, UUID.randomUUID(), targetId, ruleSignal);
    }

    private static String campaignEvent(UUID eventId, UUID targetId, String campaignSignal, Instant occurredAt) {
        UUID campaignId = UUID.randomUUID();
        return """
            {"eventId":"%s","eventType":"campaign.updated","eventVersion":1,"occurredAt":"%s",
             "producer":"campaign-service","aggregateType":"Campaign","aggregateId":"%s",
             "correlationId":"%s","causationId":null,
             "payload":{"campaignId":"%s","threatTargetId":"%s","targetType":"URL","campaignSignal":%s}}
            """.formatted(eventId, occurredAt, campaignId, UUID.randomUUID(), campaignId, targetId,
            campaignSignal);
    }
}
