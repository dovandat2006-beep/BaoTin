ALTER TABLE auth.users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE auth.users ADD COLUMN phone VARCHAR(20) UNIQUE;
ALTER TABLE auth.users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE auth.users ADD CONSTRAINT user_contact_required CHECK(email IS NOT NULL OR phone IS NOT NULL);
ALTER TABLE auth.users ADD CONSTRAINT phone_e164 CHECK(phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$');
CREATE TABLE auth.phone_challenges (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id),
    session_id UUID NOT NULL REFERENCES auth.refresh_sessions(id),
    security_version BIGINT NOT NULL,
    phone VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ
);
CREATE TABLE auth.phone_proofs (
    project_id VARCHAR(128) NOT NULL,
    firebase_uid VARCHAR(128) NOT NULL,
    auth_time BIGINT NOT NULL,
    user_id UUID NOT NULL REFERENCES auth.users(id),
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(project_id,firebase_uid,auth_time)
);
