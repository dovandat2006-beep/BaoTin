package com.baotin.reputation.domain;

import java.math.BigDecimal;

public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    private static final BigDecimal MEDIUM_FROM = new BigDecimal("25.00");
    private static final BigDecimal HIGH_FROM = new BigDecimal("50.00");
    private static final BigDecimal CRITICAL_FROM = new BigDecimal("75.00");

    public static RiskLevel from(BigDecimal score) {
        if (score == null) {
            return LOW;
        }
        if (score.compareTo(MEDIUM_FROM) < 0) {
            return LOW;
        }
        if (score.compareTo(HIGH_FROM) < 0) {
            return MEDIUM;
        }
        if (score.compareTo(CRITICAL_FROM) < 0) {
            return HIGH;
        }
        return CRITICAL;
    }
}
