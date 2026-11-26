CREATE TABLE auth.oauth_accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id),
    provider VARCHAR(16) NOT NULL CHECK(provider IN ('GOOGLE','FACEBOOK')),
    provider_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(provider,provider_subject),
    UNIQUE(user_id,provider)
);
CREATE TABLE auth.oauth_requests (
    state_hash VARCHAR(64) PRIMARY KEY,
    provider VARCHAR(16) NOT NULL CHECK(provider IN ('GOOGLE','FACEBOOK')),
    transport VARCHAR(8) NOT NULL CHECK(transport IN ('WEB','NATIVE')),
    return_uri TEXT NOT NULL,
    client_challenge VARCHAR(43) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    encrypted_verifier BYTEA NOT NULL,
    binding_hash VARCHAR(64),
    link_user_id UUID REFERENCES auth.users(id),
    link_session_id UUID REFERENCES auth.refresh_sessions(id),
    link_security_version BIGINT,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE TABLE auth.oauth_tickets (
    ticket_hash VARCHAR(64) PRIMARY KEY,
    encrypted_payload BYTEA NOT NULL,
    client_challenge VARCHAR(43) NOT NULL,
    transport VARCHAR(8) NOT NULL CHECK(transport IN ('WEB','NATIVE')),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
ALTER TABLE auth.refresh_sessions ADD COLUMN auth_method VARCHAR(16) NOT NULL DEFAULT 'pwd';
ALTER TABLE auth.mfa_challenges ADD COLUMN auth_method VARCHAR(16) NOT NULL DEFAULT 'pwd';
