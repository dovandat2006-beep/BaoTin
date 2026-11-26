package com.baotin.reputation.application;

import com.baotin.reputation.api.ApiFailure;
import com.baotin.reputation.api.RecalculationRequest;
import com.baotin.reputation.api.ReputationResponse;
import com.baotin.reputation.domain.CalculationConfig;
import com.baotin.reputation.domain.CalculationInput;
import com.baotin.reputation.domain.ReputationCalculator;
import com.baotin.reputation.domain.ScoreResult;
import com.baotin.reputation.domain.SignalOverrides;
import com.baotin.reputation.domain.TargetType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReputationService {
    private final ReputationStore store;
    private final OutboxStore outbox;
    private final ReputationCalculator calculator;
    private final ReputationCache cache;
    private final JsonMapper json;
    private final Clock clock;
    private final String topic;

    public ReputationService(ReputationStore store, OutboxStore outbox, ReputationCalculator calculator,
                             ReputationCache cache, JsonMapper json, Clock clock,
                             @Value("${reputation.events.topic}") String topic) {
        this.store = store;
        this.outbox = outbox;
        this.calculator = calculator;
        this.cache = cache;
        this.json = json;
        this.clock = clock;
        this.topic = topic;
    }

    @Transactional(readOnly = true)
    public ReputationResponse get(UUID targetId) {
        Optional<ReputationResponse> cached = cache.get(targetId);
        if (cached.isPresent()) {
            return cached.get();
        }
        Target target = store.find(targetId).orElseThrow(ApiFailure::notFound);
        ReputationResponse response = target.toResponse();
        cache.put(targetId, response);
        return response;
    }

    @Transactional
    public ReputationResponse recalculate(UUID targetId, RecalculationRequest request, String correlationId) {
        TargetType targetType = parseType(request.targetType());
        Target current;
        if (targetType == null) {
            current = store.findForUpdate(targetId).orElseThrow(() -> ApiFailure.badRequest(
                "TARGET_TYPE_REQUIRED", "targetType is required for a target that has not been calculated"));
        } else {
            current = store.ensureAndLock(targetId, targetType);
        }
        SignalOverrides overrides = overrides(request);
        validateOverrides(overrides);
        Instant now = clock.instant();
        return recalculateLocked(current, overrides, reason(request.reason(), "MANUAL_RECALCULATION"),
            correlationId, null, now, now);
    }

    @Transactional
    public ReputationResponse recalculateFromEvent(UUID targetId, TargetType targetType,
                                                   SignalOverrides overrides, String reason,
                                                   String correlationId, String causationId,
                                                   Instant eventTime) {
        Target current = store.ensureAndLock(targetId, targetType);
        if (current.lastEventAt() != null && eventTime.isBefore(current.lastEventAt())) {
            return current.toResponse();
        }
        validateOverrides(overrides);
        return recalculateLocked(current, overrides, reason(reason, "EVENT_RECALCULATION"),
            correlationId, causationId, clock.instant(), eventTime);
    }

    private ReputationResponse recalculateLocked(Target current, SignalOverrides overrides, String reason,
                                                 String correlationId, String causationId,
                                                 Instant calculatedAt, Instant eventTime) {
        BigDecimal rule = overrides.ruleSignal() == null ? current.ruleSignal() : overrides.ruleSignal();
        BigDecimal ai = overrides.aiSignal() == null ? current.aiSignal() : overrides.aiSignal();
        BigDecimal campaign = overrides.campaignSignal() == null
            ? current.campaignSignal() : overrides.campaignSignal();
        CalculationConfig config = store.config(current.targetType());
        List<com.baotin.reputation.domain.ReportContribution> contributions = store.contributions(current.threatTargetId());
        ScoreResult score = calculator.calculate(new CalculationInput(
            overrides.reportSignal(), rule, ai, campaign,
            overrides.totalReportCount(), overrides.verifiedReportCount(),
            contributions, config, calculatedAt));

        Target updated = store.update(current, score, calculatedAt, eventTime);
        boolean changed = current.finalScore().compareTo(score.finalScore()) != 0
            || current.riskLevel() != score.riskLevel();
        if (changed) {
            store.insertHistory(current, score, reason, historyComponents(score), calculatedAt);
            enqueue(updated, current, score, reason, correlationId, causationId, calculatedAt);
        }
        evictAfterCommit(current.threatTargetId());
        return updated.toResponse();
    }

    private void evictAfterCommit(UUID targetId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(targetId);
                }
            });
        } else {
            cache.evict(targetId);
        }
    }

    private void enqueue(Target updated, Target old, ScoreResult score, String reason,
                         String correlationId, String causationId, Instant changedAt) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("reportSignal", score.reportSignal());
        components.put("ruleSignal", score.ruleSignal());
        components.put("aiSignal", score.aiSignal());
        components.put("campaignSignal", score.campaignSignal());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threatTargetId", updated.threatTargetId());
        payload.put("targetType", updated.targetType().name());
        payload.put("oldScore", old.finalScore());
        payload.put("newScore", score.finalScore());
        payload.put("oldLevel", old.riskLevel().name());
        payload.put("newLevel", score.riskLevel().name());
        payload.put("confidence", score.confidence());
        payload.put("components", components);
        payload.put("calculationVersion", score.calculationVersion());
        payload.put("changedAt", changedAt.toString());
        payload.put("reason", reason);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "reputation.score.changed");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", changedAt.toString());
        envelope.put("producer", "reputation-service");
        envelope.put("aggregateType", "ThreatTarget");
        envelope.put("aggregateId", updated.threatTargetId());
        envelope.put("correlationId", normalizeCorrelation(correlationId));
        envelope.put("causationId", normalizeNullableUuid(causationId));
        envelope.put("traceId", null);
        envelope.put("payload", payload);

        try {
            outbox.enqueue(eventId, "ThreatTarget", updated.threatTargetId(),
                "reputation.score.changed", 1, topic, updated.threatTargetId().toString(),
                json.writeValueAsString(envelope), changedAt);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize reputation event", e);
        }
    }

    private String historyComponents(ScoreResult score) {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("reportSignal", score.reportSignal());
        components.put("ruleSignal", score.ruleSignal());
        components.put("aiSignal", score.aiSignal());
        components.put("campaignSignal", score.campaignSignal());
        components.put("confidence", score.confidence());
        components.put("totalReportCount", score.totalReportCount());
        components.put("verifiedReportCount", score.verifiedReportCount());
        try {
            return json.writeValueAsString(components);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize score components", e);
        }
    }

    private static SignalOverrides overrides(RecalculationRequest request) {
        RecalculationRequest.Signals signals = request.signals();
        if (signals == null) {
            return new SignalOverrides(null, null, null, null,
                request.totalReportCount(), request.verifiedReportCount());
        }
        return new SignalOverrides(signals.reportSignal(), signals.ruleSignal(),
            signals.aiSignal(), signals.campaignSignal(),
            request.totalReportCount(), request.verifiedReportCount());
    }

    private static void validateOverrides(SignalOverrides overrides) {
        if ((overrides.totalReportCount() == null) != (overrides.verifiedReportCount() == null)) {
            throw ApiFailure.badRequest("REPORT_COUNT_PAIR_REQUIRED",
                "totalReportCount and verifiedReportCount must be supplied together");
        }
        if (overrides.totalReportCount() != null
            && (overrides.totalReportCount() < 0 || overrides.verifiedReportCount() < 0
            || overrides.verifiedReportCount() > overrides.totalReportCount())) {
            throw ApiFailure.badRequest("INVALID_REPORT_COUNTS",
                "verifiedReportCount must be between 0 and totalReportCount");
        }
        validateSignal(overrides.reportSignal());
        validateSignal(overrides.ruleSignal());
        validateSignal(overrides.aiSignal());
        validateSignal(overrides.campaignSignal());
    }

    private static void validateSignal(BigDecimal signal) {
        if (signal != null && (signal.signum() < 0 || signal.compareTo(new BigDecimal("100.00")) > 0)) {
            throw ApiFailure.badRequest("INVALID_SIGNAL", "Signals must be between 0 and 100");
        }
    }

    private static TargetType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TargetType.parse(value);
        } catch (IllegalArgumentException e) {
            throw ApiFailure.badRequest("INVALID_TARGET_TYPE", "Unsupported targetType");
        }
    }

    private static String reason(String value, String fallback) {
        String reason = value == null || value.isBlank() ? fallback : value.trim();
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    private static String normalizeCorrelation(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String normalizeNullableUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
