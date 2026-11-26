# BảoTin Infrastructure

## Application and website monitoring

Public authenticated dashboards: [admin domains](docs/ADMIN_DOMAINS.vi.md) —
`prometheus.rimiprod.me` (Grafana), `sonarqube.rimiprod.me`, `jenkins.rimiprod.me`.
Existing tunnel routes to loopback origins; raw metrics/exporters remain private.

Windows primary monitoring: [runbook](monitoring/README.vi.md), Prometheus9090,
Grafana3000 and Blackbox9115, all loopback-only. Twelve readiness/HTTPS probes,
provisioned overview dashboard, TLS/availability rules and negative tests; no app
restart or public Actuator route. Hidden consoles and only the required Prometheus
datasource avoid visible plugin windows. Remaining instrumentation/routing:
[monitoring TODO](monitoring/TODO.md).

## Kubernetes / K3s deployment method

FullStack now includes `DEPLOY_TARGET=k8s|k3s` alongside the unchanged
`jar` default and Docker/VPS targets. Kustomize profiles, external Secret/model
PVC/registry configuration, scoped namespace RBAC, immutable artifact packaging,
guarded rollout/rollback and Cloudflare cutover steps:
[K8s/K3s runbook](kubernetes/README.vi.md).
The runner does not switch the running primary app, change DNS/tunnel, implicitly
install a cluster or certify a live cluster from source-only tests. `DEPLOY_RELEASE=false`
still skips the deployment node before scheduling.
Both isolated server profiles passed real application/network/web/APK and
failed-candidate rollback acceptance: [2026-11-04 record](docs/k8s-k3s-acceptance-2026-11-04.md).

## Canonical HTTPS web entry — 2026-10-30

Known public HTTP requests forwarded by the existing edge/tunnel redirect308 to
the exact HTTPS web origin before loading the app. HTTPS responses set host-only
HSTS; no includeSubDomains/preload affects other sites. Loopback health/dev hosts,
Gateway CORS/authentication and tunnel/DNS configuration remain unchanged.
`WEB_PUBLIC_ORIGIN` is an optional exact HTTPS origin in the external deployment
file; the existing BảoTin public origin is the backward-compatible default.
Invalid configured origins fail before staging/stopping any runtime. Regression:
`node --test direct/web-server.test.cjs` and
`pwsh -NoProfile -File direct/Test-WebPublicOrigin.ps1`.

## Verified unchanged-JAR reuse — 2026-10-30

JAR/web promotion reads each successful immutable child build's
`release-evidence.json` and verifies the staged JAR SHA256. An identical JAR in
the current release can be hard-linked into a fresh release on the same volume,
avoiding duplicate disk allocation; changed/missing artifacts still download and
must match CI evidence. Reparse paths and existing destinations are never reused
or overwritten. Web/APK archives always download normally. Never edit linked JARs
in place: publish a new release instead. Existing PID/start-time, key/database,
readiness, approval, rollback and Jenkins disk thresholds remain unchanged.
Windows regression: `pwsh -NoProfile -File direct/Test-JarArtifactReuse.ps1`.

## Optional verified Android download

FullStack forwards `ANDROID_DOWNLOAD_ID` only to the Frontend child. It identifies
an existing external signed-APK publication by the exact Frontend canonical source
SHA256; Frontend rechecks hash/size/source before including the APK and public
download manifest in its normal web artifact. The default retains the reviewed
Android publication `2db533d82f01bfe8ef75b40f76034cb13bc29d88d7f5b5a11d938955f271c211`
independently of newer web revisions; it does not relabel the Android binary as
newer source. A selected missing/corrupt publication fails the build. Explicitly
empty means no APK publication,
not a placeholder link. See `../Frontend/ANDROID_BUILD.md`. No additional host
port, service privilege, signing secret in Git or direct runtime upload is added.

## Current integration acceptance — 2026-10-27

FullStack **#27 SUCCESS** deployed the reviewed Gateway/Expo API integration.
Auth, Notification/Watchlist, real text/URL Scan/History and Report/Evidence/Moderation
passed real-domain acceptance. The temporary test ADMIN/MODERATOR were demoted to
USER with sessions revoked and audit retained. Eleven owned loopback runtimes are
readiness UP. See [acceptance matrix](docs/api-integration-acceptance-2026-10-27.md)
for immutable child builds, proof paths, rollback and remaining product work.
Earlier checkpoints below are historical; documentation updates do not change Jenkins pins.

## Public deployment — 2026-10-24

