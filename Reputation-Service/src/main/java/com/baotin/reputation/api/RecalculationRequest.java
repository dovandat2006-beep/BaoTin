package com.baotin.reputation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RecalculationRequest(
    String targetType,
    @Valid Signals signals,
    @Min(0) Integer totalReportCount,
    @Min(0) Integer verifiedReportCount,
    @Size(max = 200) String reason
) {
    public record Signals(
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal reportSignal,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal ruleSignal,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal aiSignal,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal campaignSignal
    ) {
    }

    public static RecalculationRequest empty() {
        return new RecalculationRequest(null, null, null, null, null);
    }
}
