# Threat Service — Text/URL Scan Engine

Java 21 / Spring Boot 4. Owns scan orchestration, lexical rules, URL targets and authenticated history in PostgreSQL schema `threat`. Inference stays private in Model-Inference-Service; reputation remains owned by Reputation-Service.

Implemented: `POST /api/v1/scans/text`, `/url`, compatibility `/api/v1/scans` (TEXT/SMS/URL only); URL extraction/deduplication (up to five links); deterministic text/URL rules; configured weighted risk scoring with missing-signal renormalization; AI outage fallback; guest/user quotas; RS256 issuer/audience/type/access-token validation; subject-owned history, snapshot/detail/delete; optional AES-GCM text retention with 30-day cleanup; transactional outbox and Kafka publisher.

Admin dashboard aggregate: `GET /api/v1/scans/admin/stats` requires `ADMIN` or `MODERATOR` and returns `{scansToday, highRiskScansToday}` for the current UTC day. The endpoint is called directly by Analytics-Service and is not exposed by API-Gateway. Counters live in `threat.scan_metrics_daily` and are incremented for every scan, including guest scans, without retaining scan identity, target or raw content. The table is part of the explicit runtime-role grant list in `DatabaseMigration`.

The backend suite passes against real PostgreSQL/Kafka containers, including ownership, native service credentials, concurrent URL identity, retention and stable outbox replay. Website/Gateway integration is being connected separately. It does not include trained weights. Missing AI returns honest degraded results; it never invents labels/confidence/version.

## Run

Set values from `.env.example` using external configuration. `THREAT_HASH_KEY` must be base64 of at least 32 random bytes. Raw text is not retained by default; enable it only with a separate base64 AES-256 `THREAT_CONTENT_KEY`. The model service credential is never sent to a browser.

```sh
sh ./gradlew test bootJar
sh ./gradlew bootRun
curl http://localhost:8182/actuator/health/liveness
curl http://localhost:8182/actuator/health/readiness
curl -H 'Content-Type: application/json' -d '{"url":"https://example.test"}' http://localhost:8182/api/v1/scans/url
```

Set SERVER_PORT=8182 for the example above. Configure `MODEL_INFERENCE_SERVICE_URI` (private service origin) and matching `MODEL_SERVICE_TOKEN`. Both services use identity model preprocessing; URL canonicalization and text template normalization are separate from model input. No scanned URL is ever fetched or resolved.

## Storage / security

Create a schema-owner migration role and a separate runtime role with USAGE on `threat` and SELECT/INSERT/UPDATE/DELETE on its tables. Runtime must not own schema or receive CREATE, cross-schema, Flyway-history write or superuser permissions. The isolated DatabaseMigration entry point can grant the explicit business-table list via operator-only DB_RUNTIME_USER; no wildcard grants to migration metadata are needed. Flyway can use `DB_MIGRATION_USER/PASSWORD`; in production run migrations as an isolated step and set `DB_MIGRATION_ENABLED=false` for the runtime. Migration is additive; roll back application images before considering schema removal. Never drop a populated schema to roll back.

The operator accepts `DB_MIGRATION_PASSWORD_FILE` (bounded regular UTF-8 file,
at least32 characters) without placing the secret in command arguments. Existing
direct `DB_MIGRATION_PASSWORD` operator input remains compatible, but specifying
both sources fails. `DB_RUNTIME_USER`, when requested, must differ from the
migrator. Daily counters grant only SELECT/INSERT/UPDATE, not DELETE; rerunning the
operator narrows an earlier overbroad direct DELETE grant. No privilege is added
to Flyway history or another service's schema. Keep migration credentials outside
runtime config trees and confirm schemaV3/grants before promoting the new counter
writer; readinessDB alone does not prove the counter table has been migrated.

All GET/DELETE history requests require a signed Auth access JWT. Guest scans create no personal history. Raw text previews are fully redacted, input hashes are keyed, and snapshots/events/logs exclude raw text. `THREAT_TRUSTED_GATEWAY_CIDRS` must contain only exact gateway peers before trusting their sanitized `X-BaoTin-Client-IP`; other callers are limited by socket address. Do not trust arbitrary forwarding/user headers. Invalid provided bearer tokens fail even for guest-capable endpoints.

History filters: type=TEXT|URL, riskLevel, page>=0, size=1..100, sort=createdAt,asc|desc. Foreign detail/delete IDs return 404. Guest history returns 401. Responses carry no-store and a validated correlation UUID. Oversized body 413, invalid input 400, quota/capacity 429, storage failure 503.

## Scoring and events

