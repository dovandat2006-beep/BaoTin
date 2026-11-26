package com.baotin.reputation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReportContribution(
    UUID reportId,
    UUID threatTargetId,
    BigDecimal weight,
    ReportContributionStatus status,
    Instant occurredAt,
    Instant lastEventAt
) {
}
