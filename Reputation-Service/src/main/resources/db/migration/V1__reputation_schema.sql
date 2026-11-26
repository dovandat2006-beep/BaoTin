CREATE TABLE reputation.target_reputation (
    threat_target_id UUID PRIMARY KEY,
    target_type VARCHAR(32) NOT NULL
        CHECK (target_type IN ('URL','PHONE','BANK_ACCOUNT','EMAIL','OTHER')),
    report_signal NUMERIC(6,2) NOT NULL DEFAULT 0
        CHECK (report_signal >= 0 AND report_signal <= 100),
    rule_signal NUMERIC(6,2) NOT NULL DEFAULT 0
        CHECK (rule_signal >= 0 AND rule_signal <= 100),
    ai_signal NUMERIC(6,2) NOT NULL DEFAULT 0
        CHECK (ai_signal >= 0 AND ai_signal <= 100),
    campaign_signal NUMERIC(6,2) NOT NULL DEFAULT 0
        CHECK (campaign_signal >= 0 AND campaign_signal <= 100),
    final_score NUMERIC(6,2) NOT NULL DEFAULT 0
        CHECK (final_score >= 0 AND final_score <= 100),
    risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW'
        CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    confidence NUMERIC(6,5) NOT NULL DEFAULT 0
        CHECK (confidence >= 0 AND confidence <= 1),
    total_report_count INTEGER NOT NULL DEFAULT 0 CHECK (total_report_count >= 0),
    verified_report_count INTEGER NOT NULL DEFAULT 0 CHECK (verified_report_count >= 0),
    calculation_version VARCHAR(30) NOT NULL DEFAULT 'uncalculated',
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_event_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (verified_report_count <= total_report_count)
);

CREATE TABLE reputation.report_signals (
    report_id UUID PRIMARY KEY,
    threat_target_id UUID NOT NULL,
    weight NUMERIC(6,2) NOT NULL CHECK (weight >= 0 AND weight <= 100),
    status VARCHAR(16) NOT NULL CHECK (status IN ('VERIFIED','REVOKED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    last_event_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reputation_report_signals_target_idx
    ON reputation.report_signals(threat_target_id, status);

CREATE TABLE reputation.score_history (
    id UUID PRIMARY KEY,
    threat_target_id UUID NOT NULL,
    old_score NUMERIC(6,2) NOT NULL,
    new_score NUMERIC(6,2) NOT NULL,
    old_level VARCHAR(20) NOT NULL,
    new_level VARCHAR(20) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    components JSONB NOT NULL,
    calculation_version VARCHAR(30) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX reputation_score_history_target_idx
    ON reputation.score_history(threat_target_id, calculated_at DESC);

CREATE TABLE reputation.calculation_config (
    target_type VARCHAR(32) NOT NULL
        CHECK (target_type IN ('URL','PHONE','BANK_ACCOUNT','EMAIL','OTHER')),
    version VARCHAR(30) NOT NULL,
    report_weight NUMERIC(5,4) NOT NULL CHECK (report_weight >= 0 AND report_weight <= 1),
    rule_weight NUMERIC(5,4) NOT NULL CHECK (rule_weight >= 0 AND rule_weight <= 1),
    ai_weight NUMERIC(5,4) NOT NULL CHECK (ai_weight >= 0 AND ai_weight <= 1),
    campaign_weight NUMERIC(5,4) NOT NULL CHECK (campaign_weight >= 0 AND campaign_weight <= 1),
    report_saturation NUMERIC(8,2) NOT NULL DEFAULT 100 CHECK (report_saturation > 0),
    decay_0_30 NUMERIC(4,3) NOT NULL DEFAULT 1.000 CHECK (decay_0_30 >= 0),
    decay_31_90 NUMERIC(4,3) NOT NULL DEFAULT 0.800 CHECK (decay_31_90 >= 0),
    decay_91_180 NUMERIC(4,3) NOT NULL DEFAULT 0.600 CHECK (decay_91_180 >= 0),
    decay_over_180 NUMERIC(4,3) NOT NULL DEFAULT 0.400 CHECK (decay_over_180 >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (target_type, version),
    CHECK (report_weight + rule_weight + ai_weight + campaign_weight BETWEEN 0.9999 AND 1.0001)
);

INSERT INTO reputation.calculation_config
    (target_type, version, report_weight, rule_weight, ai_weight, campaign_weight,
     report_saturation, decay_0_30, decay_31_90, decay_91_180, decay_over_180, active)
VALUES
    ('URL', 'v1', 0.3500, 0.2500, 0.2500, 0.1500, 100, 1.000, 0.800, 0.600, 0.400, TRUE),
    ('OTHER', 'v1', 0.3500, 0.2500, 0.2500, 0.1500, 100, 1.000, 0.800, 0.600, 0.400, TRUE),
    ('PHONE', 'v1', 0.5500, 0.2000, 0.0000, 0.2500, 100, 1.000, 0.800, 0.600, 0.400, TRUE),
    ('BANK_ACCOUNT', 'v1', 0.5500, 0.2000, 0.0000, 0.2500, 100, 1.000, 0.800, 0.600, 0.400, TRUE),
    ('EMAIL', 'v1', 0.5500, 0.2000, 0.0000, 0.2500, 100, 1.000, 0.800, 0.600, 0.400, TRUE)
ON CONFLICT (target_type, version) DO NOTHING;

CREATE TABLE reputation.inbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(150) NOT NULL,
    consumer VARCHAR(100) NOT NULL DEFAULT 'reputation-service-v1',
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    payload_hash VARCHAR(64)
);

CREATE TABLE reputation.outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reputation_outbox_pending_idx
    ON reputation.outbox_events(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING','PROCESSING');
