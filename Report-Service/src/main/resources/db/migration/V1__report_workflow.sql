CREATE SCHEMA IF NOT EXISTS report;
CREATE TABLE report.reports (
 id UUID PRIMARY KEY, reporter_user_id UUID NOT NULL, target_id UUID NOT NULL,
 target_type VARCHAR(30) NOT NULL, target_display VARCHAR(500) NOT NULL,
 category VARCHAR(50) NOT NULL, title VARCHAR(200) NOT NULL, description VARCHAR(10000) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE, status VARCHAR(40) NOT NULL,
 priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM', moderator_user_id UUID,
 moderator_note VARCHAR(2000), reviewed_at TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT report_status CHECK(status IN ('SUBMITTED','PENDING_REVIEW','NEED_MORE_INFO','VERIFIED','REJECTED')),
 CONSTRAINT report_type CHECK(target_type IN ('URL','MESSAGE_TEMPLATE')),
 CONSTRAINT report_category CHECK(category IN ('SUSPICIOUS_MESSAGE','PHISHING_URL','SPAM','OTHER'))
);
CREATE INDEX idx_report_status_created ON report.reports(status, created_at DESC);
CREATE INDEX idx_report_user_created ON report.reports(reporter_user_id, created_at DESC);
CREATE TABLE report.evidence (
 id UUID PRIMARY KEY, report_id UUID NOT NULL REFERENCES report.reports(id),
 object_key VARCHAR(500) NOT NULL UNIQUE, original_filename VARCHAR(200) NOT NULL,
 mime_type VARCHAR(80) NOT NULL, size_bytes BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, deleted_at TIMESTAMP WITH TIME ZONE, purged_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_evidence_report ON report.evidence(report_id);
CREATE TABLE report.status_history (
 id UUID PRIMARY KEY, report_id UUID NOT NULL REFERENCES report.reports(id),
 from_status VARCHAR(40), to_status VARCHAR(40) NOT NULL, changed_by_user_id UUID NOT NULL,
 note VARCHAR(2000), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE report.idempotency_keys (
 user_id UUID NOT NULL, idempotency_key UUID NOT NULL, request_hash VARCHAR(64) NOT NULL,
 report_id UUID, response_body TEXT, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id, idempotency_key)
);
CREATE TABLE report.outbox_events (
 id UUID PRIMARY KEY, aggregate_id UUID NOT NULL, event_type VARCHAR(80) NOT NULL,
 payload TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE, attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL, last_error VARCHAR(100)
);
CREATE INDEX idx_outbox_pending ON report.outbox_events(published_at, next_attempt_at, created_at);
