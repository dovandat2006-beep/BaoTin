package com.baotin.analytics.application;

import java.util.Map;

public record QueueCounts(
        Map<ModerationStatus, Long> byStatus,
        Map<Priority, Long> byPriority) {

    public QueueCounts {
        byStatus = byStatus == null ? Map.of() : Map.copyOf(byStatus);
        byPriority = byPriority == null ? Map.of() : Map.copyOf(byPriority);
    }
}
