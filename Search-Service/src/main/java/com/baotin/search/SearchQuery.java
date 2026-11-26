package com.baotin.search;

import java.text.Normalizer;
import java.util.Locale;

public record SearchQuery(String q, String type, String riskLevel, int page, int size) {
    public SearchQuery {
        q = q == null ? "" : q.strip();
        if (q.length() < 2 || q.length() > 254 || q.chars().anyMatch(Character::isISOControl)
                || type != null && !SearchTypes.ALL.contains(type)
                || riskLevel != null && !java.util.Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(riskLevel)
                || page < 0 || page > 1000 || size < 1 || size > 50) {
            throw new IllegalArgumentException("Invalid search parameters");
        }
    }
    public static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd');
    }
}
