package com.baotin.analytics.application;

public record QueueQuery(
        int page,
        int size,
        ModerationStatus status,
        Priority priority,
        QueueSort sort) {

    public QueueQuery {
        if (page < 0 || page > 10000) {
            throw new IllegalArgumentException("page must be between 0 and 10000");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        if (sort == null) {
            throw new IllegalArgumentException("sort is required");
        }
    }
}
