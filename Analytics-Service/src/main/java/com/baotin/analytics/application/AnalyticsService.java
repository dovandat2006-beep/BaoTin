package com.baotin.analytics.application;

import com.baotin.analytics.integration.ReportModerationClient;
import com.baotin.analytics.integration.ReportModerationView;
import com.baotin.analytics.integration.ThreatScanStats;
import com.baotin.analytics.integration.ThreatStatsClient;
import com.baotin.analytics.integration.UpstreamUnavailableException;
import com.baotin.analytics.store.AnalyticsStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final String REPORT_UPSTREAM = "report";
    private static final String THREAT_UPSTREAM = "threat";
    private static final int MIN_TREND_DAYS = 1;
    private static final int MAX_TREND_DAYS = 90;

    private final ReportModerationClient reportClient;
    private final ThreatStatsClient threatClient;
    private final AnalyticsStore store;
    private final QueueRiskLookup riskLookup;
    private final Clock clock;

    public AnalyticsService(ReportModerationClient reportClient, ThreatStatsClient threatClient) {
        this(reportClient, threatClient, null, ids -> Map.of(), Clock.systemUTC());
    }

    @Autowired
    public AnalyticsService(
            ReportModerationClient reportClient,
            ThreatStatsClient threatClient,
            AnalyticsStore store) {
        this(
                reportClient,
                threatClient,
                Objects.requireNonNull(store, "store must not be null"),
                store::findRiskLevels,
                Clock.systemUTC());
    }

    public AnalyticsService(
            ReportModerationClient reportClient,
            ThreatStatsClient threatClient,
            AnalyticsStore store,
            QueueRiskLookup riskLookup,
            Clock clock) {
        this.reportClient = Objects.requireNonNull(reportClient, "reportClient must not be null");
        this.threatClient = Objects.requireNonNull(threatClient, "threatClient must not be null");
        this.store = store;
        this.riskLookup = riskLookup != null
                ? riskLookup
                : (store != null ? store::findRiskLevels : ids -> Map.of());
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public AdminSummary summary(String accessToken, String correlationId) {
        ReportModerationView view = reportClient.moderation(
                null, null, 0, 1, "submittedAt,desc", accessToken, correlationId);
        if (view == null || view.counts() == null || view.counts().resolvedToday() < 0) {
            throw new UpstreamUnavailableException(REPORT_UPSTREAM);
        }
        ReportModerationView.Counts counts = view.counts();
        EnumMap<ModerationStatus, Long> byStatus = parseEnumCounts(counts.byStatus(), ModerationStatus.class);

        ThreatScanStats stats = threatClient.stats(accessToken, correlationId);
        if (stats == null || stats.scansToday() < 0 || stats.highRiskScansToday() < 0) {
            throw new UpstreamUnavailableException(THREAT_UPSTREAM);
        }

        long pendingReview = byStatus.get(ModerationStatus.SUBMITTED) + byStatus.get(ModerationStatus.PENDING_REVIEW);
        long needMoreInfo = byStatus.get(ModerationStatus.NEED_MORE_INFO);
        return new AdminSummary(
                pendingReview,
                needMoreInfo,
                counts.resolvedToday(),
                stats.highRiskScansToday(),
                stats.scansToday());
    }

    public QueuePage queue(QueueQuery query, String accessToken, String correlationId) {
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        if (query.sort().field() != QueueSort.Field.submittedAt) {
            throw new IllegalArgumentException("unsupported queue sort field: " + query.sort().field());
        }

        String status = query.status() == null ? null : query.status().name();
        String priority = query.priority() == null ? null : query.priority().name();
        String sort = query.sort().field().name() + "," + query.sort().direction().name();

        ReportModerationView view = reportClient.moderation(
                status, priority, query.page(), query.size(), sort, accessToken, correlationId);
        if (view == null
                || view.items() == null
                || view.page() < 0
                || view.size() < 1
                || view.totalItems() < 0
                || view.totalPages() < 0
                || view.totalPages() > Integer.MAX_VALUE) {
            throw new UpstreamUnavailableException(REPORT_UPSTREAM);
        }

        Map<UUID, RiskLevel> riskLevels = resolveRiskLevelsSafely(view.items());
        List<QueueItem> items = view.items().stream()
                .map(item -> mapItem(item, riskLevels))
                .toList();
        return new QueuePage(items, view.page(), view.size(), view.totalItems(), (int) view.totalPages());
    }

    public QueueCounts counts(String accessToken, String correlationId) {
        ReportModerationView view = reportClient.moderation(
                null, null, 0, 1, "submittedAt,desc", accessToken, correlationId);
        if (view == null || view.counts() == null) {
            throw new UpstreamUnavailableException(REPORT_UPSTREAM);
        }
        ReportModerationView.Counts counts = view.counts();
        return new QueueCounts(
                parseEnumCounts(counts.byStatus(), ModerationStatus.class),
                parseEnumCounts(counts.byPriority(), Priority.class));
    }

    public AnalyticsTrends trends(int days) {
        if (days < MIN_TREND_DAYS || days > MAX_TREND_DAYS) {
            throw new IllegalArgumentException(
                    "days must be between " + MIN_TREND_DAYS + " and " + MAX_TREND_DAYS);
        }
        if (store == null) {
            throw new IllegalStateException("AnalyticsStore is required for trends");
        }
        return store.trends(days, LocalDate.now(clock));
    }

    private Map<UUID, RiskLevel> resolveRiskLevelsSafely(List<ReportModerationView.Item> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        List<UUID> reportIds = items.stream()
                .filter(Objects::nonNull)
                .map(ReportModerationView.Item::id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (reportIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<UUID, RiskLevel> resolved = riskLookup.findRiskLevels(reportIds);
            return resolved == null ? Map.of() : resolved;
        } catch (RuntimeException exception) {
            log.warn("Fail-open queue riskLevel enrichment skipped: reason={}",
                    exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static QueueItem mapItem(ReportModerationView.Item item, Map<UUID, RiskLevel> riskLevels) {
        if (item == null) {
            throw new UpstreamUnavailableException(REPORT_UPSTREAM);
        }
        RiskLevel riskLevel = item.id() == null ? null : riskLevels.get(item.id());
        return new QueueItem(
                item.id(),
                item.targetType(),
                item.target(),
                item.category(),
                item.title(),
                parseEnum(ModerationStatus.class, item.status()),
                riskLevel,
                parseEnum(Priority.class, item.priority()),
                item.submittedAt(),
                item.evidenceCount(),
                item.moderatorNote());
    }

    private static <E extends Enum<E>> EnumMap<E, Long> parseEnumCounts(
            Map<String, Long> raw, Class<E> enumType) {
        if (raw == null) {
            throw new UpstreamUnavailableException(REPORT_UPSTREAM);
        }
        EnumMap<E, Long> counts = new EnumMap<>(enumType);
        for (E constant : enumType.getEnumConstants()) {
            counts.put(constant, 0L);
        }
        for (Map.Entry<String, Long> entry : raw.entrySet()) {
            E key = parseEnum(enumType, entry.getKey());
            Long value = entry.getValue();
            if (value == null || value < 0) {
                throw new UpstreamUnavailableException(REPORT_UPSTREAM);
            }
            counts.put(key, value);
        }
        return counts;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String value) {
        if (value == null) {
            throw new UpstreamUnavailableException(REPORT_UPSTREAM);
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new UpstreamUnavailableException(REPORT_UPSTREAM);
        }
    }
}
