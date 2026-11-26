# Search increment and deployment audit — 2026-11-11

## Source-backed result

The existing graph was queried before source inspection (834 nodes); it is a map
of an earlier revision, not proof that the new Search exists. Direct source review
found Search to be a startup scaffold and live Expo Search deliberately unavailable.
The new URL increment adds bounded anonymous Gateway/service GET, own-schema
PostgreSQL FTS/trigram projection, host-only Threat transactional Outbox event,
reputation consumption, transactional Inbox, and a real guest Expo page. It does
not turn an AI signal into report VERIFIED or expose private report/SMS data.
Other target types, campaign projection and historical export/backfill remain
explicit Search TODOs; do not advertise them as implemented.

## CI/CD and packaging

- Search GitHub verify: JDK21 wrapper test/bootJar; PostgreSQL/Kafka tests use
  isolated Testcontainers. Jenkins is the release/deployment authority.
- Existing per-repo release pipelines run Sonar before packaging, security/SBOM
  and immutable artifact checks. They deliberately do NOT execute tests; local
  and verify-lane tests of the candidate revision remain required. A successful
  release is not a coverage claim. No gate is disabled by this increment.
- Search Dockerfile: pinned JDK/JRE21 Alpine bases, non-root1001, healthcheck.
  Actual isolated image/readiness/query test is in the task evidence. This is not
  an acceptance of the complete Docker alternative for all current services.
- Root docker-compose.yml and older CI-CD.md sections contain historical/local
  defaults. They are not the authoritative production runbook. Do not use their
  default passwords or broadly exposed dependency ports as production config.
- Primary JAR deployment now requires SEARCH_CONFIG_DIR and verifies its exact
  runtime DB identity/database/no migrator credentials. Migration is an explicit
  owning-service step before promotion, not a runtime privilege escalation.

## Kubernetes / k3s

Catalog now adds Search's private Secret, DB role, DB/Kafka egress and Gateway
inbound; also aligns the already-implemented read-only Analytics Secret/DB/JWKS
flows. No new host port, Redis or MinIO grant is added for Search/Analytics.
K8s/k3s render/deployment tests pass locally. Config example remains intentionally
UNCONFIGURED until operator supplies actual context/namespace UID, private
endpoints, image digests, registry/config Secrets and model PVC. Current kubectl
has no selected context. Older isolated cluster acceptance exists in the workspace
but does not accept this new revision. No new live-cluster rollout is claimed.

## Full-app readiness judgment

Use the existing guarded Jenkins JAR/Web route for this delivery, with additive
Search provisioning, final APK verification and explicit release approval. A source
build alone does not certify all optional deployment routes. In particular, the
legacy Docker alternative still needs an end-to-end revalidation of all business
service configtrees/model mounts and Docker-reachable Kafka endpoints before a
switch; only the Search mount is added here. Campaign remains a scaffold. Provider
consent, native device behavior, backfill and production cluster acceptance remain
separate from Web/container regression proof.

## Evidence

Task transaction: M:/BaoTin/.changes/search-20261111/. It preserves original file
hashes, tested isolated source rollback, exact wrapper results, browser captures,
container readiness, owning migration/negative DB grants, Jenkins build handles
and final artifact verification. The release result is recorded there after the
job reaches a terminal state; this document intentionally does not predict it.
