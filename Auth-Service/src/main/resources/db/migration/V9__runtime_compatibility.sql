CREATE TABLE auth.runtime_metadata (
    name VARCHAR(64) PRIMARY KEY,
    value VARCHAR(128) NOT NULL
);
INSERT INTO auth.runtime_metadata(name,value) VALUES
    ('schemaRevision','9'),('readerFloor','1'),('cipherFloor','BK1');
-- Only migration/provisioning identities can change compatibility floors.
REVOKE ALL ON auth.runtime_metadata FROM PUBLIC;
