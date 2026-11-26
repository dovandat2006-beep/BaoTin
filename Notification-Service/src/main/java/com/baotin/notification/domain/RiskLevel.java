package com.baotin.notification.domain;

import java.math.BigDecimal;

/** Risk levels are owned by the scoring services; this is only a tolerant boundary decoder. */
public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static RiskLevel fromScore(BigDecimal score) {
        if (score == null) return null;
        double value = score.doubleValue();
        if (value < 25) return LOW;
        if (value < 50) return MEDIUM;
        if (value < 75) return HIGH;
        return CRITICAL;
    }

    public static RiskLevel parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
