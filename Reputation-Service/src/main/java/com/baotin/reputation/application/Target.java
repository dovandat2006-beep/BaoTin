package com.baotin.reputation.application;

import com.baotin.reputation.api.ReputationResponse;
import com.baotin.reputation.domain.RiskLevel;
import com.baotin.reputation.domain.TargetType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Target(
    UUID threatTargetId,
    TargetType targetType,
    BigDecimal reportSignal,
    BigDecimal ruleSignal,
    BigDecimal aiSignal,
    BigDecimal campaignSignal,
    BigDecimal finalScore,
    RiskLevel riskLevel,
    BigDecimal confidence,
    int totalReportCount,
    int verifiedReportCount,
    String calculationVersion,
    Instant calculatedAt,
    Instant lastEventAt,
    long version,
    Instant createdAt,
    Instant updatedAt
) {
    public ReputationResponse toResponse() {
        return new ReputationResponse(threatTargetId, targetType.name(), reportSignal, ruleSignal, aiSignal,
            campaignSignal, finalScore, riskLevel.name(), confidence, totalReportCount, verifiedReportCount,
            calculationVersion, calculatedAt, version);
    }
}
