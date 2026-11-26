package com.baotin.analytics.application;

public record AdminSummary(
        long pendingReview,
        long needMoreInfo,
        long resolvedToday,
        long highRisk,
        long scansToday) {
}
