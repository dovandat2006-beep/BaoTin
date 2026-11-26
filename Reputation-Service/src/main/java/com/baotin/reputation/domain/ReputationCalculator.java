package com.baotin.reputation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class ReputationCalculator {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    public ScoreResult calculate(CalculationInput input) {
        CalculationConfig config = input.config();
        if (!config.valid()) {
            throw new IllegalArgumentException("Invalid calculation config");
        }

        BigDecimal report = input.reportSignalOverride() == null
            ? derivedReportSignal(input.contributions(), config, input.calculatedAt())
            : clampSignal(input.reportSignalOverride());
        BigDecimal rule = clampSignal(input.ruleSignal());
        BigDecimal ai = clampSignal(input.aiSignal());
        BigDecimal campaign = clampSignal(input.campaignSignal());

        int total = input.totalReportCountOverride() == null
            ? input.contributions().size()
            : input.totalReportCountOverride();
        int verified = input.verifiedReportCountOverride() == null
            ? countVerified(input.contributions())
            : input.verifiedReportCountOverride();
        if (total < 0 || verified < 0 || verified > total) {
            throw new IllegalArgumentException("verifiedReportCount must be between 0 and totalReportCount");
        }

        BigDecimal finalScore = report.multiply(config.reportWeight())
            .add(rule.multiply(config.ruleWeight()))
            .add(ai.multiply(config.aiWeight()))
            .add(campaign.multiply(config.campaignWeight()));
        finalScore = clampSignal(finalScore);
        BigDecimal confidence = confidence(total, verified, report, rule, ai, campaign);

        return new ScoreResult(report, rule, ai, campaign, finalScore, RiskLevel.from(finalScore),
            confidence, total, verified, config.version());
    }

    public static BigDecimal derivedReportSignal(List<ReportContribution> contributions,
                                                 CalculationConfig config,
                                                 Instant calculatedAt) {
        BigDecimal raw = ZERO;
        for (ReportContribution contribution : contributions) {
            if (contribution.status() != ReportContributionStatus.VERIFIED) {
                continue;
            }
            BigDecimal weight = contribution.weight() == null ? ZERO : contribution.weight();
            raw = raw.add(weight.multiply(decay(contribution.occurredAt(), calculatedAt, config)));
        }
        if (raw.signum() <= 0) {
            return ZERO;
        }
        BigDecimal signal = raw.multiply(HUNDRED)
            .divide(raw.add(config.reportSaturation()), 2, RoundingMode.HALF_UP);
        return clampSignal(signal);
    }

    public static BigDecimal decay(Instant occurredAt, Instant calculatedAt, CalculationConfig config) {
        if (occurredAt == null || calculatedAt == null) {
            return config.decay0To30();
        }
        long days = Duration.between(occurredAt, calculatedAt).toDays();
        if (days <= 30) {
            return config.decay0To30();
        }
        if (days <= 90) {
            return config.decay31To90();
        }
        if (days <= 180) {
            return config.decay91To180();
        }
        return config.decayOver180();
    }

    private static BigDecimal confidence(int total, int verified,
                                         BigDecimal report, BigDecimal rule,
                                         BigDecimal ai, BigDecimal campaign) {
        BigDecimal verifiedEvidence = BigDecimal.valueOf(verified)
            .divide(BigDecimal.valueOf(5), 6, RoundingMode.HALF_UP).min(ONE);
        BigDecimal reportVolume = BigDecimal.valueOf(total)
            .divide(BigDecimal.valueOf(10), 6, RoundingMode.HALF_UP).min(ONE);
        int nonZero = 0;
        if (report.signum() > 0) nonZero++;
        if (rule.signum() > 0) nonZero++;
        if (ai.signum() > 0) nonZero++;
        if (campaign.signum() > 0) nonZero++;
        BigDecimal signalCoverage = BigDecimal.valueOf(nonZero)
            .divide(BigDecimal.valueOf(4), 6, RoundingMode.HALF_UP);

        BigDecimal confidence = verifiedEvidence.multiply(new BigDecimal("0.50"))
            .add(reportVolume.multiply(new BigDecimal("0.30")))
            .add(signalCoverage.multiply(new BigDecimal("0.20")))
            .setScale(5, RoundingMode.HALF_UP);
        if (confidence.signum() < 0) {
            return BigDecimal.ZERO.setScale(5);
        }
        return confidence.min(ONE.setScale(5));
    }

    private static int countVerified(List<ReportContribution> contributions) {
        int count = 0;
        for (ReportContribution contribution : contributions) {
            if (contribution.status() == ReportContributionStatus.VERIFIED) {
                count++;
            }
        }
        return count;
    }

    public static BigDecimal clampSignal(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(ZERO) < 0) {
            return ZERO;
        }
        if (rounded.compareTo(HUNDRED) > 0) {
            return HUNDRED;
        }
        return rounded;
    }
}
