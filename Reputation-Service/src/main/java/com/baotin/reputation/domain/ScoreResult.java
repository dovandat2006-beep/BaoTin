package com.baotin.reputation.domain;

import java.math.BigDecimal;

public record ScoreResult(
    BigDecimal reportSignal,
    BigDecimal ruleSignal,
    BigDecimal aiSignal,
    BigDecimal campaignSignal,
    BigDecimal finalScore,
    RiskLevel riskLevel,
    BigDecimal confidence,
    int totalReportCount,
    int verifiedReportCount,
    String calculationVersion
) {
}
