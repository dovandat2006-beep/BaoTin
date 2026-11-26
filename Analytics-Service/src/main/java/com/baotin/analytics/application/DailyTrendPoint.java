package com.baotin.analytics.application;

import java.time.LocalDate;

public record DailyTrendPoint(
        LocalDate date,
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
