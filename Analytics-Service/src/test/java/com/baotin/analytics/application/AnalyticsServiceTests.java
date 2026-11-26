package com.baotin.analytics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baotin.analytics.integration.ReportModerationClient;
import com.baotin.analytics.integration.ReportModerationView;
import com.baotin.analytics.integration.ThreatScanStats;
import com.baotin.analytics.integration.ThreatStatsClient;
import com.baotin.analytics.integration.UpstreamUnavailableException;
import com.baotin.analytics.store.AnalyticsStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AnalyticsServiceTests {

    @Mock
    private ReportModerationClient reportClient;

    @Mock
    private ThreatStatsClient threatClient;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(reportClient, threatClient);
    }

    @Test
    void summaryCombinesReportCountsAndThreatStats() {
        var counts = new ReportModerationView.Counts(
                Map.of("SUBMITTED", 2L, "PENDING_REVIEW", 3L, "NEED_MORE_INFO", 1L),
                Map.of("MEDIUM", 6L),
                4L);
        var view = new ReportModerationView(List.of(), 0, 1, 6L, 6L, counts);
        when(reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "token", "corr"))
                .thenReturn(view);
        when(threatClient.stats("token", "corr"))
                .thenReturn(new ThreatScanStats(42L, 7L));

        AdminSummary summary = service.summary("token", "corr");

        assertEquals(new AdminSummary(5L, 1L, 4L, 7L, 42L), summary);
        verify(reportClient).moderation(null, null, 0, 1, "submittedAt,desc", "token", "corr");
        verify(threatClient).stats("token", "corr");
    }

    @Test
    void queueForwardsQueryParametersAndMapsItemsWithNullRiskLevel() {
        UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
        Instant submittedAt = Instant.parse("2026-11-16T09:00:00Z");
        var upstreamItem = new ReportModerationView.Item(
                id,
                "URL",
                "https://example.test/",
                "PHISHING_URL",
                "Suspicious URL",
                "SUBMITTED",
                "HIGH",
                submittedAt,
                2,
                "Needs review");
        var counts = new ReportModerationView.Counts(Map.of("SUBMITTED", 1L), Map.of("HIGH", 1L), 0L);
        var view = new ReportModerationView(List.of(upstreamItem), 2, 50, 101L, 3L, counts);
        when(reportClient.moderation("SUBMITTED", "HIGH", 2, 50, "submittedAt,asc", "token", "corr"))
                .thenReturn(view);

        QueueQuery query = new QueueQuery(
                2,
                50,
                ModerationStatus.SUBMITTED,
                Priority.HIGH,
                QueueSort.parse("submittedAt,asc"));
        QueuePage page = service.queue(query, "token", "corr");

        assertEquals(2, page.page());
        assertEquals(50, page.size());
        assertEquals(101L, page.totalItems());
        assertEquals(3, page.totalPages());
        assertEquals(1, page.items().size());
        QueueItem mapped = page.items().getFirst();
        assertEquals(id, mapped.id());
        assertEquals("URL", mapped.targetType());
        assertEquals("https://example.test/", mapped.target());
        assertEquals("PHISHING_URL", mapped.category());
        assertEquals("Suspicious URL", mapped.title());
        assertEquals(ModerationStatus.SUBMITTED, mapped.status());
        assertNull(mapped.riskLevel());
        assertEquals(Priority.HIGH, mapped.priority());
        assertEquals(submittedAt, mapped.submittedAt());
        assertEquals(2, mapped.evidenceCount());
        assertEquals("Needs review", mapped.moderatorNote());

        verify(reportClient).moderation("SUBMITTED", "HIGH", 2, 50, "submittedAt,asc", "token", "corr");
        verifyNoInteractions(threatClient);
    }

    @Test
    void queueRejectsNonSubmittedAtSortWithoutCallingUpstream() {
        for (String sort : List.of("priority,desc", "riskLevel,desc", "status,asc")) {
            QueueQuery query = new QueueQuery(0, 20, null, null, QueueSort.parse(sort));
            assertThrows(IllegalArgumentException.class, () -> service.queue(query, "token", "corr"));
        }

        verifyNoInteractions(reportClient, threatClient);
    }

    @Test
    void countsFillsMissingStatusAndPriorityKeysWithZero() {
        var counts = new ReportModerationView.Counts(
                Map.of("SUBMITTED", 3L),
                Map.of("HIGH", 2L),
                0L);
        var view = new ReportModerationView(List.of(), 0, 1, 3L, 3L, counts);
        when(reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "token", "corr"))
                .thenReturn(view);

        QueueCounts result = service.counts("token", "corr");

        assertEquals(5, result.byStatus().size());
        assertEquals(3L, result.byStatus().get(ModerationStatus.SUBMITTED));
        assertEquals(0L, result.byStatus().get(ModerationStatus.PENDING_REVIEW));
        assertEquals(0L, result.byStatus().get(ModerationStatus.NEED_MORE_INFO));
        assertEquals(0L, result.byStatus().get(ModerationStatus.VERIFIED));
        assertEquals(0L, result.byStatus().get(ModerationStatus.REJECTED));

        assertEquals(3, result.byPriority().size());
        assertEquals(0L, result.byPriority().get(Priority.LOW));
        assertEquals(0L, result.byPriority().get(Priority.MEDIUM));
        assertEquals(2L, result.byPriority().get(Priority.HIGH));

        verify(reportClient).moderation(null, null, 0, 1, "submittedAt,desc", "token", "corr");
        verifyNoInteractions(threatClient);
    }

    @Test
    void upstreamUnavailablePropagatesWithoutFallbackZeros() {
        when(reportClient.moderation(any(), any(), anyInt(), anyInt(), any(), any(), any()))
                .thenThrow(new UpstreamUnavailableException("report"));

        UpstreamUnavailableException reportEx = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.summary("token", "corr"));
        assertEquals("report", reportEx.upstream());
        verifyNoInteractions(threatClient);

        UpstreamUnavailableException queueEx = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.queue(new QueueQuery(0, 20, null, null, QueueSort.parse("submittedAt,desc")), "token", "corr"));
        assertEquals("report", queueEx.upstream());

        UpstreamUnavailableException countsEx = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.counts("token", "corr"));
        assertEquals("report", countsEx.upstream());

        var counts = new ReportModerationView.Counts(Map.of(), Map.of(), 0L);
        doReturn(new ReportModerationView(List.of(), 0, 1, 0L, 0L, counts))
                .when(reportClient).moderation(null, null, 0, 1, "submittedAt,desc", "token", "corr");
        when(threatClient.stats("token", "corr"))
                .thenThrow(new UpstreamUnavailableException("threat"));

        UpstreamUnavailableException threatEx = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.summary("token", "corr"));
        assertEquals("threat", threatEx.upstream());
    }

    @Test
    void corruptUpstreamPayloadsMapToUpstreamUnavailableReport() {
        when(reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "token", "corr"))
                .thenReturn(new ReportModerationView(List.of(), 0, 1, 0L, 0L, null));
        UpstreamUnavailableException nullCountsSummary = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.summary("token", "corr"));
        assertEquals("report", nullCountsSummary.upstream());
        UpstreamUnavailableException nullCountsResult = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.counts("token", "corr"));
        assertEquals("report", nullCountsResult.upstream());

        var badEnumCounts = new ReportModerationView.Counts(Map.of("UNKNOWN_STATUS", 1L), Map.of(), 0L);
        when(reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "token", "corr"))
                .thenReturn(new ReportModerationView(List.of(), 0, 1, 1L, 1L, badEnumCounts));
        UpstreamUnavailableException badCountsEnum = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.counts("token", "corr"));
        assertEquals("report", badCountsEnum.upstream());

        var overflowView = new ReportModerationView(
                List.of(),
                0,
                20,
                100L,
                (long) Integer.MAX_VALUE + 1L,
                new ReportModerationView.Counts(Map.of(), Map.of(), 0L));
        when(reportClient.moderation(null, null, 0, 20, "submittedAt,desc", "token", "corr"))
                .thenReturn(overflowView);
        UpstreamUnavailableException overflowEx = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.queue(new QueueQuery(0, 20, null, null, QueueSort.parse("submittedAt,desc")), "token", "corr"));
        assertEquals("report", overflowEx.upstream());

        var badItem = new ReportModerationView.Item(
                UUID.randomUUID(),
                "URL",
                "https://example.test/",
                "PHISHING_URL",
                "Title",
                "NOT_A_STATUS",
                "MEDIUM",
                Instant.parse("2026-11-16T09:00:00Z"),
                1,
                null);
        when(reportClient.moderation(null, null, 0, 20, "submittedAt,desc", "token", "corr"))
                .thenReturn(new ReportModerationView(
                        List.of(badItem),
                        0,
                        20,
                        1L,
                        1L,
                        new ReportModerationView.Counts(Map.of(), Map.of(), 0L)));
        UpstreamUnavailableException badItemEx = assertThrows(
                UpstreamUnavailableException.class,
                () -> service.queue(new QueueQuery(0, 20, null, null, QueueSort.parse("submittedAt,desc")), "token", "corr"));
        assertEquals("report", badItemEx.upstream());
        verifyNoInteractions(threatClient);
    }

    @Test
    void queueEnrichesRiskLevelFromLookupAndLeavesUnmatchedAsNull() {
        UUID id1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
        Instant submittedAt = Instant.parse("2026-11-16T09:00:00Z");
        var item1 = new ReportModerationView.Item(
                id1, "URL", "https://bad.test/", "PHISHING_URL", "Bad URL", "SUBMITTED", "HIGH", submittedAt, 2, null);
        var item2 = new ReportModerationView.Item(
                id2, "URL", "https://new.test/", "PHISHING_URL", "New URL", "SUBMITTED", "MEDIUM", submittedAt, 1, null);
        var counts = new ReportModerationView.Counts(Map.of("SUBMITTED", 2L), Map.of("HIGH", 1L, "MEDIUM", 1L), 0L);
        var view = new ReportModerationView(List.of(item1, item2), 0, 20, 2L, 1L, counts);
        when(reportClient.moderation(null, null, 0, 20, "submittedAt,desc", "token", "corr"))
                .thenReturn(view);

        QueueRiskLookup riskLookup = mock(QueueRiskLookup.class);
        when(riskLookup.findRiskLevels(List.of(id1, id2)))
                .thenReturn(Map.of(id1, RiskLevel.CRITICAL));

        AnalyticsService enrichedService = new AnalyticsService(
                reportClient, threatClient, null, riskLookup, Clock.systemUTC());
        QueuePage page = enrichedService.queue(
                new QueueQuery(0, 20, null, null, QueueSort.parse("submittedAt,desc")),
                "token",
                "corr");

        assertEquals(2, page.items().size());
        assertEquals(RiskLevel.CRITICAL, page.items().get(0).riskLevel());
        assertNull(page.items().get(1).riskLevel());
        verify(riskLookup).findRiskLevels(List.of(id1, id2));
    }

    @Test
    void queueFailsOpenWithNullRiskLevelWhenLookupThrowsDataAccessException(CapturedOutput output) {
        UUID id1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
        Instant submittedAt = Instant.parse("2026-11-16T09:00:00Z");
        var item1 = new ReportModerationView.Item(
                id1, "URL", "https://bad.test/", "PHISHING_URL", "Bad URL", "SUBMITTED", "HIGH", submittedAt, 2, null);
        var counts = new ReportModerationView.Counts(Map.of("SUBMITTED", 1L), Map.of("HIGH", 1L), 0L);
        var view = new ReportModerationView(List.of(item1), 0, 20, 1L, 1L, counts);
        when(reportClient.moderation(null, null, 0, 20, "submittedAt,desc", "token", "corr"))
                .thenReturn(view);

        QueueRiskLookup failingLookup = reportIds -> {
            throw new DataAccessResourceFailureException("db down at secret-internal-host");
        };
        AnalyticsService enrichedService = new AnalyticsService(
                reportClient, threatClient, null, failingLookup, Clock.systemUTC());

        QueuePage page = enrichedService.queue(
                new QueueQuery(0, 20, null, null, QueueSort.parse("submittedAt,desc")),
                "token",
                "corr");

        assertEquals(1, page.items().size());
        assertNull(page.items().getFirst().riskLevel());
        assertFalse(output.toString().contains("secret-internal-host"));
    }

    @Test
    void queueSkipsRiskLookupWhenPageItemsAreEmpty() {
        var counts = new ReportModerationView.Counts(Map.of(), Map.of(), 0L);
        var view = new ReportModerationView(List.of(), 0, 20, 0L, 0L, counts);
        when(reportClient.moderation(null, null, 0, 20, "submittedAt,desc", "token", "corr"))
                .thenReturn(view);

        QueueRiskLookup riskLookup = mock(QueueRiskLookup.class);
        AnalyticsService enrichedService = new AnalyticsService(
                reportClient, threatClient, null, riskLookup, Clock.systemUTC());

        QueuePage page = enrichedService.queue(
                new QueueQuery(0, 20, null, null, QueueSort.parse("submittedAt,desc")),
                "token",
                "corr");

        assertEquals(0, page.items().size());
        verifyNoInteractions(riskLookup);
    }

    @Test
    void trendsDelegatesToStoreWithUtcTodayFromClockAndValidatesDaysBounds() {
        AnalyticsStore store = mock(AnalyticsStore.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-16T23:45:00Z"), ZoneOffset.UTC);
        AnalyticsService trendService = new AnalyticsService(
                reportClient, threatClient, store, store::findRiskLevels, fixedClock);

        for (int invalidDays : List.of(-5, 0, 91, 365)) {
            assertThrows(IllegalArgumentException.class, () -> trendService.trends(invalidDays));
        }
        verifyNoInteractions(store);

        AnalyticsTrends expected = new AnalyticsTrends(
                7,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 16),
                new TrendTotals(10L, 6L, 4L, 2L, 3L, 1L, 0L, 1L, 1L),
                Map.of(
                        RiskLevel.LOW, 5L,
                        RiskLevel.MEDIUM, 3L,
                        RiskLevel.HIGH, 1L,
                        RiskLevel.CRITICAL, 1L),
                List.of());
        when(store.trends(7, LocalDate.of(2026, 11, 16))).thenReturn(expected);

        AnalyticsTrends actual = trendService.trends(7);
        assertSame(expected, actual);
        verify(store).trends(7, LocalDate.of(2026, 11, 16));
    }
}
