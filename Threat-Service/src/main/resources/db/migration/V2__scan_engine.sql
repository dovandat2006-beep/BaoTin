-- Preserve the already published report-target V1; extend it without dropping IDs.
ALTER TABLE threat.targets ADD COLUMN normalized_value TEXT;
ALTER TABLE threat.targets ADD COLUMN display_preview VARCHAR(500);
ALTER TABLE threat.targets ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE threat.targets ADD COLUMN first_seen_at TIMESTAMPTZ;
ALTER TABLE threat.targets ADD COLUMN last_seen_at TIMESTAMPTZ;
ALTER TABLE threat.targets ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE threat.targets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE threat.targets SET normalized_value=normalized_url,
 display_preview=CASE WHEN type='URL' THEN LEFT(normalized_url,500) ELSE 'Message template #' || LEFT(canonical_hash,12) END,
 first_seen_at=created_at,last_seen_at=created_at,updated_at=created_at;
ALTER TABLE threat.targets ALTER COLUMN display_preview SET NOT NULL;
ALTER TABLE threat.targets ALTER COLUMN first_seen_at SET NOT NULL;
ALTER TABLE threat.targets ALTER COLUMN last_seen_at SET NOT NULL;
ALTER TABLE threat.targets ALTER COLUMN updated_at SET NOT NULL;

CREATE TABLE threat.scan_history (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    scan_type VARCHAR(20) NOT NULL CHECK (scan_type IN ('TEXT', 'URL')),
    target_id UUID REFERENCES threat.targets(id),
    input_hash VARCHAR(128) NOT NULL,
    content_preview VARCHAR(300) NOT NULL,
    encrypted_content BYTEA,
    model_label VARCHAR(80),
    model_score NUMERIC(8,6),
    model_name VARCHAR(100),
    model_version VARCHAR(50),
    rule_score NUMERIC(6,2) NOT NULL,
    reputation_score NUMERIC(6,2),
    final_score NUMERIC(6,2) NOT NULL CHECK (final_score BETWEEN 0 AND 100),
    risk_level VARCHAR(20) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    embedded_url_count INTEGER NOT NULL DEFAULT 0,
    result_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    content_expires_at TIMESTAMPTZ,
    CHECK ((encrypted_content IS NULL) OR (scan_type = 'TEXT' AND content_expires_at IS NOT NULL))
);
CREATE INDEX idx_scan_user_created ON threat.scan_history(user_id, created_at DESC, id DESC);
CREATE INDEX idx_scan_content_expiry ON threat.scan_history(content_expires_at) WHERE encrypted_content IS NOT NULL;

CREATE TABLE threat.scan_urls (
    id UUID PRIMARY KEY,
    scan_id UUID NOT NULL REFERENCES threat.scan_history(id) ON DELETE CASCADE,
    url_target_id UUID NOT NULL REFERENCES threat.targets(id),
    raw_url_hash VARCHAR(128) NOT NULL,
    model_label VARCHAR(80),
    model_score NUMERIC(8,6),
    final_score NUMERIC(6,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_scan_urls_scan ON threat.scan_urls(scan_id);

CREATE TABLE threat.outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_outbox_pending ON threat.outbox_events(status, next_attempt_at, created_at);

CREATE TABLE threat.rate_limits (
    bucket_key VARCHAR(128) NOT NULL,
    window_id BIGINT NOT NULL,
    attempts INTEGER NOT NULL,
    PRIMARY KEY (bucket_key, window_id)
);
