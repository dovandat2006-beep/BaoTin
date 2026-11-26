package com.baotin.analytics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueueSortTests {

    @Test
    void parsesAllowlistedFieldsAndDirections() {
        assertEquals("submitted_at desc, id ASC", QueueSort.parse("submittedAt,desc").sqlOrder());
        assertEquals("submitted_at asc, id ASC", QueueSort.parse("submittedAt,asc").sqlOrder());
        assertTrue(QueueSort.parse("priority,desc").sqlOrder().contains("WHEN 'HIGH' THEN 3"));
        assertTrue(QueueSort.parse("riskLevel,desc").sqlOrder().contains("WHEN 'CRITICAL' THEN 4"));
        assertTrue(QueueSort.parse("status,asc").sqlOrder().contains("WHEN 'SUBMITTED' THEN 0"));
    }

    @Test
    void rejectsMalformedExpressions() {
        for (String value : new String[] {
                null,
                "",
                "submittedAt",
                ",desc",
                "submittedAt,",
                "unknown,desc",
                "submittedAt,sideways",
                "submittedAt,desc,extra",
                "submittedAt,desc "
        }) {
            assertThrows(IllegalArgumentException.class, () -> QueueSort.parse(value), String.valueOf(value));
        }
    }
}
