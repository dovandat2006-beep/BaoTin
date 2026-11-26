\getenv runtime_password THREAT_DB_PASSWORD
\getenv migration_password THREAT_MIGRATION_PASSWORD
CREATE ROLE baotin_threat_migrator LOGIN PASSWORD :'migration_password';
CREATE ROLE baotin_threat LOGIN PASSWORD :'runtime_password';
CREATE SCHEMA threat AUTHORIZATION baotin_threat_migrator;
REVOKE ALL ON SCHEMA threat FROM PUBLIC;
GRANT USAGE ON SCHEMA threat TO baotin_threat;
