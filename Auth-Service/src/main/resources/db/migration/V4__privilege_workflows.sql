CREATE TABLE auth.pending_privileges (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id),
    requested_role VARCHAR(16) NOT NULL CHECK(requested_role='ADMIN'),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE auth.audit_events ADD COLUMN actor_id UUID;
ALTER TABLE auth.audit_events ADD COLUMN reason VARCHAR(500);
ALTER TABLE auth.refresh_sessions ADD COLUMN step_up_at TIMESTAMPTZ;

-- Functions are owned by the migration role; runtime can only execute the bounded
-- USER/MODERATOR transition. ADMIN changes are isolated to operator credentials.
CREATE FUNCTION auth.set_managed_role(actor UUID,target UUID,new_role TEXT) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM id FROM auth.users WHERE id IN(actor,target) ORDER BY id FOR UPDATE;
    IF new_role NOT IN ('USER','MODERATOR') OR actor=target THEN
        RAISE EXCEPTION 'role transition denied' USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor AND role='ADMIN' AND status='ACTIVE' AND mfa_enabled AND email_verified) THEN
        RAISE EXCEPTION 'actor role denied' USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM auth.users WHERE id=target AND role<>'ADMIN' AND status='ACTIVE'
        AND (new_role='USER' OR (mfa_enabled AND email_verified))) THEN
        RAISE EXCEPTION 'target role denied' USING ERRCODE='42501';
    END IF;
    UPDATE auth.users SET role=new_role,security_version=security_version+1 WHERE id=target;
    UPDATE auth.refresh_sessions SET revoked_at=now() WHERE user_id=target AND revoked_at IS NULL;
    INSERT INTO auth.audit_events(id,user_id,actor_id,event_type) VALUES(gen_random_uuid(),target,actor,'ROLE_CHANGED');
END $$;
REVOKE ALL ON FUNCTION auth.set_managed_role(UUID,UUID,TEXT) FROM PUBLIC;

CREATE FUNCTION auth.set_managed_status(actor UUID,target UUID,new_status TEXT) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM id FROM auth.users WHERE id IN(actor,target) ORDER BY id FOR UPDATE;
    IF new_status NOT IN ('ACTIVE','LOCKED','DISABLED') OR actor=target THEN
        RAISE EXCEPTION 'status transition denied' USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor AND role='ADMIN' AND status='ACTIVE' AND mfa_enabled AND email_verified)
        OR NOT EXISTS(SELECT 1 FROM auth.users WHERE id=target AND role<>'ADMIN') THEN
        RAISE EXCEPTION 'status transition denied' USING ERRCODE='42501';
    END IF;
    UPDATE auth.users SET status=new_status,security_version=security_version+1 WHERE id=target;
    UPDATE auth.refresh_sessions SET revoked_at=now() WHERE user_id=target AND revoked_at IS NULL;
    INSERT INTO auth.audit_events(id,user_id,actor_id,event_type) VALUES(gen_random_uuid(),target,actor,'STATUS_CHANGED');
END $$;
REVOKE ALL ON FUNCTION auth.set_managed_status(UUID,UUID,TEXT) FROM PUBLIC;

CREATE FUNCTION auth.operator_admin(action TEXT,target UUID,reason_text TEXT) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE current_user_role TEXT;
BEGIN
    IF reason_text IS NULL OR length(trim(reason_text))<8 OR length(reason_text)>500 THEN
        RAISE EXCEPTION 'reason required' USING ERRCODE='22023';
    END IF;
    SELECT role INTO current_user_role FROM auth.users WHERE id=target FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'user missing' USING ERRCODE='22023'; END IF;
    IF action='request' THEN
        IF current_user_role<>'USER' THEN RAISE EXCEPTION 'expected user account' USING ERRCODE='42501'; END IF;
        INSERT INTO auth.pending_privileges(user_id,requested_role,reason) VALUES(target,'ADMIN',reason_text)
            ON CONFLICT(user_id) DO UPDATE SET reason=excluded.reason,created_at=now();
    ELSIF action='activate' THEN
        IF NOT EXISTS(SELECT 1 FROM auth.pending_privileges WHERE user_id=target)
            OR NOT EXISTS(SELECT 1 FROM auth.users WHERE id=target AND role='USER' AND status='ACTIVE' AND email_verified AND mfa_enabled) THEN
            RAISE EXCEPTION 'verified active user with confirmed TOTP required' USING ERRCODE='42501';
        END IF;
        UPDATE auth.users SET role='ADMIN',security_version=security_version+1 WHERE id=target;
        DELETE FROM auth.pending_privileges WHERE user_id=target;
    ELSIF action='recover' THEN
        IF current_user_role<>'ADMIN' THEN RAISE EXCEPTION 'expected admin account' USING ERRCODE='42501'; END IF;
        UPDATE auth.users SET role='USER',status='ACTIVE',mfa_enabled=false,mfa_secret=NULL,last_totp_step=-1,security_version=security_version+1 WHERE id=target;
        DELETE FROM auth.recovery_codes WHERE user_id=target;
        DELETE FROM auth.mfa_enrollments WHERE user_id=target;
        INSERT INTO auth.pending_privileges(user_id,requested_role,reason) VALUES(target,'ADMIN',reason_text)
            ON CONFLICT(user_id) DO UPDATE SET reason=excluded.reason,created_at=now();
    ELSIF action='revoke' THEN
        IF current_user_role<>'ADMIN' THEN RAISE EXCEPTION 'expected admin account' USING ERRCODE='42501'; END IF;
        UPDATE auth.users SET role='USER',security_version=security_version+1 WHERE id=target;
        DELETE FROM auth.pending_privileges WHERE user_id=target;
    ELSE RAISE EXCEPTION 'unsupported operation' USING ERRCODE='22023';
    END IF;
    UPDATE auth.refresh_sessions SET revoked_at=now() WHERE user_id=target AND revoked_at IS NULL;
    INSERT INTO auth.audit_events(id,user_id,event_type,reason) VALUES(gen_random_uuid(),target,'OPERATOR_ADMIN_'||upper(action),reason_text);
END $$;
REVOKE ALL ON FUNCTION auth.operator_admin(TEXT,UUID,TEXT) FROM PUBLIC;
