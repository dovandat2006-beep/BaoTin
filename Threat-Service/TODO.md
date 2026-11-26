# Threat Scan delivery

2026-10-29 integration review: the packaged operator now supports a separate
bounded migration secret file and least-privilege counter grants. Disposable
V2→V3/idempotency/old-new-old JAR compatibility acceptance is required before
production promotion; no production migration is implied by source/test success.

- [x] Text/URL APIs; SMS compatibility input; reject unrelated scan types.
- [x] RS256 Auth JWT validation and subject-owned history; guest scan with lower quotas.
- [x] Lexical URL normalization/extraction; no DNS/HTTP navigation to user URLs.
- [x] Text normalization/template fingerprint; no permanent raw-text target from a scan.
- [x] Deterministic rules; model risk probabilities; configurable score weights and explicit fallback.
- [x] Separate private model client with bounded complete-response deadline/bytes.
- [x] Flyway schema and JDBC persistence; deterministic lock ordering for duplicate URL targets.
- [x] Immutable history snapshots, filters/detail/delete, no guest personal history.
- [x] Fully redacted text previews, HMAC input hashes, optional AES-GCM/30-day retention purge.
- [x] Atomic business update + outbox; Kafka retries and stable eventId replay.
- [x] Testcontainers PostgreSQL/Kafka and authorization/privacy/concurrency tests.
- [x] Frontend/API Gateway source integration (real token/refresh client, scan and history).
- [x] Separate migration entry point; isolated guest demo composition.
- [x] Provider complete-body deadlines/size bounds and outbox rollback regression.
- [x] Local website smoke verification on desktop/mobile against the actual Gateway/Threat/Model stack.
- [ ] Mount real trained artifacts in Model-Inference-Service for AI demo acceptance.
- [x] Reuse published Reputation target lookup/finalScore and threat.updated signal event contract.
- [x] Daily all-scan aggregate for admin dashboard (`scan_metrics_daily`, role-protected `/api/v1/scans/admin/stats`), with no scan identity/raw content retained.
- [x] Implement compatible private read-only Reputation credential mode, keeping legacy Auth JWT and model authentication unchanged; negative/header-boundary tests included.
- [x] Promote the read-only Reputation bridge and prove real synchronous lookup on the public domain (FullStack7, 2026-11-02): real stored/public score 24.96; frontend renders weighted risk 57.4; private key never sent to browser.

## API integration delivery — 2026-10-27

- [x] Source-copy public Gateway/contract and adapter regressions pass with JDK21/typecheck/Jest.
- [x] Isolated real Auth/TOTP/Gateway/Scan/History/Report/Evidence/Moderation/Kafka/Reputation/Notification/Watchlist chain passes, including ownership and moderator denials.
- [x] Desktop/mobile real browser Web login/logout, watchlist, scan/history and report evidence pass without API mocking.
- [ ] Promote exact reviewed SHAs through Jenkins and verify public-domain artifact/process/API evidence; fixture success is not production acceptance.

Search URL event now uses the existing Outbox. Historical search export/backfill
and campaign/other target projections remain separate contract tasks.

Search backfill: owning-role read-only snapshot exporter implemented/tested; no unrelated business endpoint or scheduler added.
