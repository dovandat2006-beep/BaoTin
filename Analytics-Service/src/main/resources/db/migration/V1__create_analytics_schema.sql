CREATE TABLE analytics.moderation_queue_item (
    id UUID PRIMARY KEY,
    target_type VARCHAR(32) NOT NULL,
    target VARCHAR(512) NOT NULL,
    category VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('SUBMITTED', 'PENDING_REVIEW', 'NEED_MORE_INFO', 'VERIFIED', 'REJECTED')),
    risk_level VARCHAR(16) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    priority VARCHAR(16) NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_count INTEGER NOT NULL CHECK (evidence_count >= 0),
    moderator_note VARCHAR(2000),
    resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX moderation_queue_status_submitted_idx
    ON analytics.moderation_queue_item (status, submitted_at);
CREATE INDEX moderation_queue_priority_submitted_idx
    ON analytics.moderation_queue_item (priority, submitted_at);
CREATE INDEX moderation_queue_risk_submitted_idx
    ON analytics.moderation_queue_item (risk_level, submitted_at);

CREATE TABLE analytics.daily_metrics (
    metric_date DATE PRIMARY KEY,
    total_scans BIGINT NOT NULL DEFAULT 0,
    sms_scans BIGINT NOT NULL DEFAULT 0,
    url_scans BIGINT NOT NULL DEFAULT 0,
    high_risk_scans BIGINT NOT NULL DEFAULT 0,
    reports_created BIGINT NOT NULL DEFAULT 0,
    reports_verified BIGINT NOT NULL DEFAULT 0,
    reports_rejected BIGINT NOT NULL DEFAULT 0,
    new_threats BIGINT NOT NULL DEFAULT 0,
    active_users BIGINT NOT NULL DEFAULT 0
);
