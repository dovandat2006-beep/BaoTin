package com.baotin.reputation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CalculationInput(
    BigDecimal reportSignalOverride,
    BigDecimal ruleSignal,
    BigDecimal aiSignal,
    BigDecimal campaignSignal,
    Integer totalReportCountOverride,
    Integer verifiedReportCountOverride,
    List<ReportContribution> contributions,
    CalculationConfig config,
    Instant calculatedAt
) {
    public CalculationInput {
        Objects.requireNonNull(ruleSignal, "ruleSignal");
        Objects.requireNonNull(aiSignal, "aiSignal");
        Objects.requireNonNull(campaignSignal, "campaignSignal");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        contributions = List.copyOf(contributions);
    }
}
