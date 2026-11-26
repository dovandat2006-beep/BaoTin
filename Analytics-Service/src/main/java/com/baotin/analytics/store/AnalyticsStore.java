package com.baotin.analytics.store;

import com.baotin.analytics.application.AdminSummary;
import com.baotin.analytics.application.AnalyticsTrends;
import com.baotin.analytics.application.DailyTrendPoint;
import com.baotin.analytics.application.ModerationStatus;
import com.baotin.analytics.application.Priority;
import com.baotin.analytics.application.QueueCounts;
import com.baotin.analytics.application.QueueItem;
import com.baotin.analytics.application.QueuePage;
import com.baotin.analytics.application.QueueQuery;
import com.baotin.analytics.application.QueueRiskLookup;
import com.baotin.analytics.application.RiskLevel;
import com.baotin.analytics.application.TrendTotals;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Read model for the Analytics admin API; every query is scoped to schema analytics. */
@Repository
public class AnalyticsStore implements QueueRiskLookup {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsStore.class);
    private static final int MIN_TREND_DAYS = 1;
    private static final int MAX_TREND_DAYS = 90;

    private final JdbcTemplate jdbc;

    public AnalyticsStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    public AdminSummary summary(LocalDate today) {
        OffsetDateTime start = today.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = today.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        AdminSummary queue = jdbc.queryForObject("""
                SELECT
                    COALESCE(SUM(CASE WHEN status IN ('SUBMITTED', 'PENDING_REVIEW') THEN 1 ELSE 0 END), 0) AS pending_review,
                    COALESCE(SUM(CASE WHEN status = 'NEED_MORE_INFO' THEN 1 ELSE 0 END), 0) AS need_more_info,
                    COALESCE(SUM(CASE WHEN status IN ('VERIFIED', 'REJECTED')
                                      AND resolved_at >= ? AND resolved_at < ? THEN 1 ELSE 0 END), 0) AS resolved_today,
                    COALESCE(SUM(CASE WHEN risk_level IN ('HIGH', 'CRITICAL')
                                      AND status IN ('SUBMITTED', 'PENDING_REVIEW', 'NEED_MORE_INFO') THEN 1 ELSE 0 END), 0) AS high_risk
                FROM analytics.moderation_queue_item
                """,
                (rs, rowNum) -> new AdminSummary(
                        rs.getLong("pending_review"),
                        rs.getLong("need_more_info"),
                        rs.getLong("resolved_today"),
                        rs.getLong("high_risk"),
                        0),
                start,
                end);

        long scansToday = jdbc.query("""
                        SELECT total_scans
                        FROM analytics.daily_metrics
                        WHERE metric_date = ?
                        """,
                (rs, rowNum) -> rs.getLong(1),
                today).stream().findFirst().orElse(0L);

        return new AdminSummary(
                queue.pendingReview(),
                queue.needMoreInfo(),
                queue.resolvedToday(),
                queue.highRisk(),
                scansToday);
    }

    public QueuePage queue(QueueQuery query) {
        List<Object> arguments = new ArrayList<>();
        String where = where(query, arguments);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics.moderation_queue_item" + where,
                Long.class,
                arguments.toArray());
        long totalItems = total == null ? 0L : total;

        String sql = """
                SELECT id, target_type, target, category, title, status, risk_level, priority,
                       submitted_at, evidence_count, moderator_note
                FROM analytics.moderation_queue_item
                """ + where + " ORDER BY " + query.sort().sqlOrder() + " LIMIT ? OFFSET ?";
        arguments.add(query.size());
        arguments.add((long) query.page() * query.size());

        List<QueueItem> items = jdbc.query(sql, (rs, rowNum) -> mapItem(rs), arguments.toArray());
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + query.size() - 1) / query.size());
        return new QueuePage(items, query.page(), query.size(), totalItems, totalPages);
    }

    public QueueCounts counts() {
        EnumMap<ModerationStatus, Long> byStatus = new EnumMap<>(ModerationStatus.class);
        for (ModerationStatus status : ModerationStatus.values()) {
            byStatus.put(status, 0L);
        }
        jdbc.query("""
                SELECT status, COUNT(*) AS total
                FROM analytics.moderation_queue_item
                GROUP BY status
                """, (RowCallbackHandler) rs -> byStatus.put(
                storedEnum(ModerationStatus.class, rs.getString("status"), "status"),
                rs.getLong("total")));

        EnumMap<Priority, Long> byPriority = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            byPriority.put(priority, 0L);
        }
        jdbc.query("""
                SELECT priority, COUNT(*) AS total
                FROM analytics.moderation_queue_item
                GROUP BY priority
                """, (RowCallbackHandler) rs -> byPriority.put(
                storedEnum(Priority.class, rs.getString("priority"), "priority"),
                rs.getLong("total")));

        return new QueueCounts(byStatus, byPriority);
    }

    public AnalyticsTrends trends(int days, LocalDate today) {
        if (days < MIN_TREND_DAYS || days > MAX_TREND_DAYS) {
            throw new IllegalArgumentException(
                    "days must be between " + MIN_TREND_DAYS + " and " + MAX_TREND_DAYS);
        }
        Objects.requireNonNull(today, "today must not be null");

        LocalDate fromDate = today.minusDays(days - 1L);
        LocalDate toDate = today;

        Map<LocalDate, DailyMetricsRow> rowsByDate = new HashMap<>();
        jdbc.query("""
                SELECT metric_date,
                       total_scans,
                       sms_scans,
                       url_scans,
                       high_risk_scans,
                       risk_low_scans,
                       risk_medium_scans,
                       risk_high_scans,
                       risk_critical_scans,
                       reports_created,
                       reports_verified,
                       reports_rejected,
                       reports_need_more_info,
                       new_threats
                FROM analytics.daily_metrics
                WHERE metric_date >= ? AND metric_date <= ?
                ORDER BY metric_date ASC
                """,
                (RowCallbackHandler) rs -> {
                    LocalDate metricDate = rs.getObject("metric_date", LocalDate.class);
                    rowsByDate.put(metricDate, new DailyMetricsRow(
                            metricDate,
                            rs.getLong("total_scans"),
                            rs.getLong("sms_scans"),
                            rs.getLong("url_scans"),
                            rs.getLong("high_risk_scans"),
                            rs.getLong("risk_low_scans"),
                            rs.getLong("risk_medium_scans"),
                            rs.getLong("risk_high_scans"),
                            rs.getLong("risk_critical_scans"),
                            rs.getLong("reports_created"),
                            rs.getLong("reports_verified"),
                            rs.getLong("reports_rejected"),
                            rs.getLong("reports_need_more_info"),
                            rs.getLong("new_threats")));
                },
                fromDate,
                toDate);

        List<DailyTrendPoint> series = new ArrayList<>(days);
        long totalScans = 0L;
        long textScans = 0L;
        long urlScans = 0L;
        long highRiskScans = 0L;
        long riskLow = 0L;
        long riskMedium = 0L;
        long riskHigh = 0L;
        long riskCritical = 0L;
        long reportsCreated = 0L;
        long reportsVerified = 0L;
        long reportsRejected = 0L;
        long reportsNeedMoreInfo = 0L;
        long newThreats = 0L;

        for (int offset = 0; offset < days; offset++) {
            LocalDate date = fromDate.plusDays(offset);
            DailyMetricsRow row = rowsByDate.get(date);
            if (row == null) {
                series.add(new DailyTrendPoint(date, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
                continue;
            }
            series.add(new DailyTrendPoint(
                    date,
                    row.totalScans(),
                    row.textScans(),
                    row.urlScans(),
                    row.highRiskScans(),
                    row.reportsCreated(),
                    row.reportsVerified(),
                    row.reportsRejected(),
                    row.reportsNeedMoreInfo(),
                    row.newThreats()));
            totalScans += row.totalScans();
            textScans += row.textScans();
            urlScans += row.urlScans();
            highRiskScans += row.highRiskScans();
            riskLow += row.riskLowScans();
            riskMedium += row.riskMediumScans();
            riskHigh += row.riskHighScans();
            riskCritical += row.riskCriticalScans();
            reportsCreated += row.reportsCreated();
            reportsVerified += row.reportsVerified();
            reportsRejected += row.reportsRejected();
            reportsNeedMoreInfo += row.reportsNeedMoreInfo();
            newThreats += row.newThreats();
        }

        EnumMap<RiskLevel, Long> riskBreakdown = new EnumMap<>(RiskLevel.class);
        riskBreakdown.put(RiskLevel.LOW, riskLow);
        riskBreakdown.put(RiskLevel.MEDIUM, riskMedium);
        riskBreakdown.put(RiskLevel.HIGH, riskHigh);
        riskBreakdown.put(RiskLevel.CRITICAL, riskCritical);

        TrendTotals totals = new TrendTotals(
                totalScans,
                textScans,
                urlScans,
                highRiskScans,
                reportsCreated,
                reportsVerified,
                reportsRejected,
                reportsNeedMoreInfo,
                newThreats);

        return new AnalyticsTrends(
                days,
                fromDate,
                toDate,
                totals,
                Collections.unmodifiableMap(riskBreakdown),
                List.copyOf(series));
    }

    @Override
    public Map<UUID, RiskLevel> findRiskLevels(List<UUID> reportIds) {
        if (reportIds == null || reportIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> distinctIds = reportIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(", ", Collections.nCopies(distinctIds.size(), "?"));
        String sql = """
                SELECT r.report_id, v.risk_level
                FROM analytics.report_lifecycle_state r
                JOIN analytics.target_reputation_view v ON v.target_id = r.target_id
                WHERE r.report_id IN (%s)
                """.formatted(placeholders);

        try {
            Map<UUID, RiskLevel> riskByReport = new LinkedHashMap<>();
            jdbc.query(sql, (RowCallbackHandler) rs -> {
                UUID reportId = rs.getObject("report_id", UUID.class);
                String rawLevel = rs.getString("risk_level");
                if (reportId == null || rawLevel == null) {
                    return;
                }
                try {
                    riskByReport.put(reportId, RiskLevel.valueOf(rawLevel));
                } catch (IllegalArgumentException ignored) {
                    log.warn("Ignoring invalid risk_level in analytics.target_reputation_view for reportId={}", reportId);
                }
            }, distinctIds.toArray());
            return Collections.unmodifiableMap(riskByReport);
        } catch (DataAccessException exception) {
            log.warn("Fail-open queue riskLevel lookup degraded: reason={}", exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private String where(QueueQuery query, List<Object> arguments) {
        List<String> clauses = new ArrayList<>();
        if (query.status() != null) {
            clauses.add("status = ?");
            arguments.add(query.status().name());
        }
        if (query.priority() != null) {
            clauses.add("priority = ?");
            arguments.add(query.priority().name());
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private QueueItem mapItem(ResultSet rs) throws SQLException {
        OffsetDateTime submittedAt = rs.getObject("submitted_at", OffsetDateTime.class);
        return new QueueItem(
                rs.getObject("id", UUID.class),
                rs.getString("target_type"),
                rs.getString("target"),
                rs.getString("category"),
                rs.getString("title"),
                storedEnum(ModerationStatus.class, rs.getString("status"), "status"),
                storedEnum(RiskLevel.class, rs.getString("risk_level"), "risk_level"),
                storedEnum(Priority.class, rs.getString("priority"), "priority"),
                submittedAt.toInstant(),
                rs.getInt("evidence_count"),
                rs.getString("moderator_note"));
    }

    /**
     * A value outside the allowlisted enum means stored data is corrupt, not that
     * the client sent a bad request. Fail as a server error instead of a 400.
     */
    private static <E extends Enum<E>> E storedEnum(Class<E> type, String value, String column) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Unexpected " + column + " value in analytics data", exception);
        }
    }

    private record DailyMetricsRow(
            LocalDate metricDate,
            long totalScans,
            long textScans,
            long urlScans,
            long highRiskScans,
            long riskLowScans,
            long riskMediumScans,
            long riskHighScans,
            long riskCriticalScans,
            long reportsCreated,
            long reportsVerified,
            long reportsRejected,
            long reportsNeedMoreInfo,
            long newThreats) {
    }
}
