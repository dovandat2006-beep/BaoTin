package com.baotin.analytics.application;

import java.util.List;

public record QueuePage(
        List<QueueItem> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {

    public QueuePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