FullStack#24 is terminal SUCCESS at reviewed Infra code `9381946`: all eleven child
release jobs passed before JAR/web promotion. Eleven owned, non-elevated, loopback
runtime processes are readiness UP after Jenkins exits; artifact hashes match the
successful immutable child builds. `https://baotin.rimiprod.me/` returns200 through
the existing shared Cloudflare Tunnel; API remains under `/api`. Unauthenticated
or invalid-token `/api/v1/auth/me` returns401 Problem Details; untrusted CORS403;
public `/internal/v1/inference` and `/actuator/env` return404, not SPA HTML.
Moodle remains200 and the shared tunnel configuration was not changed.

The deployment-node scheduler job-object issue is fixed by the non-elevated
job-free launcher; see [windows-agent](jenkins/windows-agent/README.md).
Windows model artifacts must be readable by the ordinary deployment user, not
Administrators-only: both real ONNX packages passed checksum/load/parity/warm-up
and readiness under that user. Runtime read-only ACLs were verified by denied
write probes at the model root and both artifact directories. No secret ACLs,
database keys, volumes or tunnel credentials were changed. Startup remains manual;
apply the preboot ownership retirement procedure in [MANUAL_START](direct/MANUAL_START.md)
only after proving no owned process or occupied app port remains. Do not disable
the PID/start-time guard to work around stale state.

Cloudflare topology and operational steps: [CLOUDFLARE_TUNNEL.vi.md](docs/CLOUDFLARE_TUNNEL.vi.md).
Older checkpoints below are historical. A documentation-only commit does not
advance the reviewed Jenkins release pin.

## CI stack consolidation — 2026-10-16

Accepted on2026-10-19: [ci-stack/README.vi.md](ci-stack/README.vi.md), one active Compose
stack/four healthy containers, existing Jenkins/dind/Sonar/DB volumes preserved.
Sonar host9001/CI internal9000; external mobile-network login-page check confirmed
by the operator. Archive creation was explicitly waived; existing snapshot images
were reused. Actual rollback to the original containers and re-adoption both passed.
Earlier standalone Compose instructions below are historical; never launch both
management paths. Stopped originals remain available for the tested rollback path.
Local `baotin-alt` app containers/images were removed by request; dependencies,
volumes, deployment source and the SSH deployment option remain.

## Accepted Jenkins deployment — 2026-10-19

FullStack#12 is terminal SUCCESS: eleven child builds pass numeric Sonar policies
before JAR/Expo packaging, followed by security/SBOM gates and approved `jar`
promotion on the non-elevated deployment node. All ten backend processes and web
are owned/non-elevated/readiness UP after the job exits; deployed JAR/web hashes
match gated artifacts. DB remains schema10/readerFloor2/BK1; keys unchanged.
Release tests are deliberately external_verify, not a claimed unit-test run.
Exact domain CORS passes and untrusted origin returns403. Cloudflare-ready origins
and manual startup: [direct/HOST_READY.vi.md](direct/HOST_READY.vi.md).
No DNS/tunnel or VPS/SSH connection was performed. Eight business services remain
scaffolds and frontend integration remains tracked in its TODO.

## Quality/deployment targets — 2026-10-13

SonarQube gates: [sonarqube/README.md](sonarqube/README.md). Verdicts require complete
numeric metrics, correct project/analysis and no lowered thresholds; known debt is
explicit, not a zero-bug claim. FullStack now builds children sequentially.
`DEPLOY_TARGET`: `jar` (default), `docker` (complete gated CI artifacts), `vps`
(immutable registry manifests over verified SSH; unconfigured until operator setup).
`DEPLOY_RELEASE=false` never schedules deployment or connects SSH.
VPS setup: [vps/README.vi.md](vps/README.vi.md). Local Docker requires the external
`DOCKER_ALT_ENV_FILE` path in jar.env. Cloudflare/tunnel remains deferred.

## Accepted deployment — 2026-10-08 (historical)

FullStack#8 terminal SUCCESS promoted ten JARs + Expo web. Auth#14 passes73 tests,
Gateway#10 passes24, eight scaffolds add8 and Frontend#8 adds25:130 pass/no failures/skips.
Actual DB migration is schema10/readerFloor2/BK1, separate migrator/runtime credentials;
persistent keys are unchanged. Primary all11 owned non-elevated processes are readiness UP.
Docker alternative all11 images/health/scans/SBOMs and live JWT/TOTP/EMAIL/refresh/
fault-rollback pass; alternative is manually stopped, preserving images/volumes/dependencies.
Google/Facebook real Web consent passed; native/link/cancel live proofs are not inferred.
Startup is manual; domain/tunnel and business functionality of eight scaffolds remain later.
Retain reader2 for rollback, never reader1/down-migration. See Auth's
`IMPLEMENTATION_STATUS.md`, `EMAIL_MFA_RUNBOOK.md` and Infra's `direct/MANUAL_START.md`.
All older checkpoints below are historical; doc-only pushes do not advance Jenkins pins.


