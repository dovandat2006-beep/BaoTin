# ADMIN lifecycle and PostgreSQL roles

## Provisioning
1. Provisioning identity executes `src/main/resources/db/provision/before-migrate.sql` in `baotin`.
2. Set random passwords externally for `baotin_auth_migrator`, `baotin_auth` and `baotin_auth_operator`. Store them separately; no shared password or superuser credential is given to Auth.
3. Run isolated `migrateDatabase` job with `baotin_auth_migrator`; runtime datasource uses `baotin_auth`, with `DB_MIGRATION_ENABLED=false` and no migrator password in runtime environment.
4. Provisioning identity executes `src/main/resources/db/provision/after-migrate.sql` after each reviewed migration.
5. Verify runtime cannot CREATE/ALTER/DROP, grant roles, modify `users.role/status`, execute operator function, update/delete audit or read another schema. Do not skip privilege tests when extending migrations.

The owner is NOLOGIN. Migrator can DDL in `auth` but runtime/operator have no owner membership. The operator only receives EXECUTE on one bounded lifecycle function; it does not need user-table write access.

```powershell
# Isolated migrator environment: DB_URL, DB_MIGRATION_USER=baotin_auth_migrator, DB_MIGRATION_PASSWORD.
.\gradlew.bat migrateDatabase
```

The containerized equivalent uses the same Auth image with Java PropertiesLauncher and `loader.main=com.baotin.auth.operator.DatabaseMigration`. It never starts the HTTP application and does not need JWT/SMTP/provider credentials.

For an existing database, review/transfer existing `auth` table/function ownership to the migrator before enabling runtime grants. Provisioning fails if runtime/operator still own objects or have unexpected role memberships; it does not silently preserve elevated access.

## Create an ADMIN without a default password
1. User registers a normal USER identity; operator confirms the target UUID out of band.
2. Operator `request` records pending grant with an audit reason. Account remains USER, never a partially initialized ADMIN.
3. User verifies email and enrolls/confirm TOTP as normal USER.
4. Operator `activate` checks active status, verified email and confirmed MFA before granting ADMIN, bumps security version and revokes sessions. The user logs in again with password/SSO plus TOTP.

```powershell
# Credentials supplied by the isolated operator environment, not command arguments.
.\gradlew.bat adminOperator --args='request USER_UUID "approved initial admin request"'
.\gradlew.bat adminOperator --args='activate USER_UUID "verified owner completed MFA"'
```

Only `OPERATOR_DB_URL`, `OPERATOR_DB_USER=baotin_auth_operator`, `OPERATOR_DB_PASSWORD` are used by the CLI. They are not runtime app variables. The CLI validates actual `session_user` before running SQL.

## Lost authenticator AND recovery codes
Operator verifies owner identity out of band and invokes `recover` with a reason. This temporarily demotes ADMIN to USER, removes the MFA secret/recovery codes, revokes every session and creates a pending grant. The user recovers password as needed, verifies/enrolls a new authenticator, then operator explicitly invokes `activate`. Email/password reset alone never removes MFA or restores ADMIN.

`revoke` demotes an ADMIN and revokes sessions. Every operator action creates an append-only audit entry. There is no public endpoint for ADMIN grants or operator recovery.

## Application ADMIN actions
`POST /api/v1/auth/step-up` consumes a fresh TOTP code for the current MFA-authenticated session. Step-up lasts 5 minutes; recovery codes cannot substitute here. Only then may ADMIN list accounts, change USER/MODERATOR role or lock/unlock/disable non-ADMIN accounts. Self changes and modifying other ADMIN accounts are denied.
