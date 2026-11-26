package com.baotin.reputation.domain;

import java.util.Locale;

public enum TargetType {
    URL,
    PHONE,
    BANK_ACCOUNT,
    EMAIL,
    OTHER;

    public static TargetType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("targetType is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported targetType");
        }
    }
}
