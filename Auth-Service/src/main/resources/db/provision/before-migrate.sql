-- Execute once with provisioning credentials, never Auth runtime credentials.
-- No passwords are embedded: set login credentials separately from secret files.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='baotin_auth_owner') THEN CREATE ROLE baotin_auth_owner NOLOGIN; END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='baotin_auth_migrator') THEN CREATE ROLE baotin_auth_migrator LOGIN; END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='baotin_auth') THEN CREATE ROLE baotin_auth LOGIN; END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='baotin_auth_operator') THEN CREATE ROLE baotin_auth_operator LOGIN; END IF;
END $$;
ALTER ROLE baotin_auth_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE baotin_auth_migrator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE baotin_auth NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE baotin_auth_operator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT baotin_auth_owner TO baotin_auth_migrator;
REVOKE baotin_auth_owner,baotin_auth_migrator FROM baotin_auth,baotin_auth_operator;
REVOKE baotin_auth_operator FROM baotin_auth;
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname IN ('baotin_auth','baotin_auth_operator')) THEN
        RAISE EXCEPTION 'unexpected runtime/operator role membership; remove before deployment';
    END IF;
END $$;
CREATE SCHEMA IF NOT EXISTS auth AUTHORIZATION baotin_auth_owner;
ALTER SCHEMA auth OWNER TO baotin_auth_owner;
REVOKE ALL ON SCHEMA auth FROM PUBLIC;
GRANT USAGE ON SCHEMA auth TO baotin_auth,baotin_auth_operator;
