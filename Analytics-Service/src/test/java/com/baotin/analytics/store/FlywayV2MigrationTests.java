package com.baotin.analytics.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class FlywayV2MigrationTests {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 16, 3, 45, 0, 0, ZoneOffset.UTC);

    @Test
    void migratesV1ToV2PreservingExistingDailyMetricsAndAligningColumns() {
        try (HikariDataSource dataSource = freshDataSource("flyway_v2_daily_metrics")) {
            flyway(dataSource).target("1").load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            LocalDate metricDate = LocalDate.of(2026, 11, 1);
            jdbc.update("""
                    INSERT INTO analytics.daily_metrics
                        (metric_date, total_scans, sms_scans, url_scans, high_risk_scans,
                         reports_created, reports_verified, reports_rejected, new_threats, active_users)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, metricDate, 100L, 65L, 35L, 12L, 8L, 5L, 2L, 4L, 99L);

            Flyway v2 = flyway(dataSource).load();
            v2.migrate();
            assertEquals("2", v2.info().current().getVersion().getVersion());

            Set<String> columns = columnNames(jdbc, "daily_metrics");
            assertTrue(columns.containsAll(Set.of(
                    "metric_date",
                    "total_scans",
                    "sms_scans",
                    "url_scans",
                    "high_risk_scans",
                    "reports_created",
                    "reports_verified",
                    "reports_rejected",
                    "reports_need_more_info",
                    "risk_low_scans",
                    "risk_medium_scans",
                    "risk_high_scans",
                    "risk_critical_scans",
                    "new_threats",
                    "updated_at")));
            assertFalse(columns.contains("text_scans"));
            assertTrue(columns.contains("active_users"));

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM analytics.daily_metrics WHERE metric_date = ?",
                    metricDate);
            assertEquals(100L, ((Number) row.get("total_scans")).longValue());
            assertEquals(65L, ((Number) row.get("sms_scans")).longValue());
            assertEquals(99L, ((Number) row.get("active_users")).longValue());
            assertEquals(35L, ((Number) row.get("url_scans")).longValue());
            assertEquals(12L, ((Number) row.get("high_risk_scans")).longValue());
            assertEquals(8L, ((Number) row.get("reports_created")).longValue());
            assertEquals(5L, ((Number) row.get("reports_verified")).longValue());
            assertEquals(2L, ((Number) row.get("reports_rejected")).longValue());
            assertEquals(0L, ((Number) row.get("reports_need_more_info")).longValue());
            assertEquals(0L, ((Number) row.get("risk_low_scans")).longValue());
            assertEquals(0L, ((Number) row.get("risk_medium_scans")).longValue());
            assertEquals(0L, ((Number) row.get("risk_high_scans")).longValue());
            assertEquals(0L, ((Number) row.get("risk_critical_scans")).longValue());
            assertEquals(4L, ((Number) row.get("new_threats")).longValue());
            assertNotNull(row.get("updated_at"));
        }
    }

    @Test
    void createsAllPhase2TablesWithExpectedDefaultsAndConstraints() {
        try (HikariDataSource dataSource = freshDataSource("flyway_v2_tables")) {
            Flyway v2 = flyway(dataSource).load();
            v2.migrate();
            assertEquals("2", v2.info().current().getVersion().getVersion());

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            LocalDate metricDate = LocalDate.of(2026, 11, 16);

            UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
            UUID aggregateId = UUID.fromString("22222222-2222-4222-8222-222222222222");
            jdbc.update("""
                    INSERT INTO analytics.inbox_events
                        (event_id, topic, event_type, aggregate_id, occurred_at, payload_hash)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, eventId, "baotin.scan.events.v1", "scan.completed", aggregateId, NOW, "a".repeat(64));
            assertNotNull(jdbc.queryForObject(
                    "SELECT processed_at FROM analytics.inbox_events WHERE event_id = ?",
                    Timestamp.class,
                    eventId));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO analytics.inbox_events
                        (event_id, topic, event_type, aggregate_id, occurred_at, payload_hash)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, eventId, "baotin.scan.events.v1", "scan.completed", aggregateId, NOW, "b".repeat(64)));

            UUID scanId = UUID.fromString("33333333-3333-4333-8333-333333333333");
            jdbc.update("INSERT INTO analytics.processed_scans (scan_id, metric_date) VALUES (?, ?)", scanId, metricDate);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO analytics.processed_scans (scan_id, metric_date) VALUES (?, ?)", scanId, metricDate));

            UUID targetId = UUID.fromString("44444444-4444-4444-8444-444444444444");
            jdbc.update("INSERT INTO analytics.known_targets (target_id, created_date, created_at) VALUES (?, ?, ?)",
                    targetId, metricDate, NOW);

            UUID reportId = UUID.fromString("55555555-5555-4555-8555-555555555555");
            jdbc.update("""
                    INSERT INTO analytics.report_lifecycle_state
                        (report_id, target_id, last_version, last_status, last_event_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, reportId, targetId, 0, "SUBMITTED", NOW);
            Map<String, Object> lifecycle = jdbc.queryForMap(
                    "SELECT * FROM analytics.report_lifecycle_state WHERE report_id = ?", reportId);
            assertEquals(Boolean.FALSE, lifecycle.get("created_recorded"));
            assertEquals(Boolean.FALSE, lifecycle.get("verified_recorded"));
            assertEquals(Boolean.FALSE, lifecycle.get("rejected_recorded"));
            assertEquals(Boolean.FALSE, lifecycle.get("need_more_info_recorded"));
            assertNotNull(lifecycle.get("updated_at"));

            jdbc.update("""
                    INSERT INTO analytics.target_reputation_view (target_id, risk_level, last_event_at)
                    VALUES (?, ?, ?)
                    """, targetId, "HIGH", NOW);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO analytics.target_reputation_view (target_id, risk_level, last_event_at)
                    VALUES (?, ?, ?)
                    """, UUID.randomUUID(), "INVALID", NOW));

            UUID runId = UUID.fromString("66666666-6666-4666-8666-666666666666");
            jdbc.update("""
                    INSERT INTO analytics.backfill_checkpoints (run_id, source_type, source_ref, status, batch_size)
                    VALUES (?, ?, ?, ?, ?)
                    """, runId, "FILE_NDJSON", "events.ndjson", "RUNNING", 200);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO analytics.backfill_checkpoints (run_id, source_type, source_ref, status, batch_size)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), "OTHER", "events.ndjson", "RUNNING", 200));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO analytics.backfill_checkpoints (run_id, source_type, source_ref, status, batch_size)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), "FILE_NDJSON", "events.ndjson", "RUNNING", 0));

            jdbc.update("""
                    INSERT INTO analytics.backfill_partition_checkpoints
                        (run_id, topic, partition_id, next_offset, end_offset)
                    VALUES (?, ?, ?, ?, ?)
                    """, runId, "baotin.scan.events.v1", 0, 10L, 25L);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO analytics.backfill_partition_checkpoints
                        (run_id, topic, partition_id, next_offset, end_offset)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), "baotin.scan.events.v1", 0, 0L, 1L));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO analytics.backfill_partition_checkpoints
                        (run_id, topic, partition_id, next_offset, end_offset)
                    VALUES (?, ?, ?, ?, ?)
                    """, runId, "baotin.scan.events.v1", -1, 0L, 1L));
        }
    }

    private static HikariDataSource freshDataSource(String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("analytics")
                .defaultSchema("analytics")
                .createSchemas(true)
                .locations("classpath:db/migration");
    }

    private static Set<String> columnNames(JdbcTemplate jdbc, String tableName) {
        return new HashSet<>(jdbc.query("""
                SELECT LOWER(column_name)
                FROM information_schema.columns
                WHERE LOWER(table_schema) = 'analytics'
                  AND LOWER(table_name) = ?
                """, (rs, rowNum) -> rs.getString(1).toLowerCase(Locale.ROOT), tableName));
    }
}
