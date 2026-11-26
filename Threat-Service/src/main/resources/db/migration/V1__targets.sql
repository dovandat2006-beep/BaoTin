CREATE SCHEMA IF NOT EXISTS threat;
CREATE TABLE threat.targets (
 id UUID PRIMARY KEY, type VARCHAR(30) NOT NULL, canonical_hash VARCHAR(64) NOT NULL,
 normalized_url VARCHAR(2048), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(type, canonical_hash), CHECK(type IN ('URL','MESSAGE_TEMPLATE'))
);
