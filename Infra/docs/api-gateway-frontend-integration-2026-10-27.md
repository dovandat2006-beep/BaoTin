# API → Gateway → Frontend integration (2026-10-27)

## Approved direction
Implement and verify the already delivered public flows, then promote a reviewed release through Jenkins to https://baotin.rimiprod.me. Preserve existing user data, Auth signing/encryption keys, Cloudflare configuration, private endpoints, original source history and a compatible rollback reader.

## Evidence at design approval (historical)

Implementation and public acceptance now passed with FullStack #27; see [final acceptance](api-integration-acceptance-2026-10-27.md). The observations below describe the pre-change baseline, not current production.
- Source main includes Auth, Notification/Watchlist, Text/URL Scan and owned History, Report/Evidence/Moderation, internal ONNX inference and Reputation.
- The live primary runtime is accepted FullStack #24, not the newly merged source. Guest POST /api/v1/scans/text currently returns 404 through the public Gateway. Public health returns 200; unauthenticated private-user routes return 401 (this alone does not prove upstream connectivity).
- Gateway ships Scan routes and adds Report routes, but Notification/Watchlist is only in the manual catalog fixture. Merely configuring a service address must not expose arbitrary endpoints.
- Frontend adapters exist, but the Jenkins web builder omits EXPO_PUBLIC_API_URL and therefore exports the default demo-auth mode.
- The direct Windows launcher has Auth/Gateway/Reputation/Model config-tree wiring; the implemented Threat, Report and Notification services need explicit private runtime configuration, schema provisioning and independent migrations.

## Design and boundaries
1. Gateway: explicitly enumerate the reviewed public contract endpoints for Scan/History, Notification/Devices/Watchlist, Report/Evidence and Moderation. Preserve exact methods, JWT, moderator/admin checks, header sanitization, body limits and CORS. External catalogs remain reviewed replacements; service addresses alone never expose an endpoint. Inference, Reputation and every /internal/** endpoint stay private.
2. Frontend: all implemented live features share one Gateway client, real Auth subject/roles and refresh/logout behavior. Release builds explicitly bake the public Gateway origin into the Expo artifact; demo browser fixtures remain explicit test/demo builds. Preserve Web/Android/iOS compilation. Never expose model/service credentials in EXPO_PUBLIC variables.
3. Deployment: extend existing direct-runtime config-tree support to Threat, Report and Notification. Provision schema-scoped migrator/runtime roles and additive migrations separately; runtime receives no schema CREATE, migration credentials or cross-schema grants. Keep existing Auth/Reputation data and keys unchanged. Provide private persistent evidence storage and Kafka connectivity for Report/Reputation/Notification events where required. FCM credentials and real-device delivery require actual provider/device acceptance, not fabricated tokens.
4. Delivery: retain Jenkins Sonar/security/artifact gates, exact reviewed source pins, protected deploy node, maintainer promotion and compatible application rollback. No hidden recovery workers, execution-policy bypass, antivirus changes, or new scheduler-based runtime startup. Existing deployment processes are replaced only by the normal reviewed deployment lane.
5. Search, Campaign and Analytics are still scaffold services and remain outside this explicitly approved integration slice. Their demo screens are not counted as live backend proof; no fabricated business API is introduced.

## Acceptance proof
- Contract-to-Gateway method/path agreement, no unknown/private/public-inference routes, anonymous denial and USER-to-moderator denial; real Redis and signed JWT tests.
- Frontend typecheck/Jest, API browser fixture regressions, Web/Android/iOS exports, release API configuration assertions.
- Isolated real-service tests via Gateway: password registration/login/profile/refresh/logout; Notification/preferences/watchlist CRUD and ownership; guest text/URL scan and signed-in history/detail/delete; report create/idempotent retry, owned scan source, private evidence upload/download/delete, moderator review and role/ownership/version denial. Real PostgreSQL/MinIO/Kafka adapters, explicit inference model status and no API mocking for live proof.
- Existing service wrappers use JDK21, tests are not skipped. Preserve baseline test results, final results and rollback-copy behavior.
- After Jenkins terminal SUCCESS, verify exact artifact hashes, eleven PID/start-time identities, non-elevation, loopback listeners, readiness and public HTTPS browser requests through Cloudflare. Existing DB/tunnel/keys remain intact.

## Implementation sequence
1. Baseline snapshots and tests; contract/route/config inventory.
2. Failing integration tests at missing boundaries, minimal Gateway/frontend/runtime wiring changes and docs.
3. Isolated full-stack integration with fresh data, least-privilege migration and compatible rollback proof.
4. Review diff, commit and validate CI/quality/security for exact SHAs; prepare external config and data backup for additive migration.
5. Jenkins promotion with maintainer approval; real-domain acceptance and a per-feature evidence matrix. Record any feature whose external acceptance is still missing.
