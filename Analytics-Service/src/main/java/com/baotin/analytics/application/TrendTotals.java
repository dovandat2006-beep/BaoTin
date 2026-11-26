package com.baotin.analytics.application;

public record TrendTotals(
        long totalScans,
        long textScans,
        long urlScans,
        long highRiskScans,
        long reportsCreated,
        long reportsVerified,
        long reportsRejected,
        long reportsNeedMoreInfo,
        long newThreats) {
}
