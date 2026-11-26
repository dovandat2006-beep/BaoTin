package com.baotin.reputation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReputationResponse(
    UUID threatTargetId,
    String targetType,
    BigDecimal reportSignal,
    BigDecimal ruleSignal,
    BigDecimal aiSignal,
    BigDecimal campaignSignal,
    BigDecimal finalScore,
    String riskLevel,
    BigDecimal confidence,
    int totalReportCount,
    int verifiedReportCount,
    String calculationVersion,
    Instant calculatedAt,
    long version
) {
}
