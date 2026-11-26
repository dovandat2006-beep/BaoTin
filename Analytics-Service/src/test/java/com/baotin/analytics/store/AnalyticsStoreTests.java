package com.baotin.analytics.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.baotin.analytics.application.AdminSummary;
import com.baotin.analytics.application.AnalyticsTrends;
import com.baotin.analytics.application.DailyTrendPoint;
import com.baotin.analytics.application.ModerationStatus;
import com.baotin.analytics.application.Priority;
import com.baotin.analytics.application.QueueCounts;
import com.baotin.analytics.application.QueuePage;
import com.baotin.analytics.application.QueueQuery;
import com.baotin.analytics.application.QueueSort;
import com.baotin.analytics.application.RiskLevel;
import com.baotin.analytics.application.TrendTotals;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsStoreTests {

    private static final OffsetDateTime SUBMITTED = OffsetDateTime.of(2026, 10, 31, 9, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalyticsStore store;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM analytics.moderation_queue_item");
        jdbc.update("DELETE FROM analytics.report_lifecycle_state");
        jdbc.update("DELETE FROM analytics.target_reputation_view");
        jdbc.update("DELETE FROM analytics.daily_metrics");
    }

    @Test
    void emptyDatabaseReturnsZeroSummaryAndEmptyQueue() {
        LocalDate day = LocalDate.of(2026, 11, 2);

        assertEquals(new AdminSummary(0, 0, 0, 0, 0), store.summary(day));

        QueuePage page = store.queue(new QueueQuery(0, 20, null, null, QueueSort.parse("submittedAt,desc")));
        assertTrue(page.items().isEmpty());
        assertEquals(0, page.totalItems());
        assertEquals(0, page.totalPages());

        QueueCounts counts = store.counts();
        assertEquals(5, counts.byStatus().size());
        assertEquals(3, counts.byPriority().size());
        assertEquals(0L, counts.byStatus().get(ModerationStatus.SUBMITTED));
        assertEquals(0L, counts.byStatus().get(ModerationStatus.VERIFIED));
        assertEquals(0L, counts.byPriority().get(Priority.HIGH));
    }

    @Test
    void summaryCountsCurrentQueueHighRiskAndUtcResolvedToday() {
        item("11111111-1111-4111-8111-111111111111", "SUBMITTED", "HIGH", "HIGH", SUBMITTED, null);
        item("22222222-2222-4222-8222-222222222222", "PENDING_REVIEW", "CRITICAL", "HIGH", SUBMITTED, null);
        item("33333333-3333-4333-8333-333333333333", "NEED_MORE_INFO", "MEDIUM", "MEDIUM", SUBMITTED, null);
        item("44444444-4444-4444-8444-444444444444", "VERIFIED", "HIGH", "LOW", SUBMITTED,
                OffsetDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZoneOffset.UTC));
        item("55555555-5555-4555-8555-555555555555", "REJECTED", "LOW", "LOW", SUBMITTED,
                OffsetDateTime.of(2026, 11, 1, 23, 59, 59, 0, ZoneOffset.UTC));
        item("66666666-6666-4666-8666-666666666666", "VERIFIED", "CRITICAL", "HIGH", SUBMITTED,
                OffsetDateTime.of(2026, 11, 1, 20, 0, 0, 0, ZoneOffset.UTC));
        jdbc.update("INSERT INTO analytics.daily_metrics (metric_date, total_scans) VALUES (?, ?)",
                LocalDate.of(2026, 11, 2), 42L);

        assertEquals(new AdminSummary(2, 1, 1, 2, 42), store.summary(LocalDate.of(2026, 11, 2)));
    }

    @Test
    void queueSortsPriorityAndRiskSemanticallyAndPaginates() {
        item("11111111-1111-4111-8111-111111111111", "SUBMITTED", "LOW", "LOW", SUBMITTED, null);
        item("22222222-2222-4222-8222-222222222222", "SUBMITTED", "MEDIUM", "MEDIUM", SUBMITTED.plusMinutes(1), null);
        item("33333333-3333-4333-8333-333333333333", "SUBMITTED", "HIGH", "HIGH", SUBMITTED.plusMinutes(2), null);
        item("44444444-4444-4444-8444-444444444444", "SUBMITTED", "CRITICAL", "LOW", SUBMITTED.plusMinutes(3), null);

        QueuePage priorityDesc = store.queue(new QueueQuery(0, 10, null, null, QueueSort.parse("priority,desc")));
        assertEquals(List.of(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("44444444-4444-4444-8444-444444444444")),
                priorityDesc.items().stream().map(item -> item.id()).toList());

        QueuePage riskDesc = store.queue(new QueueQuery(0, 10, null, null, QueueSort.parse("riskLevel,desc")));
        assertEquals(List.of(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("11111111-1111-4111-8111-111111111111")),
                riskDesc.items().stream().map(item -> item.id()).toList());

        QueuePage secondPage = store.queue(new QueueQuery(1, 2, null, null, QueueSort.parse("submittedAt,asc")));
        assertEquals(2, secondPage.items().size());
        assertEquals(4, secondPage.totalItems());
        assertEquals(2, secondPage.totalPages());
        assertEquals(UUID.fromString("33333333-3333-4333-8333-333333333333"), secondPage.items().get(0).id());
        assertEquals(UUID.fromString("44444444-4444-4444-8444-444444444444"), secondPage.items().get(1).id());
    }

    @Test
    void queueSortsStatusInWorkflowOrderWithIdTieBreaker() {
        item("22222222-2222-4222-8222-222222222222", "SUBMITTED", "LOW", "LOW", SUBMITTED, null);
        item("11111111-1111-4111-8111-111111111111", "SUBMITTED", "LOW", "LOW", SUBMITTED, null);
        item("33333333-3333-4333-8333-333333333333", "PENDING_REVIEW", "LOW", "LOW", SUBMITTED, null);
        item("44444444-4444-4444-8444-444444444444", "NEED_MORE_INFO", "LOW", "LOW", SUBMITTED, null);
        item("55555555-5555-4555-8555-555555555555", "VERIFIED", "LOW", "LOW", SUBMITTED, null);
        item("66666666-6666-4666-8666-666666666666", "REJECTED", "LOW", "LOW", SUBMITTED, null);

        QueuePage asc = store.queue(new QueueQuery(0, 10, null, null, QueueSort.parse("status,asc")));
        assertEquals(List.of(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                UUID.fromString("66666666-6666-4666-8666-666666666666")),
                asc.items().stream().map(item -> item.id()).toList());

        QueuePage desc = store.queue(new QueueQuery(0, 10, null, null, QueueSort.parse("status,desc")));
        assertEquals(List.of(
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222")),
                desc.items().stream().map(item -> item.id()).toList());
    }

    @Test
    void queueFiltersByStatusAndPriorityAndCountsAllGroups() {
        item("11111111-1111-4111-8111-111111111111", "SUBMITTED", "HIGH", "HIGH", SUBMITTED, null);

        QueuePage byStatus = store.queue(new QueueQuery(0, 20, ModerationStatus.SUBMITTED, null,
                QueueSort.parse("submittedAt,desc")));
        assertEquals(1, byStatus.totalItems());
        assertEquals("SUBMITTED", byStatus.items().get(0).status().name());

        QueuePage byPriority = store.queue(new QueueQuery(0, 20, null, Priority.HIGH,
                QueueSort.parse("submittedAt,desc")));
        assertEquals(1, byPriority.totalItems());

        QueuePage otherStatus = store.queue(new QueueQuery(0, 20, ModerationStatus.REJECTED, null,
                QueueSort.parse("submittedAt,desc")));
        assertEquals(0, otherStatus.totalItems());

        QueueCounts counts = store.counts();
        assertEquals(1L, counts.byStatus().get(ModerationStatus.SUBMITTED));
        assertEquals(0L, counts.byStatus().get(ModerationStatus.PENDING_REVIEW));
        assertEquals(0L, counts.byStatus().get(ModerationStatus.NEED_MORE_INFO));
        assertEquals(0L, counts.byStatus().get(ModerationStatus.VERIFIED));
        assertEquals(0L, counts.byStatus().get(ModerationStatus.REJECTED));
        assertEquals(0L, counts.byPriority().get(Priority.LOW));
        assertEquals(0L, counts.byPriority().get(Priority.MEDIUM));
        assertEquals(1L, counts.byPriority().get(Priority.HIGH));
    }

    @Test
    void trendsZeroFillsMissingDatesAndComputesTotalsAndRiskBreakdownWithinWindow() {
        LocalDate today = LocalDate.of(2026, 11, 16);
        // Outside window (before fromDate 2026-11-14 for 3-day window, and after today)
        insertDailyMetrics(LocalDate.of(2026, 11, 13), 100, 60, 40, 50, 20, 30, 30, 20, 10, 8, 1, 1, 5);
        insertDailyMetrics(LocalDate.of(2026, 11, 17), 90, 50, 40, 30, 40, 20, 20, 10, 9, 7, 1, 1, 4);

        // Inside 3-day window [2026-11-14 .. 2026-11-16], with 2026-11-15 missing (zero-filled)
        insertDailyMetrics(LocalDate.of(2026, 11, 14), 10, 6, 4, 3, 4, 3, 2, 1, 5, 2, 1, 2, 3);
        insertDailyMetrics(LocalDate.of(2026, 11, 16), 20, 12, 8, 7, 8, 5, 4, 3, 4, 3, 0, 1, 2);

        AnalyticsTrends trends = store.trends(3, today);

        assertEquals(3, trends.days());
        assertEquals(LocalDate.of(2026, 11, 14), trends.fromDate());
        assertEquals(LocalDate.of(2026, 11, 16), trends.toDate());
        assertEquals(
                new TrendTotals(30L, 18L, 12L, 10L, 9L, 5L, 1L, 3L, 5L),
                trends.totals());
        assertEquals(
                Map.of(
                        RiskLevel.LOW, 12L,
                        RiskLevel.MEDIUM, 8L,
                        RiskLevel.HIGH, 6L,
                        RiskLevel.CRITICAL, 4L),
                trends.riskBreakdown());
        assertEquals(
                List.of(
                        new DailyTrendPoint(LocalDate.of(2026, 11, 14), 10L, 6L, 4L, 3L, 5L, 2L, 1L, 2L, 3L),
                        new DailyTrendPoint(LocalDate.of(2026, 11, 15), 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L),
                        new DailyTrendPoint(LocalDate.of(2026, 11, 16), 20L, 12L, 8L, 7L, 4L, 3L, 0L, 1L, 2L)),
                trends.series());
    }

    @Test
    void trendsRejectsOutOfRangeDays() {
        LocalDate today = LocalDate.of(2026, 11, 16);
        assertThrows(IllegalArgumentException.class, () -> store.trends(0, today));
        assertThrows(IllegalArgumentException.class, () -> store.trends(-1, today));
        assertThrows(IllegalArgumentException.class, () -> store.trends(91, today));
        assertThrows(NullPointerException.class, () -> store.trends(14, null));
    }

    @Test
    void findRiskLevelsJoinsReportLifecycleAndTargetReputationAndFailsOpenOnError(CapturedOutput output) {
        assertEquals(Map.of(), store.findRiskLevels(null));
        assertEquals(Map.of(), store.findRiskLevels(List.of()));

        JdbcTemplate untouchedJdbc = mock(JdbcTemplate.class);
        AnalyticsStore isolatedStore = new AnalyticsStore(untouchedJdbc);
        assertEquals(Map.of(), isolatedStore.findRiskLevels(List.of()));
        verifyNoInteractions(untouchedJdbc);

        UUID reportWithCritical = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID reportWithLow = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID reportWithoutReputation = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID reportWithoutTarget = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID unknownReport = UUID.fromString("55555555-5555-4555-8555-555555555555");

        UUID targetCritical = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID targetLow = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        UUID targetUnscored = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

        jdbc.update("""
                INSERT INTO analytics.target_reputation_view (target_id, risk_level, last_event_at)
                VALUES (?, ?, ?), (?, ?, ?)
                """,
                targetCritical, "CRITICAL", SUBMITTED,
                targetLow, "LOW", SUBMITTED);

        jdbc.update("""
                INSERT INTO analytics.report_lifecycle_state
                    (report_id, target_id, last_version, last_status, last_event_at)
                VALUES (?, ?, 1, 'SUBMITTED', ?),
                       (?, ?, 1, 'VERIFIED', ?),
                       (?, ?, 1, 'SUBMITTED', ?),
                       (?, NULL, 0, 'SUBMITTED', ?)
                """,
                reportWithCritical, targetCritical, SUBMITTED,
                reportWithLow, targetLow, SUBMITTED,
                reportWithoutReputation, targetUnscored, SUBMITTED,
                reportWithoutTarget, SUBMITTED);

        Map<UUID, RiskLevel> resolved = store.findRiskLevels(List.of(
                reportWithCritical,
                reportWithLow,
                reportWithoutReputation,
                reportWithoutTarget,
                unknownReport));

        assertEquals(
                Map.of(
                        reportWithCritical, RiskLevel.CRITICAL,
                        reportWithLow, RiskLevel.LOW),
                resolved);

        JdbcTemplate failingJdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("connection refused at internal-db-secret"))
                .when(failingJdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        AnalyticsStore failingStore = new AnalyticsStore(failingJdbc);

        assertEquals(Map.of(), failingStore.findRiskLevels(List.of(reportWithCritical)));
        assertFalse(output.toString().contains("internal-db-secret"));
    }

    private void insertDailyMetrics(
            LocalDate date,
            long totalScans,
            long textScans,
            long urlScans,
            long highRiskScans,
            long riskLow,
            long riskMedium,
            long riskHigh,
            long riskCritical,
            long reportsCreated,
            long reportsVerified,
            long reportsRejected,
            long reportsNeedMoreInfo,
            long newThreats) {
        jdbc.update("""
                INSERT INTO analytics.daily_metrics (
                    metric_date, total_scans, sms_scans, url_scans, high_risk_scans,
                    risk_low_scans, risk_medium_scans, risk_high_scans, risk_critical_scans,
                    reports_created, reports_verified, reports_rejected, reports_need_more_info, new_threats
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                date, totalScans, textScans, urlScans, highRiskScans,
                riskLow, riskMedium, riskHigh, riskCritical,
                reportsCreated, reportsVerified, reportsRejected, reportsNeedMoreInfo, newThreats);
    }

    private void item(
            String id,
            String status,
            String riskLevel,
            String priority,
            OffsetDateTime submittedAt,
            OffsetDateTime resolvedAt) {
        jdbc.update("""
                INSERT INTO analytics.moderation_queue_item
                    (id, target_type, target, category, title, status, risk_level, priority,
                     submitted_at, evidence_count, moderator_note, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.fromString(id),
                "URL",
                "https://example.com",
                "PHISHING",
                "Title",
                status,
                riskLevel,
                priority,
                submittedAt,
                0,
                null,
                resolvedAt);
    }
}
