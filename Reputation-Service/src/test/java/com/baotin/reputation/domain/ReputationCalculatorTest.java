package com.baotin.reputation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReputationCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final ReputationCalculator calculator = new ReputationCalculator();

    @Test
    void appliesConfiguredWeightsAndLevels() {
        CalculationConfig config = reportOnlyConfig();
        assertScore(config, "24.99", RiskLevel.LOW);
        assertScore(config, "25.00", RiskLevel.MEDIUM);
        assertScore(config, "49.99", RiskLevel.MEDIUM);
        assertScore(config, "50.00", RiskLevel.HIGH);
        assertScore(config, "74.99", RiskLevel.HIGH);
        assertScore(config, "75.00", RiskLevel.CRITICAL);
        assertScore(config, "100.00", RiskLevel.CRITICAL);
    }

    @Test
    void clampsSignalsAndFinalScoreToBounds() {
        CalculationConfig config = reportOnlyConfig();
        ScoreResult high = calculator.calculate(input(config, new BigDecimal("150"), ZERO, ZERO, ZERO, 0, 0));
        assertThat(high.reportSignal()).isEqualByComparingTo(HUNDRED);
        assertThat(high.finalScore()).isEqualByComparingTo(HUNDRED);
        assertThat(high.riskLevel()).isEqualTo(RiskLevel.CRITICAL);

        ScoreResult low = calculator.calculate(input(config, new BigDecimal("-50"), ZERO, ZERO, ZERO, 0, 0));
        assertThat(low.reportSignal()).isEqualByComparingTo(ZERO);
        assertThat(low.finalScore()).isEqualByComparingTo(ZERO);
        assertThat(low.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void derivesURlScoreFromAllFourSignals() {
        CalculationConfig config = CalculationConfig.defaults(TargetType.URL);
        ScoreResult result = calculator.calculate(input(config, new BigDecimal("60"), new BigDecimal("40"),
            new BigDecimal("30"), new BigDecimal("20"), 10, 6));
        assertThat(result.finalScore()).isEqualByComparingTo("41.50");
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.confidence()).isEqualByComparingTo("1.00000");
    }

    @Test
    void appliesTimeDecay() {
        CalculationConfig config = CalculationConfig.defaults(TargetType.URL);
        assertThat(ReputationCalculator.derivedReportSignal(
            List.of(contribution("100", NOW.minus(Duration.ofDays(40)))), config, NOW))
            .isEqualByComparingTo("44.44");
        assertThat(ReputationCalculator.derivedReportSignal(
            List.of(contribution("100", NOW.minus(Duration.ofDays(100)))), config, NOW))
            .isEqualByComparingTo("37.50");
        assertThat(ReputationCalculator.derivedReportSignal(
            List.of(contribution("100", NOW.minus(Duration.ofDays(200)))), config, NOW))
            .isEqualByComparingTo("28.57");
    }

    @Test
    void reportSignalSaturatesInsteadOfGrowingWithoutBound() {
        CalculationConfig config = CalculationConfig.defaults(TargetType.URL);
        List<ReportContribution> contributions = List.of(
            contribution("100", NOW.minus(Duration.ofDays(1))),
            contribution("100", NOW.minus(Duration.ofDays(2))));
        assertThat(ReputationCalculator.derivedReportSignal(contributions, config, NOW))
            .isEqualByComparingTo("66.67");
    }

    @Test
    void confidenceRewardsVerifiedVolumeAndCoverage() {
        CalculationConfig config = CalculationConfig.defaults(TargetType.URL);
        ScoreResult high = calculator.calculate(input(config, new BigDecimal("50"), new BigDecimal("50"),
            new BigDecimal("50"), new BigDecimal("50"), 10, 5));
        assertThat(high.confidence()).isEqualByComparingTo("1.00000");

        ScoreResult none = calculator.calculate(input(config, ZERO, ZERO, ZERO, ZERO, 0, 0));
        assertThat(none.confidence()).isEqualByComparingTo("0.00000");
    }

    @Test
    void defaultConfigsAreValidAndSumToOne() {
        for (TargetType type : TargetType.values()) {
            assertThat(CalculationConfig.defaults(type).valid()).isTrue();
        }
    }

    private void assertScore(CalculationConfig config, String score, RiskLevel expected) {
        ScoreResult result = calculator.calculate(input(config, new BigDecimal(score), ZERO, ZERO, ZERO, 0, 0));
        assertThat(result.finalScore()).isEqualByComparingTo(score);
        assertThat(result.riskLevel()).isEqualTo(expected);
    }

    private static CalculationInput input(CalculationConfig config, BigDecimal report, BigDecimal rule,
                                          BigDecimal ai, BigDecimal campaign, int total, int verified) {
        return new CalculationInput(report, rule, ai, campaign, total, verified,
            List.of(), config, NOW);
    }

    private static CalculationConfig reportOnlyConfig() {
        return new CalculationConfig("test", new BigDecimal("1.0000"), new BigDecimal("0.0000"),
            new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("100.00"),
            new BigDecimal("1.000"), new BigDecimal("0.800"), new BigDecimal("0.600"), new BigDecimal("0.400"));
    }

    private static ReportContribution contribution(String weight, Instant occurredAt) {
        return new ReportContribution(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(weight),
            ReportContributionStatus.VERIFIED, occurredAt, occurredAt);
    }
}
