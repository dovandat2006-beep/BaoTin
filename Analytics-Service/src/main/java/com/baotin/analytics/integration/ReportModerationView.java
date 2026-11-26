package com.baotin.analytics.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReportModerationView(
        List<Item> items,
        int page,
        int size,
        long totalItems,
        long totalPages,
        Counts counts) {

    public ReportModerationView {
        items = items == null ? null : List.copyOf(items);
    }

    public record Item(
            UUID id,
            String targetType,
            String target,
            String category,
            String title,
            String status,
            String priority,
            Instant submittedAt,
            int evidenceCount,
            String moderatorNote) {
    }

    public record Counts(
            Map<String, Long> byStatus,
            Map<String, Long> byPriority,
            long resolvedToday) {

        public Counts {
            byStatus = byStatus == null ? null : Map.copyOf(byStatus);
            byPriority = byPriority == null ? null : Map.copyOf(byPriority);
        }
    }
}
