CREATE TABLE search.projection_journal (
 sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 document_id uuid NOT NULL, deleted boolean NOT NULL,
 snapshot jsonb NOT NULL, changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION search.journal_projection_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  INSERT INTO search.projection_journal(document_id,deleted,snapshot) VALUES(OLD.id,true,to_jsonb(OLD)-'search_vector');
  RETURN OLD;
 END IF;
 INSERT INTO search.projection_journal(document_id,deleted,snapshot) VALUES(NEW.id,false,to_jsonb(NEW)-'search_vector');
 RETURN NEW;
END;
$$;
CREATE TRIGGER journal_search_projection AFTER INSERT OR UPDATE OR DELETE ON search.search_documents
 FOR EACH ROW EXECUTE FUNCTION search.journal_projection_change();
CREATE TABLE search.rebuild_runs (
 id uuid PRIMARY KEY, status varchar(20) NOT NULL CHECK(status IN ('BUILDING','READY','COMPLETED','CANCELLED')),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(), capture_sequence bigint NOT NULL,
 manifest_hash varchar(64), file_hash varchar(64), record_count bigint,
 checkpoint bigint NOT NULL DEFAULT 0, caught_up_sequence bigint,
 completed_at timestamptz
);
CREATE UNIQUE INDEX search_one_pending_rebuild ON search.rebuild_runs((true)) WHERE status IN ('BUILDING','READY');
CREATE TABLE search.rebuild_documents (LIKE search.search_documents INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING CONSTRAINTS);
ALTER TABLE search.rebuild_documents ADD COLUMN run_id uuid NOT NULL REFERENCES search.rebuild_runs(id);
ALTER TABLE search.rebuild_documents ADD PRIMARY KEY(run_id,id);
CREATE UNIQUE INDEX search_rebuild_reference ON search.rebuild_documents(run_id,document_type,reference_id);
CREATE TABLE search.rebuild_inbox (
 run_id uuid NOT NULL REFERENCES search.rebuild_runs(id), event_id uuid NOT NULL,
 PRIMARY KEY(run_id,event_id)
);
-- The trigger must also work for the previous V1 runtime during a rolling upgrade.
-- No CREATE/UPDATE/DELETE or foreign-schema rights are granted to runtime.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='baotin_search') THEN
  GRANT SELECT,INSERT ON search.projection_journal TO baotin_search;
  GRANT USAGE ON SEQUENCE search.projection_journal_sequence_seq TO baotin_search;
 END IF;
END $$;