Docker Compose, PostgreSQL provisioning, Jenkins deployment support và observability cho BảoTin.

## Ranh giới

- Quản lý Jenkins/dind/Sonar bằng `ci-stack/`, giữ nguyên dữ liệu/volumes hiện hữu.
- Chỉ Gateway được publish; database, Redis, Kafka và internal services nằm trong private network.
- Mỗi service có runtime DB role/schema riêng; secret thật nằm ngoài Git.
- Release dùng immutable image digest, health/smoke test và rollback không xóa volume.

Jenkins chi tiết: [`jenkins/README.md`](jenkins/README.md). Deployment hiện hữu cần được harden theo master plan trước production-like use.

Stack team mới: [`team/README.md`](team/README.md), dùng digest, secret file/config-tree,
Windows agent riêng và compatibility-gated rollback. Legacy `deploy/*.sh` không phải tuyến
release cho Auth/Gateway hiện tại; không dùng nó để bypass các gate.

Live external-file checks:
- `team/Test-LiveProviderCredentials.ps1`: Google/Facebook/Firebase credential controls;
  user OAuth consent is a separate check and tokens are not written to reports.
- `team/Test-LiveFirebaseBinding.ps1`: real dev-project fictional-number ID token through
  Gateway/Auth/Admin SDK, owner/session/replay checks; no real SMS or team/prod enablement.
- `team/Test-LiveGmailDelivery.ps1 -TestRetry`: actual worker/outbox, sender-self mail,
  controlled fixture-local connection failure and retry, then fixture/volume cleanup.
- `team/Test-ProviderProbe.ps1`: synthetic transport/report cases, not live proof.
Android/iOS Firebase client files are checked for project matching but not modified or
copied into the frontend. Private service-account JSON remains backend-only.


## Primary full-stack deployment

Infra/Jenkinsfile builds ten backend JARs plus the Expo web artifact, then promotes them through the isolated Windows node with maintainer approval. See direct/README.md for external configuration, PostgreSQL migration prerequisites, loopback listeners and rollback. BUILD_DOCKER_IMAGES is false by default; container images remain the manual alternative. ci/README.md owns the versioned pipeline generator. No Jenkins controller rebuild/recreation is required.

## Current deployment checkpoint — 2026-10-07

Actual FullStack#6 is terminal SUCCESS with all eleven child jobs successful and118
tests (Auth62/Gateway23/scaffolds8/frontend25). Post-job ten JARs + web remain live,
PID/start-time ownership verified and all readiness endpoints UP. Twelve Jenkins jobs
pin reviewed SHAs before Jenkinsfile retrieval; doc-only commits do not auto-advance pins.
Docker PostgreSQL/Redis dependencies use persistent volumes and separate DB roles.

The complete Linux-container alternative is in [alternative/README.md](alternative/README.md):
eleven immutable images, zero HIGH/CRITICAL findings, eleven SBOMs and actual JWT/MFA/
refresh/fault-rollback acceptance. It is intentionally stopped after verification;
no volume was deleted and the primary runtime/controller/dind/CLIProxyAPI was preserved.
Domain/DNS/tunnel comes later. Fresh Facebook consent remains an external interactive
check; do not equate image/deployment acceptance with that provider proof.

### Host reboot recovery checkpoint

The host rebooted after the successful release. Native Windows JAR/web and remoting
processes do not survive a reboot; automatic startup has not been configured yet.
The existing node was reconnected non-elevated. The same successful FullStack#6 child
artifacts were restored through the reviewed deploy runner after proving old ownership
records were pre-boot, no owned process survived and all runtime ports were free.
Only stale metadata was retired; the unrelated process with a reused PID was preserved.
Fresh post-recovery checks pass all11 owned non-elevated processes/readiness endpoints
and actual deployed HTTP JWT/MFA/recovery/refresh-reuse/forged ADMIN tests. Existing
Docker controller/dind/CLIProxyAPI IDs/images remain unchanged; their start times reflect
the observed host/Docker restart, not a project-triggered restart. Autostart remains a
separate operator choice, not a claimed completed gate.

## Public domain guide (manual, not provisioned)

[Cloudflare Tunnel + Tailscale](docs/CLOUDFLARE_TUNNEL.vi.md) hướng dẫn đăng ký,
đổi nameserver, named tunnel/token-file, split API/Web routes trên cùng hostname và
private Tailscale origins. Không thay DNS, tunnel hoặc existing Serve routes tự động.
