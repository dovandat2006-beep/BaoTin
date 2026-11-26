package com.baotin.modelinference.domain;

import java.util.Map;

/** Confidence belongs to the winning class; riskProbability sums all non-benign classes. */
public record Prediction(String modelName, String modelVersion, String modelChecksum,
                         String label, double confidence, double riskProbability,
                         Map<String, Double> probabilities, double latencyMs) {}
