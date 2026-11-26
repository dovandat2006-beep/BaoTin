ALTER TABLE auth.mail_outbox ADD COLUMN lease_id UUID;
ALTER TABLE auth.mail_outbox ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE auth.rate_limits ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE TABLE auth.outbox_events (
    id UUID PRIMARY KEY,
    position BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    envelope JSONB NOT NULL,
    topic VARCHAR(80) NOT NULL DEFAULT 'baotin.auth.events.v1' CHECK(topic='baotin.auth.events.v1'),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_outbox_pending_idx ON auth.outbox_events(next_attempt_at,created_at) WHERE published_at IS NULL;
CREATE INDEX auth_mail_pending_idx ON auth.mail_outbox(next_attempt_at,created_at) WHERE sent_at IS NULL;
CREATE TABLE auth.security_notices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id),
    event_type VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    handled_at TIMESTAMPTZ
);
ALTER TABLE auth.mail_outbox ADD COLUMN notice_id UUID UNIQUE REFERENCES auth.security_notices(id);

CREATE FUNCTION auth.queue_user_event() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE event_name TEXT;event_names TEXT[]:='{}';event_id UUID;correlation TEXT:=current_setting('baotin.correlation_id',true);
BEGIN
    IF TG_OP='INSERT' THEN event_names:=ARRAY['user.created'];
    ELSE
    IF NEW.status IS DISTINCT FROM OLD.status THEN event_names:=array_append(event_names,'user.status.changed'); END IF;
    IF NEW.role IS DISTINCT FROM OLD.role THEN event_names:=array_append(event_names,'user.role.changed'); END IF;
    IF (NEW.username,NEW.display_name,NEW.email,NEW.email_verified,NEW.phone,NEW.phone_verified,NEW.mfa_enabled,NEW.password_hash,NEW.security_version)
        IS DISTINCT FROM (OLD.username,OLD.display_name,OLD.email,OLD.email_verified,OLD.phone,OLD.phone_verified,OLD.mfa_enabled,OLD.password_hash,OLD.security_version)
        THEN event_names:=array_append(event_names,'user.updated'); END IF;
    END IF;
    FOREACH event_name IN ARRAY event_names LOOP
    event_id:=gen_random_uuid();
    INSERT INTO auth.outbox_events(id,aggregate_id,event_type,envelope) VALUES(event_id,NEW.id,event_name,
        jsonb_build_object('eventId',event_id,'eventType',event_name,'eventVersion',1,'occurredAt',now(),
            'producer','auth-service','aggregateType','User','aggregateId',NEW.id,
            'correlationId',coalesce(nullif(correlation,''),event_id::text),'causationId',NULL,
            'payload',jsonb_build_object('userId',NEW.id,'status',NEW.status,'role',NEW.role,'securityVersion',NEW.security_version)));
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER user_event_outbox AFTER INSERT OR UPDATE ON auth.users FOR EACH ROW EXECUTE FUNCTION auth.queue_user_event();
REVOKE ALL ON FUNCTION auth.queue_user_event() FROM PUBLIC;

CREATE FUNCTION auth.queue_security_notice() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF NEW.user_id IS NOT NULL AND (NEW.event_type IN ('PASSWORD_RESET','PASSWORD_CHANGED','PASSWORD_INITIALIZED','MFA_ENABLED','MFA_DISABLED','MFA_RECOVERY_REGENERATED','OAUTH_LINKED','ROLE_CHANGED','STATUS_CHANGED','DEV_PHONE_VERIFIED','ALL_SESSIONS_REVOKED','REFRESH_REUSE_DETECTED') OR NEW.event_type LIKE 'OPERATOR_ADMIN_%') THEN
        INSERT INTO auth.security_notices(id,user_id,event_type) VALUES(NEW.id,NEW.user_id,NEW.event_type);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER audit_security_notice AFTER INSERT ON auth.audit_events FOR EACH ROW EXECUTE FUNCTION auth.queue_security_notice();
REVOKE ALL ON FUNCTION auth.queue_security_notice() FROM PUBLIC;
