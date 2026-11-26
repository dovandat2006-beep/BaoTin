CREATE FUNCTION auth.operator_retry_delivery(queue_kind TEXT,target UUID,reason_text TEXT) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE user_ref UUID;changed INTEGER;
BEGIN
    IF reason_text IS NULL OR length(trim(reason_text))<8 OR length(reason_text)>500 THEN RAISE EXCEPTION 'audit reason required' USING ERRCODE='22023'; END IF;
    IF queue_kind='EVENT' THEN
        SELECT aggregate_id INTO user_ref FROM auth.outbox_events WHERE id=target FOR UPDATE;
        UPDATE auth.outbox_events SET attempts=0,next_attempt_at=now(),lease_id=NULL,lease_until=NULL
            WHERE id=target AND published_at IS NULL AND attempts>=10 AND (lease_until IS NULL OR lease_until<now());
    ELSIF queue_kind='MAIL' THEN
        SELECT n.user_id INTO user_ref FROM auth.mail_outbox m LEFT JOIN auth.security_notices n ON n.id=m.notice_id WHERE m.id=target FOR UPDATE OF m;
        UPDATE auth.mail_outbox SET attempts=0,next_attempt_at=now(),lease_id=NULL,lease_until=NULL
            WHERE id=target AND sent_at IS NULL AND attempts>=5 AND created_at>now()-interval '15 minutes' AND (lease_until IS NULL OR lease_until<now());
    ELSE RAISE EXCEPTION 'invalid queue kind' USING ERRCODE='22023';
    END IF;
    GET DIAGNOSTICS changed=ROW_COUNT;
    IF changed<>1 THEN RAISE EXCEPTION 'only exhausted unleased pending delivery can retry' USING ERRCODE='42501'; END IF;
    INSERT INTO auth.audit_events(id,user_id,event_type,reason) VALUES(gen_random_uuid(),user_ref,'OPERATOR_DELIVERY_RETRY_'||queue_kind,reason_text);
END $$;
REVOKE ALL ON FUNCTION auth.operator_retry_delivery(TEXT,UUID,TEXT) FROM PUBLIC;
