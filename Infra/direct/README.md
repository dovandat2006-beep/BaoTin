# Primary deployment: backend JAR + frontend web

Hướng dẫn tạo file cấu hình và secret trên Windows: [SETUP.vi.md](SETUP.vi.md).

## Checkpoint — 2026-10-19

FullStack#12 terminal SUCCESS deployed ten backend JARs + Expo web after all eleven
Sonar gates passed before packaging. Post-job eleven owned non-elevated processes
are readiness UP; deployed hashes match CI artifacts. Domain CORS is configured for
`https://baotin.rimiprod.me`, evil origins return403. DB/key compatibility unchanged.
Cloudflare-ready origins/manual startup: [HOST_READY.vi.md](HOST_READY.vi.md).
No tunnel/DNS or SSH/VPS connection was performed. Eight business services remain
scaffolds; frontend integration remains its own TODO.

Manual startup/reboot recovery and reader2-compatible rollback after schema10:
[MANUAL_START.md](MANUAL_START.md). FullStack#8 promotion is accepted; no autostart installed.

`Infra/Jenkinsfile` builds ten backend jobs plus `Frontend`, waits for Sonar/security
gates (tests remain in external verify workflows), then asks for maintainer approval
when deploying. Deployment runs only
on `baotin-deploy-desktop`, not the Linux build controller. `DEPLOY_RELEASE=false` and
`BUILD_DOCKER_IMAGES=false` are defaults: Docker/GHCR is an alternative, not the primary runtime.

1. Provision PostgreSQL with Auth's `src/main/resources/db/provision/before-migrate.sql`, separate runtime,
   migrator and operator role/password files. Migrate using the approved Auth JAR's
   `DatabaseMigration` entry point with migrator credentials only, then apply reviewed
   `after-migrate.sql` grants. Never run Auth as postgres/migrator/operator.
2. Run PostgreSQL/Redis in the dedicated [dependencies Docker Compose](dependencies/README.md).
   Only host loopback15432/16379 is published for the Windows JARs, never LAN/public.
   Other service business schemas/consumers remain TODO until implemented.
3. Copy `.env.example` **outside Git**, configure real JDK21/Node paths, runtime root,
   public origins and external config-tree/key/password/API-token paths. Do not put secret
   values in the env file. Set node environment `BAOTIN_JAR_ENV_FILE` to this path.
4. Register the restricted Windows deploy-only node using `jenkins/windows-agent`.
   Configure all jobs with the confirmed repo URL and exact maintainer-reviewed SHA
   before loading Jenkinsfile; do not replace the reviewed pin with `*/main`.
5. Run the protected full-stack job. It supplies immutable successful build numbers to
   `Deploy-Jars.ps1`, which fetches only those authenticated Jenkins artifacts.

All ten JARs and the Node static-web server bind **127.0.0.1**; only Gateway/web should be
forwarded by the reviewed HTTPS ingress. Defaults are API8090, web8091, Auth8181 and
scaffolds8182..8189. Existing app/controller/Serve routes are not recreated or reset.
Configure reviewed Tailscale API9443/web9444 separately after verifying current hostname.

Deployment rejects unsuccessful builds, wrong artifact paths, occupied/duplicate config
ports and incompatible Auth/database reader/schema/cipher metadata. Current DB state is
read with `baotin_auth`, not migration/operator credentials. Normal release rejects key
overwrites; key rotation keeps its dedicated procedure. PID + process-start timestamp
limits stop operations to owned processes. New release state is committed only after all
eleven readiness checks; failed replacement restores the prior JAR/web runtime when present.
No DB down-migration, Docker/controller recreation or volume deletion is performed.

Windows runtime processes are launched with an explicit native handle whitelist: only
NUL stdin and each service's own output/error log files are inherited. They do not keep
Jenkins' durable-task stdout/stderr pipes open. A separate detached process group/job
breakaway preserves the caller's token (no elevation); failure to detach fails deployment.
PID/start-time ownership still controls stop/rollback. Logs remain in the owned release
directory outside Git. This fixes a real Jenkins promotion that printed readiness PASS
but kept its step open through inherited handles; readiness alone was not completion.

```powershell
.\direct\Test-DetachedProcess.ps1
```

The test reproduces the original parent-exited/output-pipe-held behavior with a separate
Node sleeper, then proves parent completion, independent child survival and file logging.
It stops only fixture-owned PID/start-time matches. Real Jenkins terminal SUCCESS plus
post-job runtime readiness must still be observed before release acceptance.

PostgreSQL provisioning/migration is an explicit operator prerequisite. Deployment does
not invent missing passwords or silently skip DB compatibility checks. Before first live
promotion, verify configured health, factor/key persistence, account/session revocation
and populated-DB rollback. The frontend's existing mock/backend-integration TODOs are not
closed by a successful web export or deployment.

## Direct-runtime regression

