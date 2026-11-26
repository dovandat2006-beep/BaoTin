ALTER TABLE auth.users ADD COLUMN email_mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE auth.users ADD CONSTRAINT email_mfa_user_only CHECK (
  NOT email_mfa_enabled OR (role='USER' AND email IS NOT NULL AND email_verified
    AND password_hash IS NOT NULL AND NOT mfa_enabled));
CREATE TABLE auth.email_mfa_challenges (
  token_hash VARCHAR(64) PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES auth.users(id),
  session_id UUID REFERENCES auth.refresh_sessions(id),
  security_version BIGINT NOT NULL,
  email_snapshot VARCHAR(320) NOT NULL,
  purpose VARCHAR(24) NOT NULL CHECK(purpose IN ('ENROLL','LOGIN','CHANGE_PASSWORD','DISABLE_MFA','STEP_UP')),
  transport VARCHAR(8) CHECK(transport IN ('NATIVE','WEB')),
  auth_method VARCHAR(16),
  code_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
  deliveries INTEGER NOT NULL DEFAULT 1 CHECK(deliveries BETWEEN 1 AND 3),
  last_sent_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX email_mfa_owner_idx ON auth.email_mfa_challenges(user_id,expires_at);
REVOKE ALL ON auth.email_mfa_challenges FROM PUBLIC;
UPDATE auth.runtime_metadata SET value='10' WHERE name='schemaRevision';
-- Reader1 would ignore email mode, so it is not a valid post-migration rollback.
UPDATE auth.runtime_metadata SET value='2' WHERE name='readerFloor';
