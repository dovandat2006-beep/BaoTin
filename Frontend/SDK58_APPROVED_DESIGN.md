# Approved Expo58 + real Gateway integration refresh — 2026-10-28

## User-approved scope and delivery
- Pull each independent repo first (completed15;14 BaoTin origins plus read-only reference;0 changed commits/conflicts).
- Merge any unmerged remote feature work only after review/tests; delete merged feature branches only after exact-head/main-ancestry checks and preserving their commits in local Git bundles. Keep main/develop/release branches.
- Upgrade Frontend to npm stable Expo58.0.20, React/RN/native modules and tooling selected by the matching Expo58 SDK. Verify SDK-specific Expo Go compatibility, not merely the old global client metadata.
- Run commands and Expo Go use real public Gateway configuration by default. Demo data is explicit test-only, never an implicit fallback on missing config or server errors.
- Wire already implemented Auth, Scan/History, Notification/Watchlist, Report/Evidence/Moderation through Gateway. Internal Model/Reputation stay private; Kafka/outbox/inbox and owner/RBAC/version boundaries remain.
- Search/Campaign/Analytics have no business controllers. User approved no new scaffold service development: live UI shows unavailable/not implemented, never fabricated metrics/results. Tests may retain explicit synthetic fixtures.
- Reuse external secret files and existing volumes/keys; public .env examples contain placeholders/public URLs only. Secrets are file-mounted/read-only or Spring config-tree/_FILE; audit ACLs, no secret values in Git/logs.
- Validate baseline/modified/rollback, SDK compatibility, typecheck/unit/security/browser tests, Web/Android/iOS exports and actual API flows. User-run device acceptance remains separate from compilation proof.
- User explicitly requested direct push to main of each changed repo, then Jenkins build/deploy. Do not introduce PR gates, lower security/Sonar policy, auto-advance untested pins or deploy local unreviewed JARs.

## Rollout and rollback
Keep current DB data/signing/encryption keys, private service ports and shared Cloudflare/DNS unchanged. Start only existing current CI/dependency Compose resources (not legacy duplicate Sonar/frontend Compose). Use immutable gated child artifacts and the existing non-elevated deployment node. Check DB compatibility,11 owned loopback/readiness processes, artifact hashes, public boundaries and real frontend/API flows after terminal SUCCESS. Preserve compatible previous release and original source bundles; no down migrations or key replacements.

## Current observations and risks
All remote branch heads observed are already in main and no PR is open. Frontend currently SDK57 and apiEnabled=false when API URL is absent; Search/Admin still import mock datasets. The prior live release is FullStack27, while Docker CI/dependencies are currently stopped by previous user request. SDK58 is npm latest58.0.20; Expo version API lists RN0.88.0/React19.3.0 and SDK-specific Go clients Android58.0.5/iOS58.0.7, but old global expoGoSdkVersion still says57; verify/document this explicitly. C: has only about0.6GiB free; do not fill it, delete unrelated data or weaken Jenkins disk thresholds. Place task build copies/caches on L:, seek approval before changing persistent agent TEMP configuration.

## Acceptance checklist
1. Separate per-repo remote heads/branches/PR audit, backed-up deleted ref SHAs, pull completion.
2. API/contract/route/UI feature matrix and public/private negative checks.
3. Expo58 dependency/Go metadata and run/build profile checks.
4. Explicit real-mode behavior; missing backend features unavailable; no implicit mock fallback.
5. External secrets/config validation, credentials excluded from frontend artifacts and logs.
6. Source baseline/modified/rollback proof; actual tests/exports; direct per-repo main pushes.
7. Jenkins exact pinned source/gates/artifacts; compatible promotion; live acceptance and limits recorded.

## Self-review
No new business APIs, fabricated data, cross-schema coupling, role header trust, secret rotation or new public internal ports are permitted. Reusing current Jenkins/dependencies differs from recreating the installation. SDK export/health is not device or full-domain acceptance. Deployment is complete only after terminal Jenkins success and independent post-job proof.
