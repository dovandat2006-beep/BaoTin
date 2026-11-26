package com.baotin.reputation.application;

import com.baotin.reputation.api.ApiFailure;
import com.baotin.reputation.domain.CalculationConfig;
import com.baotin.reputation.domain.ReportContribution;
import com.baotin.reputation.domain.ReportContributionStatus;
import com.baotin.reputation.domain.RiskLevel;
import com.baotin.reputation.domain.ScoreResult;
import com.baotin.reputation.domain.TargetType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReputationStore {
    private static final Logger log = LoggerFactory.getLogger(ReputationStore.class);

    private static final String SELECT_TARGET = """
        SELECT threat_target_id,target_type,report_signal,rule_signal,ai_signal,campaign_signal,
               final_score,risk_level,confidence,total_report_count,verified_report_count,
               calculation_version,calculated_at,last_event_at,version,created_at,updated_at
        FROM reputation.target_reputation
        """;

    private final JdbcTemplate jdbc;

    public ReputationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Target> find(UUID targetId) {
        return jdbc.query(SELECT_TARGET + " WHERE threat_target_id=?", TARGET_MAPPER, targetId)
            .stream().findFirst();
    }

    public Optional<Target> findForUpdate(UUID targetId) {
        return jdbc.query(SELECT_TARGET + " WHERE threat_target_id=? FOR UPDATE", TARGET_MAPPER, targetId)
            .stream().findFirst();
    }

    public Target ensureAndLock(UUID targetId, TargetType targetType) {
        jdbc.update("""
            INSERT INTO reputation.target_reputation
                (threat_target_id,target_type,report_signal,rule_signal,ai_signal,campaign_signal,
                 final_score,risk_level,confidence,calculation_version)
            VALUES (?,?,0,0,0,0,0,'LOW',0,'uncalculated')
            ON CONFLICT (threat_target_id) DO NOTHING
            """, targetId, targetType.name());
        Target target = findForUpdate(targetId)
            .orElseThrow(() -> new IllegalStateException("Target was not created"));
        if (target.targetType() != targetType) {
            throw ApiFailure.conflict("TARGET_TYPE_CONFLICT",
                "Target already belongs to " + target.targetType());
        }
        return target;
    }

    public List<ReportContribution> contributions(UUID targetId) {
        return jdbc.query("""
            SELECT report_id,threat_target_id,weight,status,occurred_at,last_event_at
            FROM reputation.report_signals
            WHERE threat_target_id=?
            ORDER BY occurred_at,report_id
            """, (rs, rowNum) -> new ReportContribution(
                rs.getObject("report_id", UUID.class),
                rs.getObject("threat_target_id", UUID.class),
                rs.getBigDecimal("weight"),
                ReportContributionStatus.valueOf(rs.getString("status")),
                instant(rs, "occurred_at"),
                instant(rs, "last_event_at")
            ), targetId);
    }

    public boolean upsertReportContribution(UUID reportId, UUID targetId, BigDecimal weight,
                                            ReportContributionStatus status, Instant occurredAt,
                                            Instant lastEventAt) {
        List<ExistingReport> existing = jdbc.query("""
            SELECT threat_target_id,last_event_at
            FROM reputation.report_signals
            WHERE report_id=?
            FOR UPDATE
            """, (rs, rowNum) -> new ExistingReport(
                rs.getObject("threat_target_id", UUID.class),
                instant(rs, "last_event_at")
            ), reportId);
        if (!existing.isEmpty()) {
            ExistingReport current = existing.getFirst();
            if (!current.targetId().equals(targetId)) {
                throw ApiFailure.conflict("REPORT_TARGET_CONFLICT",
                    "Report already belongs to another threat target");
            }
            if (lastEventAt.isBefore(current.lastEventAt())) {
                return false;
            }
        }

        jdbc.update("""
            INSERT INTO reputation.report_signals
                (report_id,threat_target_id,weight,status,occurred_at,last_event_at)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (report_id) DO UPDATE SET
                weight=EXCLUDED.weight,
                status=EXCLUDED.status,
                occurred_at=EXCLUDED.occurred_at,
                last_event_at=EXCLUDED.last_event_at,
                updated_at=now()
            """, reportId, targetId, weight, status.name(),
            Timestamp.from(occurredAt), Timestamp.from(lastEventAt));
        return true;
    }

    public CalculationConfig config(TargetType targetType) {
        List<CalculationConfig> rows = jdbc.query("""
            SELECT version,report_weight,rule_weight,ai_weight,campaign_weight,
                   report_saturation,decay_0_30,decay_31_90,decay_91_180,decay_over_180
            FROM reputation.calculation_config
            WHERE target_type=? AND active
            ORDER BY effective_from DESC
            LIMIT 1
            """, (rs, rowNum) -> new CalculationConfig(
                rs.getString("version"),
                rs.getBigDecimal("report_weight"),
                rs.getBigDecimal("rule_weight"),
                rs.getBigDecimal("ai_weight"),
                rs.getBigDecimal("campaign_weight"),
                rs.getBigDecimal("report_saturation"),
                rs.getBigDecimal("decay_0_30"),
                rs.getBigDecimal("decay_31_90"),
                rs.getBigDecimal("decay_91_180"),
                rs.getBigDecimal("decay_over_180")
            ), targetType.name());
        if (rows.isEmpty()) {
            return CalculationConfig.defaults(targetType);
        }
        CalculationConfig config = rows.getFirst();
        if (!config.valid()) {
            log.warn("Calculation config {} for {} is invalid; using defaults", config.version(), targetType);
            return CalculationConfig.defaults(targetType);
        }
        return config;
    }

    public Target update(Target current, ScoreResult score, Instant calculatedAt, Instant lastEventAt) {
        Instant effectiveLastEvent = current.lastEventAt();
        if (effectiveLastEvent == null || (lastEventAt != null && lastEventAt.isAfter(effectiveLastEvent))) {
            effectiveLastEvent = lastEventAt;
        }
        int changed = jdbc.update("""
            UPDATE reputation.target_reputation SET
                report_signal=?,rule_signal=?,ai_signal=?,campaign_signal=?,
                final_score=?,risk_level=?,confidence=?,
                total_report_count=?,verified_report_count=?,
                calculation_version=?,calculated_at=?,last_event_at=?,
                version=version+1,updated_at=now()
            WHERE threat_target_id=? AND version=?
            """,
            score.reportSignal(), score.ruleSignal(), score.aiSignal(), score.campaignSignal(),
            score.finalScore(), score.riskLevel().name(), score.confidence(),
            score.totalReportCount(), score.verifiedReportCount(),
            score.calculationVersion(), Timestamp.from(calculatedAt),
            effectiveLastEvent == null ? null : Timestamp.from(effectiveLastEvent),
            current.threatTargetId(), current.version());
        if (changed != 1) {
            throw ApiFailure.conflict("CONCURRENT_UPDATE", "Reputation changed concurrently; retry");
        }
        return new Target(current.threatTargetId(), current.targetType(),
            score.reportSignal(), score.ruleSignal(), score.aiSignal(), score.campaignSignal(),
            score.finalScore(), score.riskLevel(), score.confidence(),
            score.totalReportCount(), score.verifiedReportCount(), score.calculationVersion(),
            calculatedAt, effectiveLastEvent, current.version() + 1, current.createdAt(), calculatedAt);
    }

    public void insertHistory(Target old, ScoreResult score, String reason,
                              String componentsJson, Instant calculatedAt) {
        jdbc.update("""
            INSERT INTO reputation.score_history
                (id,threat_target_id,old_score,new_score,old_level,new_level,reason,
                 components,calculation_version,calculated_at)
            VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)
            """, UUID.randomUUID(), old.threatTargetId(), old.finalScore(), score.finalScore(),
            old.riskLevel().name(), score.riskLevel().name(), reason, componentsJson,
            score.calculationVersion(), Timestamp.from(calculatedAt));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Target> TARGET_MAPPER = (rs, rowNum) -> new Target(
        rs.getObject("threat_target_id", UUID.class),
        TargetType.valueOf(rs.getString("target_type")),
        rs.getBigDecimal("report_signal"),
        rs.getBigDecimal("rule_signal"),
        rs.getBigDecimal("ai_signal"),
        rs.getBigDecimal("campaign_signal"),
        rs.getBigDecimal("final_score"),
        RiskLevel.valueOf(rs.getString("risk_level")),
        rs.getBigDecimal("confidence"),
        rs.getInt("total_report_count"),
        rs.getInt("verified_report_count"),
        rs.getString("calculation_version"),
        instant(rs, "calculated_at"),
        instant(rs, "last_event_at"),
        rs.getLong("version"),
        instant(rs, "created_at"),
        instant(rs, "updated_at")
    );

    private record ExistingReport(UUID targetId, Instant lastEventAt) {
    }
}
