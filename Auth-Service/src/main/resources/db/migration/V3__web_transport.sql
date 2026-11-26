ALTER TABLE auth.refresh_sessions ADD COLUMN transport VARCHAR(8) NOT NULL DEFAULT 'NATIVE' CHECK (transport IN ('WEB','NATIVE'));
ALTER TABLE auth.refresh_sessions ADD COLUMN csrf_hash VARCHAR(64);
ALTER TABLE auth.mfa_challenges ADD COLUMN transport VARCHAR(8) NOT NULL DEFAULT 'NATIVE' CHECK (transport IN ('WEB','NATIVE'));
