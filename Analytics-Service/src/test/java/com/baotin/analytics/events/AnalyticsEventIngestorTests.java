package com.baotin.analytics.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsEventIngestorTests {

    private static final String SCAN_TOPIC = "baotin.scan.events.v1";
    private static final String REPORT_TOPIC = "baotin.report.events.v1";
    private static final String REPUTATION_TOPIC = "baotin.reputation.events.v1";

    @Autowired
    private AnalyticsEventIngestor ingestor;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM analytics.inbox_events");
        jdbc.update("DELETE FROM analytics.processed_scans");
        jdbc.update("DELETE FROM analytics.known_targets");
        jdbc.update("DELETE FROM analytics.report_lifecycle_state");
        jdbc.update("DELETE FROM analytics.target_reputation_view");
        jdbc.update("DELETE FROM analytics.daily_metrics");
    }

    @Test
    void duplicateEventIdWithSamePayloadIsNoOpAndCountsOnce() {
        UUID eventId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        String json = scanCompletedEvent(eventId, scanId, "TEXT", "HIGH", "2026-11-15T10:15:30Z");
        String reorderedWhitespaceJson = """
                {
                  "payload" : {
                    "riskLevel" : "HIGH",
                    "scanType" : "TEXT",
                    "scanId" : "%s",
                    "targetId" : null,
                    "authenticated" : false,
                    "finalScore" : 80.5,
                    "aiAvailable" : true,
                    "embeddedUrlCount" : 0,
                    "modelName" : null,
                    "modelVersion" : null,
                    "modelLabel" : null,
                    "modelLatencyMs" : null
                  },
                  "aggregateId" : "%s",
                  "occurredAt" : "2026-11-15T10:15:30Z",
                  "aggregateType" : "Scan",
                  "producer" : "threat-service",
                  "eventVersion" : 1,
                  "eventType" : "scan.completed",
                  "eventId" : "%s"
                }
                """.formatted(scanId, scanId, eventId);

        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC, json));
        assertEquals(IngestOutcome.DUPLICATE, ingestor.ingest(SCAN_TOPIC, json));
        assertEquals(IngestOutcome.DUPLICATE, ingestor.ingest(SCAN_TOPIC, reorderedWhitespaceJson));

        Map<String, Object> metrics = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(1L, ((Number) metrics.get("total_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("sms_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("high_risk_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("risk_high_scans")).longValue());
        assertEquals(1, countRows("analytics.inbox_events"));
    }

    @Test
    void duplicateEventIdWithDifferentPayloadLogsMetadataConflictAndDoesNotMutateMetrics(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        String first = scanCompletedEvent(eventId, scanId, "TEXT", "LOW", "2026-11-15T10:15:30Z");
        String conflicting = scanCompletedEvent(eventId, scanId, "TEXT", "CRITICAL", "2026-11-15T10:15:30Z");

        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC, first));
        assertEquals(IngestOutcome.CONFLICT_IGNORED, ingestor.ingest(SCAN_TOPIC, conflicting));

        Map<String, Object> metrics = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(1L, ((Number) metrics.get("total_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("risk_low_scans")).longValue());
        assertEquals(0L, ((Number) metrics.get("risk_critical_scans")).longValue());

        String logs = output.toString();
        assertTrue(logs.contains("WARN"));
        assertTrue(logs.contains("payload_hash_mismatch"));
        assertTrue(logs.contains(eventId.toString()));
        assertFalse(logs.contains("\"finalScore\""));
        assertFalse(logs.contains("\"CRITICAL\""));
    }

    @Test
    void duplicateLogicalScanIdUnderNewEventIdIsBlockedByProcessedScans() {
        UUID scanId = UUID.randomUUID();
        String first = scanCompletedEvent(UUID.randomUUID(), scanId, "URL", "MEDIUM", "2026-11-15T10:00:00Z");
        String second = scanCompletedEvent(UUID.randomUUID(), scanId, "URL", "MEDIUM", "2026-11-15T10:00:01Z");

        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC, first));
        assertEquals(IngestOutcome.DUPLICATE, ingestor.ingest(SCAN_TOPIC, second));

        Map<String, Object> metrics = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(1L, ((Number) metrics.get("total_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("url_scans")).longValue());
        assertEquals(1, countRows("analytics.processed_scans"));
        assertEquals(2, countRows("analytics.inbox_events"));
    }

    @Test
    void outOfOrderScansAcrossUtcDatesAttributeToExactUtcDatesAndRiskDistribution() {
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC,
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "CRITICAL", "2026-11-15T23:30:00Z")));
        // 2026-11-15T01:00:00+02:00 equals 2026-11-14T23:00:00Z
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC,
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T01:00:00+02:00")));
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC,
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "MEDIUM", "2026-11-14T05:00:00Z")));
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC,
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "HIGH", "2026-11-15T12:00:00Z")));

        Map<String, Object> nov14 = dailyRow(LocalDate.of(2026, 11, 14));
        assertEquals(2L, ((Number) nov14.get("total_scans")).longValue());
        assertEquals(2L, ((Number) nov14.get("sms_scans")).longValue());
        assertEquals(0L, ((Number) nov14.get("url_scans")).longValue());
        assertEquals(0L, ((Number) nov14.get("high_risk_scans")).longValue());
        assertEquals(1L, ((Number) nov14.get("risk_low_scans")).longValue());
        assertEquals(1L, ((Number) nov14.get("risk_medium_scans")).longValue());

        Map<String, Object> nov15 = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(2L, ((Number) nov15.get("total_scans")).longValue());
        assertEquals(0L, ((Number) nov15.get("sms_scans")).longValue());
        assertEquals(2L, ((Number) nov15.get("url_scans")).longValue());
        assertEquals(2L, ((Number) nov15.get("high_risk_scans")).longValue());
        assertEquals(1L, ((Number) nov15.get("risk_high_scans")).longValue());
        assertEquals(1L, ((Number) nov15.get("risk_critical_scans")).longValue());
    }

    @Test
    void outOfOrderTargetUpdatedBeforeTargetCreatedCreditsNewThreatsOnce() {
        UUID targetId = UUID.randomUUID();
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC,
                targetEvent(UUID.randomUUID(), "target.updated", targetId, "2026-11-15T12:00:00Z")));
        assertEquals(0, countRows("analytics.daily_metrics"));

        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(SCAN_TOPIC,
                targetEvent(UUID.randomUUID(), "target.created", targetId, "2026-11-15T10:00:00Z")));
        assertEquals(IngestOutcome.DUPLICATE, ingestor.ingest(SCAN_TOPIC,
                targetEvent(UUID.randomUUID(), "target.created", targetId, "2026-11-15T10:00:00Z")));

        Map<String, Object> nov15 = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(1L, ((Number) nov15.get("new_threats")).longValue());
        assertEquals(1, countRows("analytics.known_targets"));
    }

    @Test
    void reportSubmittedWithVersionZeroAcceptedCleanlyAndNeverLogsReporterPii(CapturedOutput output) {
        UUID reportId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID reporterUserId = UUID.randomUUID();
        String json = reportEvent(
                UUID.randomUUID(), "report.submitted", reportId, targetId, reporterUserId,
                "SUBMITTED", 0, "2026-11-15T08:00:00Z");

        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPORT_TOPIC, json));

        Map<String, Object> nov15 = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(1L, ((Number) nov15.get("reports_created")).longValue());

        Map<String, Object> state = jdbc.queryForMap(
                "SELECT * FROM analytics.report_lifecycle_state WHERE report_id = ?", reportId);
        assertEquals(0, ((Number) state.get("last_version")).intValue());
        assertEquals("SUBMITTED", state.get("last_status"));
        assertEquals(targetId, state.get("target_id"));
        assertEquals(Boolean.TRUE, state.get("created_recorded"));
        assertEquals(Date.valueOf(LocalDate.of(2026, 11, 15)), state.get("created_date"));
        assertFalse(output.toString().contains(reporterUserId.toString()));
    }

    @Test
    void outOfOrderReportVerifiedBeforeSubmittedCountsBothAndPreservesNewerStatus() {
        UUID reportId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        // First arrive: report.verified v1 with null targetId on day 2
        String verified = """
                {
                  "eventId": "%s",
                  "eventType": "report.verified",
                  "eventVersion": 1,
                  "occurredAt": "2026-11-16T03:00:00Z",
                  "producer": "report-service",
                  "aggregateType": "Report",
                  "aggregateId": "%s",
                  "payload": {
                    "reportId": "%s",
                    "targetId": null,
                    "targetType": "URL",
                    "reporterUserId": "%s",
                    "status": "VERIFIED",
                    "version": 1
                  }
                }
                """.formatted(UUID.randomUUID(), reportId, reportId, UUID.randomUUID());
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPORT_TOPIC, verified));

        // Second arrive: out-of-order report.submitted v0 on day 1 with targetId
        String submitted = reportEvent(
                UUID.randomUUID(), "report.submitted", reportId, targetId, UUID.randomUUID(),
                "SUBMITTED", 0, "2026-11-15T22:00:00Z");
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPORT_TOPIC, submitted));

        Map<String, Object> day1 = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(1L, ((Number) day1.get("reports_created")).longValue());
        assertEquals(0L, ((Number) day1.get("reports_verified")).longValue());

        Map<String, Object> day2 = dailyRow(LocalDate.of(2026, 11, 16));
        assertEquals(0L, ((Number) day2.get("reports_created")).longValue());
        assertEquals(1L, ((Number) day2.get("reports_verified")).longValue());

        Map<String, Object> state = jdbc.queryForMap(
                "SELECT * FROM analytics.report_lifecycle_state WHERE report_id = ?", reportId);
        assertEquals(1, ((Number) state.get("last_version")).intValue());
        assertEquals("VERIFIED", state.get("last_status"));
        assertEquals(targetId, state.get("target_id"));
        assertEquals(Boolean.TRUE, state.get("created_recorded"));
        assertEquals(Boolean.TRUE, state.get("verified_recorded"));
    }

    @Test
    void duplicateAndStaleReportTransitionsNeverOverCountOrRegressStatus() {
        UUID reportId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();

        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPORT_TOPIC,
                reportEvent(UUID.randomUUID(), "report.submitted", reportId, targetId, reporter,
                        "SUBMITTED", 0, "2026-11-15T01:00:00Z")));
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPORT_TOPIC,
                reportEvent(UUID.randomUUID(), "report.need_more_info", reportId, targetId, reporter,
                        "NEED_MORE_INFO", 1, "2026-11-15T02:00:00Z")));
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPORT_TOPIC,
                reportEvent(UUID.randomUUID(), "report.rejected", reportId, targetId, reporter,
                        "REJECTED", 2, "2026-11-15T03:00:00Z")));

        // Stale lower-version transition under a new eventId
        assertEquals(IngestOutcome.OUT_OF_ORDER_IGNORED, ingestor.ingest(REPORT_TOPIC,
                reportEvent(UUID.randomUUID(), "report.need_more_info", reportId, targetId, reporter,
                        "NEED_MORE_INFO", 1, "2026-11-15T02:00:00Z")));
        // Higher-version repeat of an already-recorded milestone never double-counts
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPORT_TOPIC,
                reportEvent(UUID.randomUUID(), "report.need_more_info", reportId, targetId, reporter,
                        "NEED_MORE_INFO", 3, "2026-11-15T04:00:00Z")));

        Map<String, Object> day = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(1L, ((Number) day.get("reports_created")).longValue());
        assertEquals(1L, ((Number) day.get("reports_need_more_info")).longValue());
        assertEquals(1L, ((Number) day.get("reports_rejected")).longValue());

        Map<String, Object> state = jdbc.queryForMap(
                "SELECT * FROM analytics.report_lifecycle_state WHERE report_id = ?", reportId);
        assertEquals(3, ((Number) state.get("last_version")).intValue());
        assertEquals("NEED_MORE_INFO", state.get("last_status"));
    }

    @Test
    void reputationScoreChangedUpdatesMonotonicallyByChangedAtWithoutMutatingDailyMetrics() {
        UUID targetId = UUID.randomUUID();
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPUTATION_TOPIC,
                reputationEvent(UUID.randomUUID(), targetId, "HIGH",
                        "2026-11-15T10:00:00Z", "2026-11-15T10:00:00Z")));
        assertEquals(IngestOutcome.OUT_OF_ORDER_IGNORED, ingestor.ingest(REPUTATION_TOPIC,
                reputationEvent(UUID.randomUUID(), targetId, "LOW",
                        "2026-11-15T11:00:00Z", "2026-11-15T09:00:00Z")));
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPUTATION_TOPIC,
                reputationEvent(UUID.randomUUID(), targetId, "CRITICAL",
                        "2026-11-15T12:00:00Z", "2026-11-15T12:00:00Z")));
        // Fallback to envelope occurredAt when changedAt is absent
        assertEquals(IngestOutcome.APPLIED, ingestor.ingest(REPUTATION_TOPIC,
                reputationEvent(UUID.randomUUID(), targetId, "MEDIUM",
                        "2026-11-15T13:00:00Z", null)));

        Map<String, Object> view = jdbc.queryForMap(
                "SELECT risk_level, last_event_at FROM analytics.target_reputation_view WHERE target_id = ?",
                targetId);
        assertEquals("MEDIUM", view.get("risk_level"));
        assertEquals(
                OffsetDateTime.of(2026, 11, 15, 13, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                ((OffsetDateTime) view.get("last_event_at")).toInstant());
        assertEquals(0, countRows("analytics.daily_metrics"));
    }

    @Test
    void unknownEventTypeOnSubscribedTopicIsRecordedAndIgnored() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "scan.recalculated",
                  "eventVersion": 1,
                  "occurredAt": "2026-11-15T10:00:00Z",
                  "aggregateId": "%s",
                  "payload": {"scanId": "%s"}
                }
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID());

        assertEquals(IngestOutcome.IGNORED_EVENT_TYPE, ingestor.ingest(SCAN_TOPIC, json));
        assertEquals(1, countRows("analytics.inbox_events"));
        assertEquals(0, countRows("analytics.daily_metrics"));
    }

    @Test
    void invalidPayloadsThrowWithoutLeakingPiiAndTopicResolverMapsEnvelopes(CapturedOutput output) {
        UUID secretUserId = UUID.randomUUID();
        String secretMessage = "secret-bank-otp-999999";

        assertThrows(AnalyticsInvalidEventException.class, () -> ingestor.ingest(SCAN_TOPIC, "{not-json"));
        assertThrows(AnalyticsInvalidEventException.class, () -> ingestor.ingest("unknown.topic",
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T10:00:00Z")));
        assertThrows(AnalyticsInvalidEventException.class, () -> ingestor.ingest(SCAN_TOPIC,
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "SMS", "LOW", "2026-11-15T10:00:00Z")));
        assertThrows(AnalyticsInvalidEventException.class, () -> ingestor.ingest(REPORT_TOPIC,
                reportEvent(UUID.randomUUID(), "report.submitted", UUID.randomUUID(), UUID.randomUUID(),
                        secretUserId, "SUBMITTED", -1, "2026-11-15T10:00:00Z")));

        String malformedWithPii = """
                {"eventId":"%s","eventType":"report.submitted","eventVersion":2,"occurredAt":"2026-11-15T10:00:00Z",
                 "aggregateId":"%s","reporterUserId":"%s","note":"%s","payload":{}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), secretUserId, secretMessage);
        AnalyticsInvalidEventException ex = assertThrows(
                AnalyticsInvalidEventException.class,
                () -> ingestor.ingest(REPORT_TOPIC, malformedWithPii));
        assertEquals(REPORT_TOPIC, ex.topic());
        assertFalse(ex.getMessage().contains(secretUserId.toString()));
        assertFalse(ex.getMessage().contains(secretMessage));
        assertFalse(output.toString().contains(secretUserId.toString()));
        assertFalse(output.toString().contains(secretMessage));

        assertEquals(SCAN_TOPIC, AnalyticsEventIngestor.resolveTopicForEnvelope(
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T10:00:00Z"),
                SCAN_TOPIC, REPORT_TOPIC, REPUTATION_TOPIC));
        assertEquals(SCAN_TOPIC, AnalyticsEventIngestor.resolveTopicForEnvelope(
                targetEvent(UUID.randomUUID(), "target.created", UUID.randomUUID(), "2026-11-15T10:00:00Z"),
                SCAN_TOPIC, REPORT_TOPIC, REPUTATION_TOPIC));
        assertEquals(REPORT_TOPIC, AnalyticsEventIngestor.resolveTopicForEnvelope(
                reportEvent(UUID.randomUUID(), "report.submitted", UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "SUBMITTED", 0, "2026-11-15T10:00:00Z"),
                SCAN_TOPIC, REPORT_TOPIC, REPUTATION_TOPIC));
        assertEquals(REPUTATION_TOPIC, AnalyticsEventIngestor.resolveTopicForEnvelope(
                reputationEvent(UUID.randomUUID(), UUID.randomUUID(), "LOW",
                        "2026-11-15T10:00:00Z", "2026-11-15T10:00:00Z"),
                SCAN_TOPIC, REPORT_TOPIC, REPUTATION_TOPIC));
        AnalyticsInvalidEventException unknownEx = assertThrows(AnalyticsInvalidEventException.class,
                () -> AnalyticsEventIngestor.resolveTopicForEnvelope(
                        "{\"eventType\":\"campaign.created\"}",
                        SCAN_TOPIC, REPORT_TOPIC, REPUTATION_TOPIC));
        assertNull(unknownEx.eventId());
    }

    private Map<String, Object> dailyRow(LocalDate date) {
        return jdbc.queryForMap("SELECT * FROM analytics.daily_metrics WHERE metric_date = ?", date);
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static String scanCompletedEvent(
            UUID eventId, UUID scanId, String scanType, String riskLevel, String occurredAt) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "scan.completed",
                  "eventVersion": 1,
                  "producer": "threat-service",
                  "aggregateType": "Scan",
                  "aggregateId": "%s",
                  "occurredAt": "%s",
                  "payload": {
                    "scanId": "%s",
                    "scanType": "%s",
                    "targetId": null,
                    "authenticated": false,
                    "finalScore": 80.5,
                    "riskLevel": "%s",
                    "aiAvailable": true,
                    "embeddedUrlCount": 0,
                    "modelName": null,
                    "modelVersion": null,
                    "modelLabel": null,
                    "modelLatencyMs": null
                  }
                }
                """.formatted(eventId, scanId, occurredAt, scanId, scanType, riskLevel);
    }

    private static String targetEvent(UUID eventId, String eventType, UUID targetId, String occurredAt) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "eventVersion": 1,
                  "producer": "threat-service",
                  "aggregateType": "Target",
                  "aggregateId": "%s",
                  "occurredAt": "%s",
                  "payload": {
                    "targetId": "%s",
                    "targetType": "URL",
                    "canonicalHash": "%s"
                  }
                }
                """.formatted(eventId, eventType, targetId, occurredAt, targetId, "a".repeat(64));
    }

    private static String reportEvent(
            UUID eventId,
            String eventType,
            UUID reportId,
            UUID targetId,
            UUID reporterUserId,
            String status,
            int version,
            String occurredAt) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "eventVersion": 1,
                  "occurredAt": "%s",
                  "producer": "report-service",
                  "aggregateType": "Report",
                  "aggregateId": "%s",
                  "payload": {
                    "reportId": "%s",
                    "targetId": "%s",
                    "targetType": "URL",
                    "reporterUserId": "%s",
                    "status": "%s",
                    "version": %d
                  }
                }
                """.formatted(eventId, eventType, occurredAt, reportId, reportId, targetId, reporterUserId, status, version);
    }

    private static String reputationEvent(
            UUID eventId,
            UUID threatTargetId,
            String newLevel,
            String occurredAt,
            String changedAt) {
        String changedAtJson = changedAt == null ? "" : "\"changedAt\": \"%s\",".formatted(changedAt);
        return """
                {
                  "eventId": "%s",
                  "eventType": "reputation.score.changed",
                  "eventVersion": 1,
                  "occurredAt": "%s",
                  "producer": "reputation-service",
                  "aggregateType": "ThreatTarget",
                  "aggregateId": "%s",
                  "traceId": "trace-secret-ignored",
                  "payload": {
                    "threatTargetId": "%s",
                    "targetType": "URL",
                    %s
                    "newLevel": "%s"
                  }
                }
                """.formatted(eventId, occurredAt, threatTargetId, threatTargetId, changedAtJson, newLevel);
    }
}