`Test-DirectRuntime.ps1` uses real current JARs for all ten services and the actual web
export, a fresh PostgreSQL/Redis pair bound only to ephemeral loopback ports, synthetic
keys/credentials in an ACL-restricted temporary directory and a synthetic Jenkins artifact
transport. It never uses a real Jenkins token, production DB or account. It tests two
full deployments, USER JWT/session continuity, forged ADMIN403, local HTTP Web cookie/
CSRF/refresh/logout contract, reader/cipher-floor and invalid-artifact rejection before
state mutation, failed Gateway startup with populated-DB rollback and encrypted TOTP
enrollment completion after rollback. This is not live Jenkins/HTTPS/provider proof.

```powershell
.\direct\Test-DirectRuntime.ps1
```

The first actual replacement exposed a PowerShell scalar-array bug: one owned process
became a hashtable whose Count was3, so a legitimate existing listener was rejected.
The owned-process selection is now explicitly array-wrapped outside the conditional.
Normal deployment no longer requires a migration-password file: provisioning/migration
remains the separate operator step, not a credential dependency of the runtime/deploy job.
Every test exit stops only recorded PID/start-time-matched processes and removes only
its own containers/volumes/temporary secrets. The real build daemon/controller is unchanged.

Repeated rejected releases exposed a second defect: downloaded JAR staging directories
were retained after preflight failure, eventually filling the deployment drive. Every
failed/non-active release now removes only its validated GUID staging directory; active
and previous rollback directories remain. The deployment lock is disposed even when
staging cleanup fails. Tests assert only the two successful release directories remain
after reader/cipher rejection and failed-startup rollback.

## Integrated business configuration — 2026-10-27

The public API integration release additionally requires external `THREAT_CONFIG_DIR`, `REPORT_CONFIG_DIR` and `NOTIFICATION_CONFIG_DIR`. These config-trees hold each service's own datasource URL/user/password, private service credentials and event/storage settings. Runtime disables Flyway, uses Auth JWKS on private loopback8181, and Notification receives the exact reviewed browser origins from the Auth/Gateway configuration. Reputation also uses private Auth JWKS8181.

Provision and back up the database separately. Preserve published migration checksums and existing Auth/Reputation data/keys. Report/Notification JARs expose an isolated operator entry point using DB_MIGRATION_PASSWORD_FILE and a distinct runtime role. Published Report/Threat V1 includes CREATE SCHEMA IF NOT EXISTS: PostgreSQL checks database CREATE even for an existing schema, so grant it only to the isolated migrator for initial V1 and revoke immediately afterward. Runtime never receives CREATE or migration credentials. No repair/drop/down-migration is part of rollback.

`dependencies/business-compose.yml` adds persistent Kafka and private MinIO on loopback19092/19000 for Windows JAR access; internal/controller/console ports stay unpublished. This separate project preserves existing dependencies, Jenkins and Cloudflare. Root MinIO credentials are file-mounted; Report uses a distinct user with `report-evidence-policy.json`, restricted to private reports/* objects. Kafka auto-creation is retained for this single-host domain-event integration; wider producer/topic ACL rollout remains separately reviewed.

Tests: `pwsh -NoProfile -File direct/Test-DeploymentEnvironment.ps1`; `python direct/dependencies/test_business_migrations.py --workspace WORKSPACE --java JDK21/bin/java.exe`; `python direct/dependencies/test_business_dependencies.py`. These fixtures verify config-tree isolation, no runtime migration, loopback listeners, preserved data on repeat migration, and negative CREATE/cross-schema/Flyway-history access. Production backup and live promotion acceptance are separate gates.

Integrated deployment preflight now rejects admin/migrator usernames, migration credential files, a different physical datasource URL and missing Notification-to-Threat bridge settings before artifacts are fetched or processes stopped. Report runtime grants omit DELETE in accordance with its runbook; its migration fixture proves DELETE denial.
# Analytics runtime — 2026-11-10

The new DB-backed Analytics release uses external `ANALYTICS_CONFIG_DIR` with its
own `baotin_analytics` role, reviewed DB URL/password, Flyway disabled and private
Auth JWKS at8181. Apply owning migrations with a separate operator before startup;
runtime receives no migrator credential or DDL permission. Absent configuration
remains compatible with an older accepted scaffold during rollback. Empty read
models are not proof of Report/Scan ingestion or composed live metrics.

## Search projection — 2026-11-11

SEARCH_CONFIG_DIR is now required: external configtree with exact baotin_search
runtime role, reviewed physical database URL/password and spring.kafka.bootstrap-servers.
Provision search schema/pg_trgm, run owning SearchMigration as separate migrator
and grant runtime DML before rollout. No credentials or mutation of another schema.
JAR launch forces Flyway false and events true; readiness requires schema and consumer.

Full Search candidate: apply owning migrations V2/V3 before rollout, provision the two dedicated owner Search streams, keep only runtime journal append/read grants. SearchSnapshotExport/Rebuild are manual owning-role CLIs, not app startup or public routes. See Search-Service/REBUILD.md. Local actual backfill34/17 rows is not a deployed HTTP acceptance.
