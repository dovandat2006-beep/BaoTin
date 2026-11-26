# Report / Evidence / Moderation v1

Spring Boot 4.1.1 / Java 21. Report-Service owns only schema `report`.
Public contract: `../Contracts/openapi/report-service.yaml`.
Events: `../Contracts/events/report/report-events-v1.schema.json`.

## Startup

1. Provision PostgreSQL schema `report` owned by a dedicated migrator. Give the runtime role USAGE on that schema only. Run all versioned Flyway migrations with the migration credentials. V1 is preserved; V2 adds the optional source scan reference; V3 gives outbox rows a database publication sequence. After migration grant SELECT/INSERT/UPDATE on reports, evidence, idempotency_keys and outbox_events, and SELECT/INSERT on status_history to the runtime role. Grant USAGE on the outbox identity sequence (and equivalent default privileges before creating it). Never grant cross-schema access or schema CREATE to runtime. For production prefer a separate migration process and `SPRING_FLYWAY_ENABLED=false` in runtime; the migration identity must not be shipped with runtime afterward.
2. Provision private MinIO bucket `baotin-evidence` with no anonymous policy. Use restricted credentials: GetBucketLocation for this bucket; GetObject/PutObject/DeleteObject only under `baotin-evidence/reports/*`. Do not use root credentials. Bucket creation/policy is an operator task, never an automatic public fallback.
3. Provision Kafka topic `baotin.report.events.v1`; enable producer ACLs and TLS/SASL through standard Spring Kafka configuration for nonlocal environments.
4. Configure the variables in `.env.example` externally. The file is documentation, not an automatic dotenv loader. Report and Threat share a random service credential of at least 32 characters. Internal traffic must stay on private network/TLS, never through public ingress. Set Report's `THREAT_SERVICE_URI` and Threat's `REPORT_THREAT_SERVICE_TOKEN` consistently.
5. Start Threat-Service, Report-Service, then Gateway. `./gradlew.bat bootRun` on Windows; `./gradlew bootRun` on Unix. Gateway routes the published report/moderation allowlist. Frontend must point at Gateway, never Report directly.

Liveness is process-only. Readiness includes PostgreSQL. Kafka outage leaves durable outbox rows pending with bounded exponential retry; MinIO outage returns 503 for evidence operations. Report submissions do not depend on broker availability. Restore dependencies before retrying. Do not expose actuator management or internal APIs publicly.

## Behavior

- Authenticated users create reports for URL or MESSAGE_TEMPLATE. Optional scanId is resolved by Threat with the JWT reporter identity: it must be an owned scan with matching URL identity or original message input; foreign/deleted scans return 404 and mismatches return 400. No cross-schema scan query or FK is used. Existing manual requests and their idempotency hashes remain compatible. Threat owns canonical target identity. Message targets retain a fingerprint and redacted display, not raw text. Report title/description are explicitly submitted case content.
- Required UUID `Idempotency-Key`, scoped per owner. Identical retries replay the original 201 representation, even after moderation. Different payloads with the same key return 409. Keys are retained with the case, avoiding accidental duplicate creation after a lost response.
- `SUBMITTED` starts review via `start-review`. Only `PENDING_REVIEW` permits verify/reject/request-info. Owner may add evidence in SUBMITTED/NEED_MORE_INFO and resubmit a note from NEED_MORE_INFO. No appeals or AI verification.
- Moderators/admins cannot review their own reports. Decisions and supplements require current `version`; competing updates return 409. Row locks also protect the five-file limit.
- Evidence is JPEG/PNG/WebP/PDF, 1 byte–10 MiB, max five active files; declared MIME must match signature. SHA256 detects duplicate evidence. Downloads reauthorize every time and return attachment/no-store/nosniff. Storage keys never appear in public responses.
- Deletion immediately revokes download access. A scheduled, idempotent cleanup removes private object bytes and marks `purged_at`; storage outages retry later. Upload rollback compensates object creation. A crash between object PUT and DB commit may leave an unreferenced private object: reconcile bucket inventory against evidence.object_key before applying a retention policy. Never delete active object keys.
- State/history/outbox commit together. Kafka key is report ID; per-report pending events use a database sequence to preserve order across retries and clock changes. An acknowledgement lost before DB commit can replay the same eventId: consumers must use an inbox transaction. No raw descriptions, targets or object keys are published. Producer failures stay in outbox; consumer DLT policy belongs to downstream consumers.
- Terminal reports are immutable. Additive migrations only; do not down-migrate a live report schema or run the old scaffold against this deployment. Roll back with a compatible reader and preserved database/object data.

## Proof

`./gradlew.bat test`: 23 tests, including actual signed RS256 JWT validation, denied spoofed headers/roles/owners, duplicate submission concurrency, moderation concurrency, optimistic version checks, immutable terminal states, info/resubmit, evidence limits/MIME/duplicate/delete and outbox retry/order.

Default tests use H2 PostgreSQL mode with mocked Threat/MinIO/Kafka adapters. The same suite also passes against local PostgreSQL 17.6 using `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` and `--rerun-tasks`. `./gradlew.bat integrationTest` additionally passed with real PostgreSQL 17.6, MinIO 2025.4.22 and Kafka 4.1.0 in Docker. It verifies actual object bytes, denied anonymous access, object purge, idempotent creation, migration of populated outbox rows and ordered report.submitted/report.verified delivery. Only the Threat HTTP response is a contract fixture in this test; signed-JWT security remains covered separately. CI runs both suites. The test uses bitnamilegacy/minio because the upstream minio/minio repository is no longer publicly pullable; it is a test dependency, not the deployment image choice. Full deployment acceptance still belongs to the deployment environment. Observe `baotin_outbox_pending_total`; investigate sustained backlog and failed private-object cleanup. Never log case text or credentials.

Metrics: baotin_report_total counts only committed new reports; retries and rolled-back submissions do not increment it. baotin_outbox_pending_total reflects durable unpublished rows. Instrumentation is in MeterRegistry; the deployment owns private collection/export configuration.
