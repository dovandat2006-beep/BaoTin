-- Additive queue-order index. IF NOT EXISTS also admits a verified prebuilt index.
CREATE INDEX IF NOT EXISTS idx_outbox_pending_created_at
 ON threat.outbox_events(created_at,id) WHERE status='PENDING';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_index WHERE indexrelid='threat.idx_outbox_pending_created_at'::regclass
   AND indisvalid AND indisready AND indrelid='threat.outbox_events'::regclass
   AND indnkeyatts=2 AND pg_get_indexdef(indexrelid,1,true)='created_at'
   AND pg_get_indexdef(indexrelid,2,true)='id' AND indpred IS NOT NULL)
 THEN RAISE EXCEPTION 'Pending Outbox index must be valid and ordered'; END IF;
END $$;
