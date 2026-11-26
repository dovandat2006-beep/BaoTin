package com.baotin.report.api;

import java.time.Instant;
import java.util.*;

public record AnalyticsModerationView(List<Item> items, int page, int size, long totalItems, long totalPages, Counts counts) {
    public record Item(UUID id, String targetType, String target, String category, String title, String status,
        String priority, Instant submittedAt, int evidenceCount, String moderatorNote) {}
    public record Counts(Map<String,Long> byStatus, Map<String,Long> byPriority, long resolvedToday) {}
}
