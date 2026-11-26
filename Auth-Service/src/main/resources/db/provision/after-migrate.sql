-- Run with provisioning credentials after Flyway. Grants are explicit, not
-- ALL TABLES/default privileges, so new migrations fail until reviewed here.
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='auth' AND r.rolname IN ('baotin_auth','baotin_auth_operator')) THEN
        RAISE EXCEPTION 'runtime/operator must not own auth objects; migrate ownership first';
    END IF;
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA auth FROM baotin_auth,baotin_auth_operator;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA auth FROM PUBLIC,baotin_auth,baotin_auth_operator;
REVOKE ALL ON SCHEMA auth FROM baotin_auth,baotin_auth_operator;
GRANT USAGE ON SCHEMA auth TO baotin_auth,baotin_auth_operator;
GRANT SELECT ON auth.users TO baotin_auth;
GRANT SELECT ON auth.runtime_metadata TO baotin_auth;
GRANT INSERT(id,username,email,password_hash,display_name) ON auth.users TO baotin_auth;
GRANT UPDATE(password_hash,display_name,email_verified,security_version,mfa_secret,mfa_enabled,last_totp_step) ON auth.users TO baotin_auth;
GRANT INSERT(phone) ON auth.users TO baotin_auth;
GRANT UPDATE(phone,phone_verified) ON auth.users TO baotin_auth;
GRANT SELECT,INSERT,UPDATE ON auth.refresh_sessions,auth.refresh_tokens,auth.mfa_challenges,auth.verification_tokens TO baotin_auth;
GRANT SELECT,INSERT,UPDATE,DELETE ON auth.mfa_enrollments,auth.recovery_codes,auth.mail_outbox,auth.rate_limits TO baotin_auth;
GRANT SELECT,INSERT ON auth.oauth_accounts TO baotin_auth;
GRANT SELECT,INSERT,UPDATE,DELETE ON auth.oauth_requests,auth.oauth_tickets TO baotin_auth;
GRANT SELECT,INSERT,UPDATE ON auth.phone_challenges TO baotin_auth;
GRANT SELECT,INSERT ON auth.phone_proofs TO baotin_auth;
GRANT SELECT ON auth.security_notices TO baotin_auth;
GRANT UPDATE(handled_at) ON auth.security_notices TO baotin_auth;
GRANT SELECT ON auth.outbox_events TO baotin_auth;
GRANT UPDATE(attempts,next_attempt_at,published_at,lease_id,lease_until) ON auth.outbox_events TO baotin_auth;
GRANT INSERT(id,user_id,event_type,occurred_at) ON auth.audit_events TO baotin_auth;
GRANT EXECUTE ON FUNCTION auth.set_managed_role(UUID,UUID,TEXT),auth.set_managed_status(UUID,UUID,TEXT) TO baotin_auth;
GRANT EXECUTE ON FUNCTION auth.operator_admin(TEXT,UUID,TEXT) TO baotin_auth_operator;
GRANT EXECUTE ON FUNCTION auth.operator_retry_delivery(TEXT,UUID,TEXT) TO baotin_auth_operator;
DO $$ BEGIN
    IF to_regclass('auth.email_mfa_challenges') IS NOT NULL THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON auth.email_mfa_challenges TO baotin_auth;
        GRANT UPDATE(email_mfa_enabled) ON auth.users TO baotin_auth;
    END IF;
END $$;
DO $$ DECLARE db TEXT:=current_database(); BEGIN
    EXECUTE format('REVOKE CREATE,TEMPORARY ON DATABASE %I FROM PUBLIC',db);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO baotin_auth,baotin_auth_migrator,baotin_auth_operator',db);
END $$;
