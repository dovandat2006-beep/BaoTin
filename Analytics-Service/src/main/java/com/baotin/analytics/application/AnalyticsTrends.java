package com.baotin.analytics.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AnalyticsTrends(
        int days,
        LocalDate fromDate,
        LocalDate toDate,
        TrendTotals totals,
        Map<RiskLevel, Long> riskBreakdown,
        List<DailyTrendPoint> series) {
}