Weights default to the master plan: URL new .70 AI/.30 rules; known .50/.20/.30 reputation; text .85/.15; text+URLs .75/.15/.10 maximum embedded URL. Override `scan.weights.*` only as normalized positive arrays. Confidence belongs to the winning label; `riskProbability` sums non-benign classes. Missing components are removed and remaining weights renormalized. `aiAvailable`, `degraded` and warning codes make incomplete analysis explicit.

`THREAT_EVENTS_ENABLED=true` enables publishing to `baotin.scan.events.v1`; scan/target updates and outbox inserts commit atomically. Publisher uses row locks/SKIP LOCKED and broker acknowledgements; crash after ack may replay the same eventId. Consumers deduplicate. After 10 failures, FAILED events are retained for operator review; Kafka outages do not interrupt scanning while PostgreSQL is healthy. Events disabled means pending outbox rows remain for later delivery.

Optional Reputation lookup uses the published `GET /internal/v1/reputation/targets/{threatTargetId}` contract and reads `finalScore`. It looks up an existing canonical target ID first; new targets have no reputation. Configure `REPUTATION_SERVICE_URI` as a private service origin. `REPUTATION_AUTH_MODE=bearer` (default) preserves the existing Auth-issued ADMIN/MODERATOR access JWT in `REPUTATION_SERVICE_TOKEN`; expiry/401 is explicit fallback, not a fabricated score. Recommended `REPUTATION_AUTH_MODE=service-token` sends only `X-Service-Token`, using a separate random 256-bit key represented as 64 lowercase hex characters in `REPUTATION_SERVICE_TOKEN`, matching Reputation's `REPUTATION_THREAT_READ_TOKEN`. This key grants only the internal target GET, never recalculation/admin. Unknown mode or malformed configured service key fails startup without logging the key. Store it in an external protected config-tree file, not frontend/EXPO_PUBLIC or Git. Expand Reputation first, then enable Threat; disable lookup before rolling back the provider. No new public route or DB grant is needed. In addition to Scan v1 events, each URL publishes the existing `threat.updated` signal contract on `baotin.threat.events.v1` through the same outbox. Missing AI is omitted from the event, never replaced with zero.

Contracts: `../Contracts/openapi/threat-service.json`, `../Contracts/events/scan/scan-events-v1.schema.json`. Existing Jenkins build/release lanes remain; tests run locally and in PR verification.

## Local guest website demo

Clone sibling `Frontend`, `API-Gateway`, `Model-Inference-Service`, `AI` and this repo under the same workspace. Use the integrated main branches of these repositories.

```sh
python3 -m unittest discover -s ops
python3 ops/create-demo-env.py
docker compose --env-file .env.demo -f compose.demo.yml up -d --build
# Website: http://localhost:8096
docker compose --env-file .env.demo -f compose.demo.yml ps
```

Credentials are generated into mode-0600 `.env.demo` and never printed or committed. The integrated runtime also requires REPORT_THREAT_SERVICE_TOKEN. For an existing demo env, generate and append this new random credential externally without replacing the existing database passwords or hash keys. The database volume persists across `down`; keep its env file/keys. Startup runs a separate schema-owner migration container; runtime gets only `baotin_threat` credentials and cannot create tables. Only frontend publishes a loopback port; PostgreSQL, Redis, Threat, Gateway and inference use an internal network. No existing database or Jenkins instance is touched.

This composition is a **guest scan demo**, not the team's complete production stack. Auth and Reputation are not launched here; real signed-in history uses the existing team Auth deployment and its matching JWKS/issuer/audience. Kafka publishing is disabled in this local composition, but outbox rows are retained; Kafka delivery is verified separately by Testcontainers. Missing model artifacts intentionally leave inference readiness DOWN while the website/Threat continue with explicit fallback. Do not change model health to UP just to hide missing weights.

When trained exports arrive, package each with Model-Inference-Service's `scripts/package_artifact.py`. Put the two immutable artifact directories under an external parent and append to `.env.demo`:

```dotenv
BAOTIN_MODEL_DIR=/absolute/external/artifacts
MODEL_TEXT_PATH=/models/text-1.0.0
MODEL_URL_PATH=/models/url-1.0.0
```

Recreate only model inference after changing mounted artifacts. Its native sessions reload once and refuse checksum/tokenizer/probability mismatch. Never mount `src/test/resources/fixtures` for an actual user demo; those graphs only test integration, not fraud detection.

Stop with `docker compose --env-file .env.demo -f compose.demo.yml down`; it preserves data. Back up the named PostgreSQL volume and `.env.demo` before changing versions. Roll back app images and keep the additive schema. Volume removal is an explicit destructive reset, never part of the normal instructions.

## Report target integration

POST /internal/v1/targets/resolve remains private and requires REPORT_THREAT_SERVICE_TOKEN (at least 32 characters). A dedicated ordered security chain protects internal paths without shadowing scan JWT authorization. URL resolution reuses the scan normalizer and the same target table; it never fetches the submitted URL. Display snapshots are bounded to 500 characters. Message targets store a normalized fingerprint, never the original message.

