# Search Service — remaining work

## Implemented URL increment — 2026-11-11
- [x] Public read-only Gateway and service boundary, bounded parameters/problem JSON.
- [x] Own-schema PostgreSQL FTS/pg_trgm and explicit separate migration entry point.
- [x] Host-only Threat Outbox event, reputation signal, Inbox and stale-event rejection.
- [x] Vietnamese normalization, typo, filters/pages and SQL/authorization/privacy tests.
- [x] Expo live guest screen with loading/error/empty/unknown-risk states.
- [x] JAR/Compose/k8s/k3s Search runtime settings and dependency-network regression.

## Not covered by the URL increment
- [x] Receiver-side PHONE/EMAIL/BANK_ACCOUNT masking/exact-lookup contract and projection; owning producers remain separate service work.
- [x] Campaign created/updated public projection, explicit publication/withdrawal policy and broker tests; no fake source data.
- [x] Owning-service read-only exporters/composition and actual34-record local backfill; never Search-role foreign-schema SELECT.
- [x] Managed rebuild staging/checkpoint/atomic cutover/rollback, packaged CLI and concurrency/privacy/role tests.
- [x] Actual owning export/backfill and atomic cutover on the existing DB, with negative role checks.
- [ ] Final deployed-runtime/public Search acceptance and release rollback checks.
- [ ] Inbox retention compatible with maximum replay horizon; lag/poison alerts.
- [ ] Broker authentication/ACL and producer isolation for deployed topics.
- [ ] Full native-device acceptance and cluster rollout of this new revision.

Current full-model extension is uncommitted/unpublished pending managed rebuild and full release checks.
