# Text/URL Scan and ONNX delivery — 2026-10-22

## Delivered

All 14 `vietluccuu` repositories are cloned under `/Users/dovandat/BaoTin`.

- Model-Inference-Service: Spring Boot + native ONNX Runtime, PhoBERT/CANINE encoding, internal service credential, checksummed immutable artifacts including external weights, Python golden tensor/label/probability gate, warm-up, native deadline/capacity limits, metadata/metrics and separate liveness/readiness. Merged to main; feature branch deleted as requested.
- Threat-Service: text/URL scan orchestration, lexical normalization/extraction/rules, risk probability weighting and honest fallback, guest quotas, JWT validation, subject-owned snapshot history, optional encrypted 30-day raw retention, PostgreSQL/Flyway and transactional outbox/Kafka retry/replay. Work remains on feature/text-url-scan-engine for review.
- Contracts: versioned inference/scan/history API and scan event schemas, privacy and integration semantics; pushed to main while preserving concurrent Notification contract work.
- API-Gateway: explicit scan allowlist, guest POST and JWT-only history; inference remains private. Default routes and manual catalog sample updated without removing Notification/Watchlist work.
- Frontend: real Gateway scan calls, shared existing real Auth token/refresh client, snapshot history, cancellation/errors, model/rule/embedded URL detail and explicit fallback. Concurrent Auth/Notification updates preserved. Public API URL is baked at Expo build time.
- Local guest demo: `compose.demo.yml`, isolated database/runtime/migration roles, separate migration job, generated uncommitted credentials, private dependencies and only a loopback website port.

## Evidence

- Model service: 11 Java tests pass, including real native ONNX execution with input-dependent synthetic test graphs, Hugging Face Python tokenizer golden vectors, checksum/path and external-weight tamper failures, authenticated HTTP and missing-model readiness. Full public PhoBERT vocabulary was separately exercised locally; only synthetic vocabularies/graphs are committed as fixtures. Docker native inference/readiness was also exercised with test artifacts.
- Threat service: 17 Java tests pass, including real PostgreSQL/Kafka Testcontainers, signed JWT issuer/audience checks, history ownership/foreign IDs, concurrent canonical target identity, encryption/expiry, atomic rollback on outbox insertion failure, real broker delivery and replay with the same eventId, embedded URL cap, stalled/oversized provider responses and total provider budget.
- Contracts: 3 schema/OpenAPI/privacy/auth boundary tests pass.
- Gateway: full regression suite passed after the scan allowlist change; an additional run of both default ScanRoutesTests passed with real Redis, forwarding, CORS and forged-header removal.
- Frontend: typecheck, 42 unit tests and web export pass; UI regression on desktop/mobile passes 22 tests with 2 viewport-specific skips.
- Real-stack browser smoke: desktop 1440x1000 and mobile 375x844 pass against the actual website -> nginx -> Gateway -> Threat -> Model boundary with no API mocking. Covers canonicalization, duplicate links, credential stripping, fallback and guest history 401. Screenshot verification includes light/dark themes.
- Local runtime DB privilege check: CREATE on threat schema=false, INSERT on scan_history=true, UPDATE on flyway_schema_history=false. Guest scans leave scan_history empty.

## Explicit limits

The trained ONNX weights and saved training tokenizer artifacts are not present on this machine or in the AI checkout, as confirmed by the user. Production-model accuracy, real-weight parity and CPU/RAM/p50/p95 acceptance cannot be claimed. The runtime and packaging flow are implemented from the actual AI training/export code. Missing weights intentionally produce MISSING/503 and readiness DOWN for model inference; Threat/website remain live with an explicit degraded result. No generated/pretrained substitute is used in the user demo.

The isolated composition demonstrates guest scans. It does not replace the team's complete Auth/Reputation/Notification deployment. Authenticated history is integration-tested with signed JWTs; the Frontend reuses the merged real Web Auth client when EXPO_PUBLIC_API_URL is configured. The optional Reputation adapter now reuses the owner's published target-ID/finalScore contract; its Auth-issued ADMIN/MODERATOR credential must be provisioned/rotated externally before enabling lookup. URL signals additionally publish the existing threat.updated contract through the transactional outbox. Kafka publication is disabled in the guest demo but outbox rows are retained; broker delivery is proven by Testcontainers.

No production rollout, remote database migration, Jenkins promotion or registry publication is claimed. Native iOS/Android hardware behavior and real-model security/performance gates are not inferred from browser/fixture tests. Keep .env.demo with the persistent local database volume; normal shutdown preserves both.
