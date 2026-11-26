-- Preserve V1 columns and use sms_scans as the canonical text scan counter.
ALTER TABLE analytics.daily_metrics ADD COLUMN reports_need_more_info BIGINT NOT NULL DEFAULT 0;
ALTER TABLE analytics.daily_metrics ADD COLUMN risk_low_scans BIGINT NOT NULL DEFAULT 0;
ALTER TABLE analytics.daily_metrics ADD COLUMN risk_medium_scans BIGINT NOT NULL DEFAULT 0;
ALTER TABLE analytics.daily_metrics ADD COLUMN risk_high_scans BIGINT NOT NULL DEFAULT 0;
ALTER TABLE analytics.daily_metrics ADD COLUMN risk_critical_scans BIGINT NOT NULL DEFAULT 0;
ALTER TABLE analytics.daily_metrics ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE analytics.inbox_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX analytics_inbox_topic_processed_idx
    ON analytics.inbox_events (topic, processed_at);

CREATE TABLE analytics.processed_scans (
    scan_id UUID PRIMARY KEY,
    metric_date DATE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analytics.known_targets (
    target_id UUID PRIMARY KEY,
    created_date DATE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analytics.report_lifecycle_state (
    report_id UUID PRIMARY KEY,
    target_id UUID,
    last_version INTEGER NOT NULL,
    last_status VARCHAR(32) NOT NULL,
    last_event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_recorded BOOLEAN NOT NULL DEFAULT FALSE,
    created_date DATE,
    verified_recorded BOOLEAN NOT NULL DEFAULT FALSE,
    verified_date DATE,
    rejected_recorded BOOLEAN NOT NULL DEFAULT FALSE,
    rejected_date DATE,
    need_more_info_recorded BOOLEAN NOT NULL DEFAULT FALSE,
    need_more_info_date DATE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analytics.target_reputation_view (
    target_id UUID PRIMARY KEY,
    risk_level VARCHAR(16) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    last_event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analytics.backfill_checkpoints (
    run_id UUID PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL CHECK (source_type IN ('FILE_NDJSON', 'KAFKA_REPLAY')),
    source_ref VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    last_cursor BIGINT NOT NULL DEFAULT 0,
    processed_count BIGINT NOT NULL DEFAULT 0,
    applied_count BIGINT NOT NULL DEFAULT 0,
    skipped_count BIGINT NOT NULL DEFAULT 0,
    batch_size INTEGER NOT NULL CHECK (batch_size BETWEEN 1 AND 1000),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE analytics.backfill_partition_checkpoints (
    run_id UUID NOT NULL REFERENCES analytics.backfill_checkpoints(run_id),
    topic VARCHAR(128) NOT NULL,
    partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
    next_offset BIGINT NOT NULL CHECK (next_offset >= 0),
    end_offset BIGINT NOT NULL CHECK (end_offset >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, topic, partition_id)
);
