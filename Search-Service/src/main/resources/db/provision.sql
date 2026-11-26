-- Run as operator on the single baotin database. Passwords are supplied externally.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE SCHEMA IF NOT EXISTS search;
-- Existing baotin_search role is provisioned externally; do not grant cross-schema access.
REVOKE ALL ON SCHEMA search FROM PUBLIC;
GRANT USAGE ON SCHEMA search TO baotin_search;
-- Apply after owning-service migrations using separate migrator credentials:
GRANT SELECT,INSERT,UPDATE ON search.search_documents TO baotin_search;
GRANT SELECT,INSERT ON search.inbox_events TO baotin_search;

-- After V3, append-only journal access for the runtime trigger/readiness:
GRANT SELECT,INSERT ON search.projection_journal TO baotin_search;
GRANT USAGE ON SEQUENCE search.projection_journal_sequence_seq TO baotin_search;
