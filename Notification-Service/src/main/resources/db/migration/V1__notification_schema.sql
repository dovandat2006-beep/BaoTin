-- Notification schema. One PostgreSQL database, schema-per-service.
-- No foreign keys or joins to auth/threat/report schemas: user and target ids are opaque.

CREATE TABLE notification.preferences (
    user_id UUID PRIMARY KEY,
    risk_change_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    report_status_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification.device_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    platform VARCHAR(16) NOT NULL CHECK (platform IN ('WEB','ANDROID','IOS')),
    token VARCHAR(512) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (token)
);
CREATE INDEX notification_device_tokens_user_idx
    ON notification.device_tokens(user_id, active);

CREATE TABLE notification.watchlist (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    url_target_id UUID NOT NULL,
    canonical_url TEXT NOT NULL,
    canonical_hash VARCHAR(64) NOT NULL,
    display_url VARCHAR(500) NOT NULL,
    host VARCHAR(253) NOT NULL,
    risk_score NUMERIC(6,2),
    previous_score NUMERIC(6,2),
    risk_level VARCHAR(20),
    previous_level VARCHAR(20),
    risk_label VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, url_target_id),
    CHECK (risk_score IS NULL OR (risk_score >= 0 AND risk_score <= 100)),
    CHECK (previous_score IS NULL OR (previous_score >= 0 AND previous_score <= 100)),
    CHECK (risk_level IS NULL OR risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CHECK (previous_level IS NULL OR previous_level IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);
CREATE INDEX notification_watchlist_hash_idx
    ON notification.watchlist(canonical_hash);
CREATE INDEX notification_watchlist_user_idx
    ON notification.watchlist(user_id, created_at DESC);

CREATE TABLE notification.notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    type VARCHAR(60) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    in_app_delivered BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ
);
CREATE INDEX notification_notifications_user_created_idx
    ON notification.notifications(user_id, created_at DESC);
CREATE INDEX notification_notifications_unread_idx
    ON notification.notifications(user_id)
    WHERE read_at IS NULL AND in_app_delivered;

CREATE TABLE notification.delivery_attempts (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notification.notifications(id) ON DELETE CASCADE,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('IN_APP','PUSH')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED','SKIPPED')),
    attempt INTEGER NOT NULL DEFAULT 0,
    device_token_id UUID REFERENCES notification.device_tokens(id) ON DELETE SET NULL,
    error TEXT,
    next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notification_delivery_pending_idx
    ON notification.delivery_attempts(status, next_attempt_at)
    WHERE status IN ('PENDING','PROCESSING');
CREATE INDEX notification_delivery_notification_idx
    ON notification.delivery_attempts(notification_id);

CREATE TABLE notification.inbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(150) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE TABLE notification.outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notification_outbox_pending_idx
    ON notification.outbox_events(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING','PROCESSING');
