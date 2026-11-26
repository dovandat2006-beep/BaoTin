-- Additive: V1 URL IDs and public response shape remain readable during rollout.
ALTER TABLE search.search_documents ADD COLUMN reference_id uuid;
UPDATE search.search_documents SET reference_id=id;
-- Preserve V1 writers during a rolling upgrade/rollback: their ID is the target reference.
CREATE FUNCTION search.fill_legacy_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.reference_id IS NULL THEN NEW.reference_id := NEW.id; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER fill_legacy_search_reference BEFORE INSERT ON search.search_documents
 FOR EACH ROW EXECUTE FUNCTION search.fill_legacy_reference();
ALTER TABLE search.search_documents ALTER COLUMN reference_id SET NOT NULL;
ALTER TABLE search.search_documents ADD COLUMN status varchar(20) NOT NULL DEFAULT 'ACTIVE'
 CHECK (status IN ('ACTIVE','REMOVED'));
ALTER TABLE search.search_documents ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}'::jsonb
 CHECK (jsonb_typeof(metadata)='object');
ALTER TABLE search.search_documents ADD COLUMN lookup_hash varchar(64)
 CHECK (lookup_hash IS NULL OR lookup_hash ~ '^[a-f0-9]{64}$');
ALTER TABLE search.search_documents ADD CONSTRAINT search_document_type
 CHECK (document_type IN ('URL','PHONE','BANK_ACCOUNT','EMAIL','CAMPAIGN','OTHER'));
CREATE UNIQUE INDEX search_document_reference ON search.search_documents(document_type,reference_id);
CREATE INDEX search_document_lookup ON search.search_documents(lookup_hash) WHERE lookup_hash IS NOT NULL;
