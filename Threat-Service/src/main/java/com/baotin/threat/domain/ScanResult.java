package com.baotin.threat.domain;

import java.time.Instant;
import java.util.*;

/** Immutable snapshot; no raw SMS, credentials or provider exception text. */
public record ScanResult(UUID scanId, String type, UUID targetId, String preview, Risk risk,
                         ModelSignal textModel, ModelSignal urlModel, Rules rules,
                         List<UrlResult> urls, boolean aiAvailable, boolean degraded,
                         List<String> warnings, Instant scannedAt) {
    public record Risk(double score, String level) {
        public static Risk of(double score) {
            double rounded = Math.round(Math.clamp(score, 0, 100) * 100.0) / 100.0;
            return new Risk(rounded, rounded >= 75 ? "CRITICAL" : rounded >= 50 ? "HIGH" : rounded >= 25 ? "MEDIUM" : "LOW");
        }
    }
    public record ModelSignal(String modelName, String modelVersion, String modelChecksum, String label,
                              double confidence, double riskProbability, Map<String, Double> probabilities, double latencyMs) {}
    public record Rules(double score, List<String> signals) {}
    public record UrlResult(String url, UUID targetId, ModelSignal model, Rules rules, Double reputationScore,
                            Risk risk, boolean aiAvailable) {}
}
