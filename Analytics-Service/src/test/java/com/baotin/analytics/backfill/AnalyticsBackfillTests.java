package com.baotin.analytics.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baotin.analytics.events.AnalyticsEventIngestor;
import com.baotin.analytics.events.IngestOutcome;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsBackfillTests {

    private static final String SCAN_TOPIC = "baotin.scan.events.v1";
    private static final String REPORT_TOPIC = "baotin.report.events.v1";
    private static final String REPUTATION_TOPIC = "baotin.reputation.events.v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private BackfillService backfillService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @TempDir
    Path tempDir;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM analytics.backfill_partition_checkpoints");
        jdbc.update("DELETE FROM analytics.backfill_checkpoints");
        jdbc.update("DELETE FROM analytics.inbox_events");
        jdbc.update("DELETE FROM analytics.processed_scans");
        jdbc.update("DELETE FROM analytics.known_targets");
        jdbc.update("DELETE FROM analytics.report_lifecycle_state");
        jdbc.update("DELETE FROM analytics.target_reputation_view");
        jdbc.update("DELETE FROM analytics.daily_metrics");
    }

    @Test
    void fileBackfillRejectsMissingDirectorySymlinkAndInvalidBatchSizeBeforeCreatingCheckpoint() throws IOException {
        Path missing = tempDir.resolve("missing.ndjson");
        assertThrows(IllegalArgumentException.class, () -> backfillService.startFileRun(missing, 200));
        assertThrows(IllegalArgumentException.class, () -> backfillService.startFileRun(tempDir, 200));

        Path regular = tempDir.resolve("valid.ndjson");
        Files.writeString(regular, scanCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T10:00:00Z") + "\n",
                StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> backfillService.startFileRun(regular, 0));
        assertThrows(IllegalArgumentException.class, () -> backfillService.startFileRun(regular, 1001));

        Path symlink = tryCreateSymlink(tempDir.resolve("link.ndjson"), regular);
        if (symlink != null) {
            assertThrows(IllegalArgumentException.class, () -> backfillService.startFileRun(symlink, 200));
        }

        assertEquals(0, countRows("analytics.backfill_checkpoints"));
    }

    @Test
    void fileBackfillProcessesWrappedAndBareEventsAndSkipsBlankLines() throws IOException {
        UUID duplicateScanEventId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();

        String scanJson = scanCompletedEvent(
                duplicateScanEventId, scanId, "TEXT", "HIGH", "2026-11-15T09:00:00Z");
        String wrappedScan = wrappedLine(SCAN_TOPIC, scanJson);
        String bareTarget = targetCreatedEvent(UUID.randomUUID(), targetId, "2026-11-15T10:00:00Z");
        String bareReport = reportSubmittedEvent(
                UUID.randomUUID(), reportId, targetId, UUID.randomUUID(), "2026-11-15T11:00:00Z");
        String wrappedReputation = wrappedLine(REPUTATION_TOPIC,
                reputationEvent(UUID.randomUUID(), targetId, "CRITICAL", "2026-11-15T12:00:00Z"));
        String wrappedDuplicate = wrappedLine(SCAN_TOPIC, scanJson);

        Path ndjson = tempDir.resolve("mixed.ndjson");
        Files.writeString(ndjson, String.join("\n", List.of(
                "",
                wrappedScan,
                "   ",
                bareTarget,
                bareReport,
                wrappedReputation,
                wrappedDuplicate,
                ""
        )), StandardCharsets.UTF_8);

        BackfillRunSummary summary = backfillService.startFileRun(ndjson, 2);
        assertEquals("FILE_NDJSON", summary.sourceType());
        assertEquals("COMPLETED", summary.status());
        assertEquals(7L, summary.lastCursor());
        assertEquals(5L, summary.processedCount());
        assertEquals(4L, summary.appliedCount());
        assertEquals(1L, summary.skippedCount());
        assertEquals(2, summary.batchSize());
        assertEquals(summary, backfillService.status(summary.runId()));

        Map<String, Object> metrics = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(1L, ((Number) metrics.get("total_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("sms_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("high_risk_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("new_threats")).longValue());
        assertEquals(1L, ((Number) metrics.get("reports_created")).longValue());
        assertNotNull(jdbc.queryForObject(
                "SELECT completed_at FROM analytics.backfill_checkpoints WHERE run_id = ?",
                Timestamp.class,
                summary.runId()));
    }

    @Test
    void fileBackfillBatchFailureRollsBackFailingBatchAndResumeCompletesWithoutDoubleCounting(
            CapturedOutput output) throws IOException {
        String line1 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T01:00:00Z");
        String line2 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "HIGH", "2026-11-15T02:00:00Z");
        String line3 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "MEDIUM", "2026-11-15T03:00:00Z");
        String corruptLine4 = "{not-valid-json secret-otp-in-file";
        String line5 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "CRITICAL", "2026-11-15T05:00:00Z");

        Path ndjson = tempDir.resolve("crash-recovery.ndjson");
        Files.writeString(ndjson, String.join("\n", List.of(line1, line2, line3, corruptLine4, line5)) + "\n",
                StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, () -> backfillService.startFileRun(ndjson, 2));

        UUID runId = jdbc.queryForObject("SELECT run_id FROM analytics.backfill_checkpoints", UUID.class);
        assertNotNull(runId);

        BackfillRunSummary failed = backfillService.status(runId);
        assertEquals("FAILED", failed.status());
        assertEquals(2L, failed.lastCursor());
        assertEquals(2L, failed.processedCount());
        assertEquals(2L, failed.appliedCount());
        assertEquals(0L, failed.skippedCount());
        assertNull(jdbc.queryForObject(
                "SELECT completed_at FROM analytics.backfill_checkpoints WHERE run_id = ?",
                Timestamp.class,
                runId));

        Map<String, Object> midMetrics = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(2L, ((Number) midMetrics.get("total_scans")).longValue());
        assertFalse(output.toString().contains("secret-otp-in-file"));

        String fixedLine4 = scanCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T04:00:00Z");
        Files.writeString(ndjson, String.join("\n", List.of(line1, line2, line3, fixedLine4, line5)) + "\n",
                StandardCharsets.UTF_8);

        BackfillRunSummary completed = backfillService.resumeFileRun(runId, ndjson, null);
        assertEquals("COMPLETED", completed.status());
        assertEquals(5L, completed.lastCursor());
        assertEquals(5L, completed.processedCount());
        assertEquals(5L, completed.appliedCount());
        assertEquals(0L, completed.skippedCount());

        Map<String, Object> finalMetrics = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(5L, ((Number) finalMetrics.get("total_scans")).longValue());
        assertEquals(3L, ((Number) finalMetrics.get("sms_scans")).longValue());
        assertEquals(2L, ((Number) finalMetrics.get("url_scans")).longValue());

        Timestamp completedAt = jdbc.queryForObject(
                "SELECT completed_at FROM analytics.backfill_checkpoints WHERE run_id = ?",
                Timestamp.class,
                runId);
        assertNotNull(completedAt);

        assertThrows(IllegalStateException.class, () -> backfillService.resumeFileRun(runId, ndjson, null));
        assertEquals("COMPLETED", backfillService.status(runId).status());
        assertEquals(completedAt, jdbc.queryForObject(
                "SELECT completed_at FROM analytics.backfill_checkpoints WHERE run_id = ?",
                Timestamp.class,
                runId));
    }

    @Test
    void kafkaReplaySnapshotsEndOffsetsSkipsPoisonPillAndIgnoresRecordsPastEndOffset(CapturedOutput output) {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition scanP0 = new TopicPartition(SCAN_TOPIC, 0);
        TopicPartition reportP0 = new TopicPartition(REPORT_TOPIC, 0);
        consumer.updatePartitions(SCAN_TOPIC, List.of(new PartitionInfo(SCAN_TOPIC, 0, null, null, null)));
        consumer.updatePartitions(REPORT_TOPIC, List.of(new PartitionInfo(REPORT_TOPIC, 0, null, null, null)));
        consumer.updateBeginningOffsets(Map.of(scanP0, 0L, reportP0, 5L));
        consumer.updateEndOffsets(Map.of(scanP0, 3L, reportP0, 5L));

        String rec0 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T10:00:00Z");
        String poison1 = """
                {"eventId":"%s","eventType":"scan.completed","eventVersion":1,"occurredAt":"2026-11-15T10:01:00Z",
                 "aggregateId":"%s","reporterUserId":"pii-user-999","payload":{"scanType":"SMS","url":"secret-url-token"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        String rec2 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "CRITICAL", "2026-11-15T10:02:00Z");
        String livePastEnd3 = scanCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "TEXT", "HIGH", "2026-11-15T10:03:00Z");

        consumer.schedulePollTask(() -> {
            consumer.addRecord(new ConsumerRecord<>(SCAN_TOPIC, 0, 0L, "k0", rec0));
            consumer.addRecord(new ConsumerRecord<>(SCAN_TOPIC, 0, 1L, "k1", poison1));
            consumer.addRecord(new ConsumerRecord<>(SCAN_TOPIC, 0, 2L, "k2", rec2));
            consumer.addRecord(new ConsumerRecord<>(SCAN_TOPIC, 0, 3L, "k3", livePastEnd3));
        });

        double beforeRejected = totalRejectedCount();
        BackfillRunSummary summary = backfillService.startKafkaReplayRun(
                consumer, List.of(SCAN_TOPIC, REPORT_TOPIC), 2, Duration.ofMillis(50));

        assertEquals("KAFKA_REPLAY", summary.sourceType());
        assertEquals("COMPLETED", summary.status());
        assertEquals(3L, summary.processedCount());
        assertEquals(2L, summary.appliedCount());
        assertEquals(1L, summary.skippedCount());

        Map<String, Object> scanCheckpoint = jdbc.queryForMap("""
                SELECT next_offset, end_offset
                FROM analytics.backfill_partition_checkpoints
                WHERE run_id = ? AND topic = ? AND partition_id = 0
                """, summary.runId(), SCAN_TOPIC);
        assertEquals(3L, ((Number) scanCheckpoint.get("next_offset")).longValue());
        assertEquals(3L, ((Number) scanCheckpoint.get("end_offset")).longValue());

        Map<String, Object> reportCheckpoint = jdbc.queryForMap("""
                SELECT next_offset, end_offset
                FROM analytics.backfill_partition_checkpoints
                WHERE run_id = ? AND topic = ? AND partition_id = 0
                """, summary.runId(), REPORT_TOPIC);
        assertEquals(5L, ((Number) reportCheckpoint.get("next_offset")).longValue());
        assertEquals(5L, ((Number) reportCheckpoint.get("end_offset")).longValue());

        Map<String, Object> metrics = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(2L, ((Number) metrics.get("total_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("sms_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("url_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("high_risk_scans")).longValue());

        assertTrue(consumer.committed(java.util.Set.of(scanP0, reportP0), Duration.ofMillis(50)).isEmpty());
        assertEquals(1.0, totalRejectedCount() - beforeRejected);

        String logs = output.toString();
        assertTrue(logs.contains(SCAN_TOPIC));
        assertTrue(logs.contains("partition=0"));
        assertTrue(logs.contains("offset=1"));
        assertFalse(logs.contains("pii-user-999"));
        assertFalse(logs.contains("secret-url-token"));
    }

    @Test
    void kafkaReplayTransientDbFailurePreservesLastCommittedOffsetAndResumeCompletes() {
        TopicPartition scanP0 = new TopicPartition(SCAN_TOPIC, 0);
        List<String> events = List.of(
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T10:00:00Z"),
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "MEDIUM", "2026-11-15T10:01:00Z"),
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "HIGH", "2026-11-15T10:02:00Z"),
                scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "CRITICAL", "2026-11-15T10:03:00Z"));

        AnalyticsEventIngestor failOnThirdIngestor = new AnalyticsEventIngestor(jdbc) {
            private int calls = 0;

            @Override
            public IngestOutcome ingest(String topic, String rawJson) {
                calls++;
                if (calls == 3) {
                    throw new DataAccessResourceFailureException("transient postgres outage");
                }
                return super.ingest(topic, rawJson);
            }
        };
        BackfillService failingService = new BackfillService(
                jdbc, transactionManager, failOnThirdIngestor, SCAN_TOPIC, REPORT_TOPIC, REPUTATION_TOPIC, meterRegistry);

        MockConsumer<String, String> firstConsumer = scanReplayConsumer(events);
        assertThrows(DataAccessException.class, () -> failingService.startKafkaReplayRun(
                firstConsumer, List.of(SCAN_TOPIC), 2, Duration.ofMillis(50)));

        UUID runId = jdbc.queryForObject("SELECT run_id FROM analytics.backfill_checkpoints", UUID.class);
        assertNotNull(runId);

        BackfillRunSummary failed = backfillService.status(runId);
        assertEquals("FAILED", failed.status());
        assertEquals(2L, failed.processedCount());
        assertEquals(2L, failed.appliedCount());

        Long persistedNextOffset = jdbc.queryForObject("""
                SELECT next_offset
                FROM analytics.backfill_partition_checkpoints
                WHERE run_id = ? AND topic = ? AND partition_id = 0
                """, Long.class, runId, SCAN_TOPIC);
        assertEquals(2L, persistedNextOffset);
        assertEquals(2L, ((Number) dailyRow(LocalDate.of(2026, 11, 15)).get("total_scans")).longValue());

        MockConsumer<String, String> resumeConsumer = scanReplayConsumer(events);
        BackfillRunSummary resumed = backfillService.resumeKafkaReplayRun(
                runId, resumeConsumer, 2, Duration.ofMillis(50));
        assertEquals("COMPLETED", resumed.status());
        assertEquals(4L, resumed.processedCount());
        assertEquals(4L, resumed.appliedCount());
        assertEquals(0L, resumed.skippedCount());
        assertEquals(2L, resumeConsumer.position(scanP0) == 4L ? 2L : -1L);
        assertEquals(4L, jdbc.queryForObject("""
                SELECT next_offset
                FROM analytics.backfill_partition_checkpoints
                WHERE run_id = ? AND topic = ? AND partition_id = 0
                """, Long.class, runId, SCAN_TOPIC));
        assertEquals(4L, ((Number) dailyRow(LocalDate.of(2026, 11, 15)).get("total_scans")).longValue());
    }

    @Test
    void statusAndResumeRejectMissingWrongSourceTypeOrAlreadyRunningRun() {
        UUID missingId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> backfillService.status(missingId));

        UUID runningId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analytics.backfill_checkpoints
                    (run_id, source_type, source_ref, status, batch_size)
                VALUES (?, ?, ?, ?, ?)
                """, runningId, "KAFKA_REPLAY", SCAN_TOPIC, "RUNNING", 200);

        MockConsumer<String, String> dummyConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        assertThrows(IllegalStateException.class,
                () -> backfillService.resumeKafkaReplayRun(runningId, dummyConsumer, 200, Duration.ofMillis(50)));
        assertEquals("RUNNING", backfillService.status(runningId).status());

        Path dummyFile = tempDir.resolve("dummy.ndjson");
        assertThrows(IllegalStateException.class, () -> {
            Files.writeString(dummyFile, "", StandardCharsets.UTF_8);
            backfillService.resumeFileRun(runningId, dummyFile, null);
        });
    }

    @Test
    void cliValidatesOperatorCredentialsAndSanitizesFailuresWithoutLeakingSecrets() throws IOException {
        Path passwordFile = tempDir.resolve("analytics.pw");
        String secretPassword = "super-secret-db-password-value";
        Files.writeString(passwordFile, secretPassword + "\n", StandardCharsets.UTF_8);

        AnalyticsBackfill.OperatorConfig valid = AnalyticsBackfill.validateOperatorConfig(Map.of(
                "ANALYTICS_BACKFILL_URL", "jdbc:postgresql://localhost:5432/baotin",
                "ANALYTICS_BACKFILL_USER", "baotin_analytics",
                "ANALYTICS_BACKFILL_PASSWORD_FILE", passwordFile.toString()));
        assertEquals("baotin_analytics", valid.user());
        assertEquals(secretPassword, valid.password());
        assertFalse(valid.toString().contains(secretPassword));

        AnalyticsBackfill.OperatorConfig migrator = AnalyticsBackfill.validateOperatorConfig(Map.of(
                "ANALYTICS_BACKFILL_URL", "jdbc:postgresql://db:5432/baotin",
                "ANALYTICS_BACKFILL_USER", "baotin_analytics_migrator",
                "ANALYTICS_BACKFILL_PASSWORD_FILE", passwordFile.toString()));
        assertEquals("baotin_analytics_migrator", migrator.user());

        assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.validateOperatorConfig(Map.of(
                "ANALYTICS_BACKFILL_URL", "jdbc:h2:mem:analytics",
                "ANALYTICS_BACKFILL_USER", "baotin_analytics",
                "ANALYTICS_BACKFILL_PASSWORD_FILE", passwordFile.toString())));
        assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.validateOperatorConfig(Map.of(
                "ANALYTICS_BACKFILL_URL", "jdbc:postgresql://localhost:5432/baotin",
                "ANALYTICS_BACKFILL_USER", "postgres",
                "ANALYTICS_BACKFILL_PASSWORD_FILE", passwordFile.toString())));
        assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.validateOperatorConfig(Map.of(
                "ANALYTICS_BACKFILL_URL", "jdbc:postgresql://localhost:5432/baotin",
                "ANALYTICS_BACKFILL_USER", "baotin_analytics",
                "ANALYTICS_BACKFILL_PASSWORD_FILE", tempDir.toString())));

        Path oversizePassword = tempDir.resolve("oversize.pw");
        Files.writeString(oversizePassword, "x".repeat(4097), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.validateOperatorConfig(Map.of(
                "ANALYTICS_BACKFILL_URL", "jdbc:postgresql://localhost:5432/baotin",
                "ANALYTICS_BACKFILL_USER", "baotin_analytics",
                "ANALYTICS_BACKFILL_PASSWORD_FILE", oversizePassword.toString())));

        Path symlinkPassword = tryCreateSymlink(tempDir.resolve("link.pw"), passwordFile);
        if (symlinkPassword != null) {
            assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.validateOperatorConfig(Map.of(
                    "ANALYTICS_BACKFILL_URL", "jdbc:postgresql://localhost:5432/baotin",
                    "ANALYTICS_BACKFILL_USER", "baotin_analytics",
                    "ANALYTICS_BACKFILL_PASSWORD_FILE", symlinkPassword.toString())));
        }

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit = AnalyticsBackfill.runCli(
                new String[]{"status", UUID.randomUUID().toString()},
                Map.of(
                        "ANALYTICS_BACKFILL_URL", "jdbc:postgresql://localhost:5432/baotin",
                        "ANALYTICS_BACKFILL_USER", "unauthorized_role",
                        "ANALYTICS_BACKFILL_PASSWORD_FILE", passwordFile.toString()),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        assertEquals(1, exit);
        assertEquals("", outBytes.toString(StandardCharsets.UTF_8));
        String err = errBytes.toString(StandardCharsets.UTF_8).trim();
        assertEquals("ANALYTICS_BACKFILL FAIL input_role_state_or_database_check; no secret/payload diagnostics emitted", err);
        assertFalse(err.contains(secretPassword));
    }

    @Test
    void cliExecutesFileResumeFileAndStatusSubcommandsAgainstStandaloneHikariDataSource() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:cli_backfill_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(2);

        try (HikariDataSource ds = new HikariDataSource(config)) {
            Flyway.configure()
                    .dataSource(ds)
                    .schemas("analytics")
                    .defaultSchema("analytics")
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            Path ndjson = tempDir.resolve("cli-events.ndjson");
            String line1 = scanCompletedEvent(
                    UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-16T01:00:00Z");
            String line2 = scanCompletedEvent(
                    UUID.randomUUID(), UUID.randomUUID(), "URL", "HIGH", "2026-11-16T02:00:00Z");
            Files.writeString(ndjson, line1 + "\n" + line2 + "\n", StandardCharsets.UTF_8);

            BackfillRunSummary fileSummary = AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"file", ndjson.toString(), "1"}, Map.of());
            assertEquals("COMPLETED", fileSummary.status());
            assertEquals(2L, fileSummary.appliedCount());

            String jsonLine = JSON.writeValueAsString(fileSummary);
            JsonNode parsed = JSON.readTree(jsonLine);
            assertEquals(fileSummary.runId().toString(), parsed.path("runId").asString());
            assertEquals("COMPLETED", parsed.path("status").asString());
            assertFalse(jsonLine.contains("\n"));

            BackfillRunSummary statusSummary = AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"status", fileSummary.runId().toString()}, Map.of());
            assertEquals(fileSummary, statusSummary);

            JdbcTemplate standaloneJdbc = new JdbcTemplate(ds);
            UUID failedRunId = UUID.randomUUID();
            standaloneJdbc.update("""
                    INSERT INTO analytics.backfill_checkpoints
                        (run_id, source_type, source_ref, status, last_cursor, processed_count, applied_count, skipped_count, batch_size)
                    VALUES (?, 'FILE_NDJSON', ?, 'FAILED', 1, 1, 1, 0, 1)
                    """, failedRunId, ndjson.toString());

            BackfillRunSummary resumed = AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"resume-file", failedRunId.toString(), ndjson.toString()}, Map.of());
            assertEquals("COMPLETED", resumed.status());
            assertEquals(2L, resumed.lastCursor());

            assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"unknown-cmd"}, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"replay"}, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"replay", "   "}, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"replay", "localhost:9092", "1001"}, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"replay", "localhost:9092", "200", "a,,b"}, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"resume-replay", "not-a-uuid", "localhost:9092"}, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> AnalyticsBackfill.executeWithDataSource(
                    ds, new String[]{"status", "not-a-uuid"}, Map.of()));
        }
    }

    @Test
    void kafkaReplaySanitizesGroupIdAndSupportsMultiTopicPartitionsWithDuplicates() {
        Properties dirtyProps = new Properties();
        dirtyProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        dirtyProps.put(ConsumerConfig.GROUP_ID_CONFIG, "analytics-service-v1");
        dirtyProps.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "instance-1");
        dirtyProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        Properties sanitized = BackfillService.sanitizeConsumerProperties(dirtyProps);
        assertFalse(sanitized.containsKey(ConsumerConfig.GROUP_ID_CONFIG));
        assertFalse(sanitized.containsKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG));
        assertEquals("false", sanitized.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));

        Properties blankBootstrap = new Properties();
        blankBootstrap.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "  ");
        assertThrows(IllegalArgumentException.class, () -> BackfillService.sanitizeConsumerProperties(blankBootstrap));

        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition scanP0 = new TopicPartition(SCAN_TOPIC, 0);
        TopicPartition scanP1 = new TopicPartition(SCAN_TOPIC, 1);
        TopicPartition reportP0 = new TopicPartition(REPORT_TOPIC, 0);
        consumer.updatePartitions(SCAN_TOPIC, List.of(
                new PartitionInfo(SCAN_TOPIC, 0, null, null, null),
                new PartitionInfo(SCAN_TOPIC, 1, null, null, null)));
        consumer.updatePartitions(REPORT_TOPIC, List.of(
                new PartitionInfo(REPORT_TOPIC, 0, null, null, null)));
        consumer.updateBeginningOffsets(Map.of(scanP0, 0L, scanP1, 0L, reportP0, 0L));
        consumer.updateEndOffsets(Map.of(scanP0, 2L, scanP1, 1L, reportP0, 2L));

        UUID dupReportEventId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        String reportJson = reportSubmittedEvent(
                dupReportEventId, reportId, UUID.randomUUID(), UUID.randomUUID(), "2026-11-15T12:00:00Z");

        consumer.schedulePollTask(() -> {
            consumer.addRecord(new ConsumerRecord<>(SCAN_TOPIC, 0, 0L, "k1",
                    scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T09:00:00Z")));
            consumer.addRecord(new ConsumerRecord<>(SCAN_TOPIC, 0, 1L, "k2",
                    scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "HIGH", "2026-11-15T10:00:00Z")));
            consumer.addRecord(new ConsumerRecord<>(SCAN_TOPIC, 1, 0L, "k3",
                    targetCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "2026-11-15T11:00:00Z")));
            consumer.addRecord(new ConsumerRecord<>(REPORT_TOPIC, 0, 0L, "k4", reportJson));
            consumer.addRecord(new ConsumerRecord<>(REPORT_TOPIC, 0, 1L, "k5", reportJson));
        });

        double beforeRejected = totalRejectedCount();
        BackfillRunSummary summary = backfillService.startKafkaReplayRun(
                consumer, List.of(SCAN_TOPIC, REPORT_TOPIC), 2, Duration.ofMillis(50));
        assertEquals("COMPLETED", summary.status());
        assertEquals(5L, summary.processedCount());
        assertEquals(4L, summary.appliedCount());
        assertEquals(1L, summary.skippedCount());
        assertEquals(0.0, totalRejectedCount() - beforeRejected);

        Map<String, Object> metrics = dailyRow(LocalDate.of(2026, 11, 15));
        assertEquals(2L, ((Number) metrics.get("total_scans")).longValue());
        assertEquals(1L, ((Number) metrics.get("new_threats")).longValue());
        assertEquals(1L, ((Number) metrics.get("reports_created")).longValue());
    }

    @Test
    void resumeFileRunUpdatesBatchSizeOverrideAndRejectsWrongSourceType() throws IOException {
        Path ndjson = tempDir.resolve("override.ndjson");
        String line1 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "LOW", "2026-11-15T01:00:00Z");
        String line2 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "URL", "MEDIUM", "2026-11-15T02:00:00Z");
        String line3 = scanCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEXT", "HIGH", "2026-11-15T03:00:00Z");
        Files.writeString(ndjson, String.join("\n", List.of(line1, "{bad", line3)) + "\n", StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, () -> backfillService.startFileRun(ndjson, 1));
        UUID fileRunId = jdbc.queryForObject("SELECT run_id FROM analytics.backfill_checkpoints", UUID.class);
        assertNotNull(fileRunId);

        MockConsumer<String, String> dummyConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        assertThrows(IllegalStateException.class,
                () -> backfillService.resumeKafkaReplayRun(fileRunId, dummyConsumer, 10, Duration.ofMillis(50)));
        assertEquals("FAILED", backfillService.status(fileRunId).status());

        Files.writeString(ndjson, String.join("\n", List.of(line1, line2, line3)) + "\n", StandardCharsets.UTF_8);
        BackfillRunSummary resumed = backfillService.resumeFileRun(fileRunId, ndjson, 5);
        assertEquals("COMPLETED", resumed.status());
        assertEquals(5, resumed.batchSize());
        assertEquals(3L, resumed.processedCount());
        assertEquals(3L, resumed.appliedCount());
    }

    private MockConsumer<String, String> scanReplayConsumer(List<String> events) {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition(SCAN_TOPIC, 0);
        consumer.updatePartitions(SCAN_TOPIC, List.of(new PartitionInfo(SCAN_TOPIC, 0, null, null, null)));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.updateEndOffsets(Map.of(tp, (long) events.size()));
        consumer.schedulePollTask(() -> {
            for (int i = 0; i < events.size(); i++) {
                consumer.addRecord(new ConsumerRecord<>(SCAN_TOPIC, 0, i, "k" + i, events.get(i)));
            }
        });
        return consumer;
    }

    private double totalRejectedCount() {
        return meterRegistry.find("analytics.events.rejected")
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private Map<String, Object> dailyRow(LocalDate date) {
        return jdbc.queryForMap("SELECT * FROM analytics.daily_metrics WHERE metric_date = ?", date);
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static Path tryCreateSymlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException ignored) {
            return null;
        }
    }

    private static String wrappedLine(String topic, String eventJson) {
        return JSON.writeValueAsString(Map.of("topic", topic, "event", JSON.readTree(eventJson)));
    }

    private static String scanCompletedEvent(
            UUID eventId, UUID scanId, String scanType, String riskLevel, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"scan.completed","eventVersion":1,"producer":"threat-service","aggregateType":"Scan","aggregateId":"%s","occurredAt":"%s","payload":{"scanId":"%s","scanType":"%s","targetId":null,"authenticated":false,"finalScore":80.5,"riskLevel":"%s","aiAvailable":true,"embeddedUrlCount":0}}
                """.formatted(eventId, scanId, occurredAt, scanId, scanType, riskLevel).trim();
    }

    private static String targetCreatedEvent(UUID eventId, UUID targetId, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"target.created","eventVersion":1,"producer":"threat-service","aggregateType":"Target","aggregateId":"%s","occurredAt":"%s","payload":{"targetId":"%s","targetType":"URL","canonicalHash":"%s"}}
                """.formatted(eventId, targetId, occurredAt, targetId, "a".repeat(64)).trim();
    }

    private static String reportSubmittedEvent(
            UUID eventId, UUID reportId, UUID targetId, UUID reporterUserId, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"report.submitted","eventVersion":1,"producer":"report-service","aggregateType":"Report","aggregateId":"%s","occurredAt":"%s","payload":{"reportId":"%s","targetId":"%s","targetType":"URL","reporterUserId":"%s","status":"SUBMITTED","version":0}}
                """.formatted(eventId, reportId, occurredAt, reportId, targetId, reporterUserId).trim();
    }

    private static String reputationEvent(UUID eventId, UUID targetId, String newLevel, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"reputation.score.changed","eventVersion":1,"producer":"reputation-service","aggregateType":"ThreatTarget","aggregateId":"%s","occurredAt":"%s","payload":{"threatTargetId":"%s","targetType":"URL","changedAt":"%s","newLevel":"%s"}}
                """.formatted(eventId, targetId, occurredAt, targetId, occurredAt, newLevel).trim();
    }
}
