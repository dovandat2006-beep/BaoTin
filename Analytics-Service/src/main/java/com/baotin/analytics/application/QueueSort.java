package com.baotin.analytics.application;

import java.util.Locale;

public record QueueSort(Field field, Direction direction) {

    public enum Field {
        submittedAt,
        priority,
        riskLevel,
        status
    }

    public enum Direction {
        asc,
        desc
    }

    public QueueSort {
        if (field == null || direction == null) {
            throw new IllegalArgumentException("sort field and direction are required");
        }
    }

    public static QueueSort parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("sort is required");
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("sort must be field,direction");
        }

        Field field;
        try {
            field = Field.valueOf(parts[0]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported sort field", exception);
        }

        Direction direction;
        try {
            direction = Direction.valueOf(parts[1]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported sort direction", exception);
        }

        return new QueueSort(field, direction);
    }

    public String sqlOrder() {
        String expression = switch (field) {
            case submittedAt -> "submitted_at";
            case priority -> "CASE priority WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END";
            case riskLevel -> "CASE risk_level WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END";
            case status -> "CASE status WHEN 'SUBMITTED' THEN 0 WHEN 'PENDING_REVIEW' THEN 1 WHEN 'NEED_MORE_INFO' THEN 2 WHEN 'VERIFIED' THEN 3 ELSE 4 END";
        };
        return expression + " " + direction.name().toLowerCase(Locale.ROOT) + ", id ASC";
    }
}