Migration lineage for integrated main: published V1__targets.sql is unchanged; V2__scan_engine.sql extends its target rows and adds scan/history/outbox tables. Existing target IDs are preserved. Do not replace an already-applied migration checksum: environments initialized from the separate, unmerged V1__scan_engine.sql lineage need an operator-reviewed reconciliation before adopting this branch. Do not run Flyway repair or drop schema automatically.

## Notification target binding

`NOTIFICATION_THREAT_SERVICE_TOKEN` configures a separate optional private credential for Notification URL watch binding. It must differ from the Report credential and contain at least 32 characters. That caller may resolve URL identities only, without scanId/reporterUserId; message/template or source-scan access is forbidden. Existing Report token behavior is unchanged. No internal target endpoint is routed publicly.


## Release dependency gate — 2026-10-28

Jenkins FullStack28/Auth41 identified new HIGH advisories in transitive Snappy and Guava. Runtime constraints select snappy-java1.1.10.9. This dependency-only patch preserves APIs/auth/events; scanner policy remains enabled. Repo wrapper tests and packaged-JAR scans must pass before promotion.

## Search projection event — 2026-11-11

Scan transaction adds search.target.updated on baotin.search.targets.v1 through
the same Outbox. Only a public DNS hostname is included; URL path/query/userinfo,
SMS and IP/internal/local hosts are excluded. Existing event schemas/topics remain
unchanged. Provision this new topic/ACL before rollout; historical backfill remains
a separate owning-service operation. Contract: ../Contracts/docs/search-v1.md.

## Owning Search snapshot exporter — 2026-11-12 (candidate)

The manual `com.baotin.threat.operator.SearchSnapshotExport` CLI accepts a NEW
absolute JSONL output path and the Search rebuild `startedAt` ISO-8601 timestamp.
Set SOURCE_EXPORT_URL, SOURCE_EXPORT_USER and SOURCE_EXPORT_PASSWORD_FILE for
this process only. It verifies its actual owning DB user, uses a repeatable-read
read-only transaction, never reads another business schema, and writes a hashed
complete owner manifest next to the data. No runtime HTTP endpoint or scheduler
is added. Export files/passwords stay outside Git.

Launch with the approved JDK21 JAR and PropertiesLauncher, for example:
`java -Dloader.main=com.baotin.threat.operator.SearchSnapshotExport -cp build/libs/Threat-Service-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher L:/external/threat.jsonl STARTED_AT_ISO`

Role baotin_threat exports hostname-only URLs and message-template fingerprints. Paths/query/userinfo/raw messages never enter the public export; unpublishable local/IP targets are excluded. The same pure publication policy is used by the transactional Outbox producer.

Full owning-role/privacy/negative privilege tests must pass before promotion.

## Masked message lookup — 2026-11-12 (candidate, not deployed)

Message history retains readable masked wording, rather than hiding the whole
message. Detected contacts, numeric identifiers, labelled secrets/name/address
fields and URL paths/query values become `***`; automatic pattern masking is
not a guarantee of detecting every possible personal detail. High/critical text
scans publish only masked wording through the owning Threat Outbox. Search
validates the same masking policy, returns optional full `content`, and removes
it on PRIVATE/REMOVED. Existing fingerprint-only documents remain compatible.
Old hidden snapshots without retained input cannot be reconstructed.

Dataset TEXT/URL seeds use the owning Threat operator, SHA-bound JSONL and
per-row transactions/deduplication. SUSPICIOUS is a content warning only; UNKNOWN
is not a safe verdict, and no seed changes report VERIFIED or fabricates scores.
Dataset source stays internal, not a required UI field. Threat/Search additive
V4 migrations and runtime grants must precede promotion. Roll back code without
dropping these populated additive tables. Deployment/import acceptance remains
separate from passing source tests.

Bulk seed publishing: owning V5 adds an ordered partial index on pending Outbox
creation order. It preserves the existing ten-row locking/ack/idempotency policy
and runtime grants, while avoiding a full payload scan/external sort per batch.
Apply through the isolated owning migrator; retain the additive index on app rollback.

## Dataset binary labels — 2026-11-12 correction
SMS0=legitimate (BENIGN),1=spam/fraud (SUSPICIOUS); URL benign=BENIGN,
phishing=SUSPICIOUS. UNKNOWN is reserved for genuinely unlabelled input.
Lookup displays a dataset label independently of aggregate risk scores and report
verification. A normalized message/domain may combine records: a dangerous label
wins over a benign one; absence of a score does not discard an existing label.
Additive ThreatV6 restores previously seeded normal labels; expand Search enum
support before publishing BENIGN events. No report VERIFIED or score is inferred.
