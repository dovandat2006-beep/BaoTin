package com.baotin.analytics.backfill;

import java.util.UUID;

public record BackfillRunSummary(
        UUID runId,
        String sourceType,
        String sourceRef,
        String status,
        long lastCursor,
        long processedCount,
        long appliedCount,
        long skippedCount,
        int batchSize) {
}
