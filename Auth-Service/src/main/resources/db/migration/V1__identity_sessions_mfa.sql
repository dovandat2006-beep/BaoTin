CREATE TABLE auth.users (
    id UUID PRIMARY KEY,
    username VARCHAR(30) NOT NULL UNIQUE,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash TEXT,
    display_name VARCHAR(120) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','MODERATOR','ADMIN')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DISABLED','DELETED')),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    security_version BIGINT NOT NULL DEFAULT 0,
    mfa_secret BYTEA,
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_totp_step BIGINT NOT NULL DEFAULT -1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE auth.refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    mfa_authenticated BOOLEAN NOT NULL DEFAULT FALSE,
    auth_time TIMESTAMPTZ NOT NULL
);
CREATE INDEX refresh_sessions_user_idx ON auth.refresh_sessions(user_id);
CREATE TABLE auth.refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth.refresh_sessions(id),
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX refresh_tokens_session_idx ON auth.refresh_tokens(session_id);
CREATE TABLE auth.mfa_challenges (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id),
    security_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ
);
CREATE TABLE auth.mfa_enrollments (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id),
    encrypted_secret BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE auth.recovery_codes (
    user_id UUID NOT NULL REFERENCES auth.users(id),
    code_hash VARCHAR(64) NOT NULL,
    consumed_at TIMESTAMPTZ,
    PRIMARY KEY(user_id, code_hash)
);
CREATE TABLE auth.verification_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id),
    type VARCHAR(16) NOT NULL CHECK(type IN ('VERIFY_EMAIL','RESET_PASSWORD')),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE TABLE auth.mail_outbox (
    id UUID PRIMARY KEY,
    encrypted_payload BYTEA NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE auth.audit_events (
    id UUID PRIMARY KEY,
    user_id UUID,
    event_type VARCHAR(80) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
