package com.baotin.analytics.events;

public enum IngestOutcome {
    APPLIED,
    DUPLICATE,
    CONFLICT_IGNORED,
    OUT_OF_ORDER_IGNORED,
    IGNORED_EVENT_TYPE
}
