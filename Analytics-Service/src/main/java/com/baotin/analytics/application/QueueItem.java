package com.baotin.analytics.application;

import java.time.Instant;
import java.util.UUID;

public record QueueItem(
        UUID id,
        String targetType,
        String target,
        String category,
        String title,
        ModerationStatus status,
        RiskLevel riskLevel,
        Priority priority,
        Instant submittedAt,
        int evidenceCount,
        String moderatorNote) {
}
