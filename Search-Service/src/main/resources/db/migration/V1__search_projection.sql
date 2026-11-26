-- Extensions must be provisioned by an operator, never the runtime role.
CREATE TABLE search.search_documents (
 id uuid PRIMARY KEY, document_type varchar(40) NOT NULL DEFAULT 'URL',
 title varchar(253), display_value varchar(253), search_text text,
 risk_score double precision CHECK (risk_score BETWEEN 0 AND 100),
 risk_level varchar(20) CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 target_at timestamptz, target_event uuid, risk_at timestamptz, risk_event uuid,
 updated_at timestamptz NOT NULL,
 search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(search_text,''))) STORED
);
CREATE INDEX search_documents_fts ON search.search_documents USING gin(search_vector);
CREATE INDEX search_documents_trgm ON search.search_documents USING gin(search_text public.gin_trgm_ops);
CREATE INDEX search_documents_filters ON search.search_documents(risk_level,updated_at DESC,id);
CREATE TABLE search.inbox_events(event_id uuid PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now());
