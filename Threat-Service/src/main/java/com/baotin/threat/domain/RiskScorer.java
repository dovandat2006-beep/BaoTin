package com.baotin.threat.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RiskScorer {
    private final double[] urlNew, urlKnown, textPlain, textLinks;
    public RiskScorer(@Value("${scan.weights.url-new:0.70,0.30}") double[] urlNew,
                      @Value("${scan.weights.url-known:0.50,0.20,0.30}") double[] urlKnown,
                      @Value("${scan.weights.text-plain:0.85,0.15}") double[] textPlain,
                      @Value("${scan.weights.text-links:0.75,0.15,0.10}") double[] textLinks) {
        validate(urlNew, 2); validate(urlKnown, 3); validate(textPlain, 2); validate(textLinks, 3);
        this.urlNew = urlNew.clone(); this.urlKnown = urlKnown.clone(); this.textPlain = textPlain.clone(); this.textLinks = textLinks.clone();
    }
    private void validate(double[] weights, int size) {
        if (weights.length != size || java.util.Arrays.stream(weights).anyMatch(w -> !Double.isFinite(w) || w <= 0) || Math.abs(java.util.Arrays.stream(weights).sum() - 1) > 1e-8) throw new IllegalArgumentException("Positive risk weights must sum to one");
    }
    public ScanResult.Risk url(Double probability, double rules, Double reputation) {
        return ScanResult.Risk.of(reputation == null ? combine(urlNew, probability == null ? null : probability * 100, rules)
                : combine(urlKnown, probability == null ? null : probability * 100, rules, reputation));
    }
    public ScanResult.Risk text(Double probability, double rules, Double embedded) {
        return ScanResult.Risk.of(embedded == null ? combine(textPlain, probability == null ? null : probability * 100, rules)
                : combine(textLinks, probability == null ? null : probability * 100, rules, embedded));
    }
    private double combine(double[] weights, Double... signals) {
        double total = 0, weight = 0;
        for (int i = 0; i < signals.length; i++) if (signals[i] != null) { total += signals[i] * weights[i]; weight += weights[i]; }
        return weight == 0 ? 0 : total / weight;
    }
}
