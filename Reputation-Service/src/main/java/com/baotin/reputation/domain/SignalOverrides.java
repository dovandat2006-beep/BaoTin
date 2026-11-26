package com.baotin.reputation.domain;

import java.math.BigDecimal;

public record SignalOverrides(
    BigDecimal reportSignal,
    BigDecimal ruleSignal,
    BigDecimal aiSignal,
    BigDecimal campaignSignal,
    Integer totalReportCount,
    Integer verifiedReportCount
) {
    public static SignalOverrides empty() {
        return new SignalOverrides(null, null, null, null, null, null);
    }
}
