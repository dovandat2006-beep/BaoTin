package com.baotin.reputation.domain;

import java.math.BigDecimal;

public record CalculationConfig(
    String version,
    BigDecimal reportWeight,
    BigDecimal ruleWeight,
    BigDecimal aiWeight,
    BigDecimal campaignWeight,
    BigDecimal reportSaturation,
    BigDecimal decay0To30,
    BigDecimal decay31To90,
    BigDecimal decay91To180,
    BigDecimal decayOver180
) {
    private static final BigDecimal ONE = new BigDecimal("1.0000");

    public static CalculationConfig defaults(TargetType targetType) {
        return switch (targetType) {
            case PHONE, BANK_ACCOUNT, EMAIL -> new CalculationConfig(
                "v1-default",
                new BigDecimal("0.5500"), new BigDecimal("0.2000"), new BigDecimal("0.0000"), new BigDecimal("0.2500"),
                new BigDecimal("100.00"),
                new BigDecimal("1.000"), new BigDecimal("0.800"), new BigDecimal("0.600"), new BigDecimal("0.400"));
            case URL, OTHER -> new CalculationConfig(
                "v1-default",
                new BigDecimal("0.3500"), new BigDecimal("0.2500"), new BigDecimal("0.2500"), new BigDecimal("0.1500"),
                new BigDecimal("100.00"),
                new BigDecimal("1.000"), new BigDecimal("0.800"), new BigDecimal("0.600"), new BigDecimal("0.400"));
        };
    }

    public boolean valid() {
        if (version == null || version.isBlank() || version.length() > 30) {
            return false;
        }
        if (reportSaturation == null || reportSaturation.signum() <= 0) {
            return false;
        }
        if (!nonNegative(reportWeight) || !nonNegative(ruleWeight) || !nonNegative(aiWeight) || !nonNegative(campaignWeight)) {
            return false;
        }
        BigDecimal sum = reportWeight.add(ruleWeight).add(aiWeight).add(campaignWeight);
        if (sum.subtract(ONE).abs().compareTo(new BigDecimal("0.0001")) > 0) {
            return false;
        }
        return unit(decay0To30) && unit(decay31To90)
            && unit(decay91To180) && unit(decayOver180);
    }

    private static boolean nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }

    private static boolean unit(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }
}
