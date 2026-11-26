# BảoTin Auth Service

## Snapshot privacy and interruption correctness — 2026-11-01

Identity snapshots defensively retain encrypted credential bytes, compare/hash
them by content and redact credential/contact fields from diagnostic strings.
Proxy CIDR values keep the same trust boundary. Web cookies retain their existing
30-day lifetime using long arithmetic; missing admin targets retain404 behavior.
An interrupted Kafka publisher preserves interruption, stops the claimed batch and
retains the existing retry/lease-recovery path. No HTTP contract, schema, role/MFA
policy or persistent key changes. Focused regressions and the complete JDK21
wrapper suite are required; local tests alone do not imply CI promotion.

## Admin negative regressions — 2026-10-28

The operator lifecycle test now calls the actual admin HTTP boundary for guests,
USER, MODERATOR, expired step-up, revoked sessions and an operator-demoted ADMIN.
Only the active verified TOTP ADMIN with fresh step-up succeeds; existing self-edit,
ADMIN-grant and database-privilege denials remain covered. Gateway performs a
separate ADMIN role check; it does not replace Auth's live session/role checks.

## Accepted deployment — 2026-10-08 (current)

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


Nguồn sự thật cho user, password, OAuth identities, JWT/refresh sessions, RBAC, SMTP, TOTP và phone verification proof.

## Trạng thái

Đã triển khai password/JWT/refresh session, Web cookie/CSRF, TOTP/recovery, verification/reset, SMTP outbox, operator/admin lifecycle, Google/Facebook, Firebase phone dev-only và key rotation. Live provider smoke và deploy hardening còn đang thực hiện; xem [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md).

## Chạy

Yêu cầu JDK 21 và PostgreSQL khi persistence được bật.

```powershell
.\gradlew.bat test
.\gradlew.bat bootRun
```

Secret thật không nằm trong repo; chỉ cấu hình placeholder ở `.env.example`. App chạy ngoài test yêu cầu RSA PKCS#8 private-key file, key ID và encryption-key file 32 byte; không tự tạo khóa mặc định.

## Database và kiểm thử

DB có schema `auth` tạo trước bằng provisioning. Chạy job `migrateDatabase` bằng migrator riêng, cấp grant, rồi khởi động Auth runtime với `DB_MIGRATION_ENABLED=false` và không mang migrator credential. Test harness dùng Testcontainers PostgreSQL, không dùng H2.

```powershell
.\gradlew.bat test bootJar --no-daemon --max-workers=1
```

## Token transport

USER email-OTP implementation/rollout: [EMAIL_MFA_RUNBOOK.md](EMAIL_MFA_RUNBOOK.md).
It is opt-in, never a TOTP/ADMIN fallback; new schema/reader promotion is a separate
verified release step. Current live schema9 remains unchanged during validation.

- Native: `/api/v1/auth/login`, `/refresh`, `/mfa/challenges`; refresh token lưu trong secure storage của React Native.
- Expo Web: `/api/v1/auth/web/login`, `/web/refresh`, `/web/mfa/challenges`, `/web/logout`; gửi `credentials: include`, Origin allowlisted và `X-CSRF-TOKEN` cho refresh/logout.
- Web response chỉ trả access/CSRF token; refresh nằm trong cookie `__Host-baotin-refresh`, Secure/HttpOnly/SameSite=Lax.
- Sau confirm enrollment, đổi mật khẩu hoặc logout-all, mọi phiên cũ mất hiệu lực; client phải login lại.

OpenAPI: `../Contracts/openapi/auth-service.yaml`. Mọi endpoint chưa implement vẫn nằm trong status file, không trả dữ liệu giả.

Runbook: [`OPERATOR_RUNBOOK.md`](OPERATOR_RUNBOOK.md), [`OAUTH_RUNBOOK.md`](OAUTH_RUNBOOK.md). Provision trước Flyway và cấp grant sau Flyway; runtime không mang owner/migrator/operator privileges. Test thực thi cả happy path và negative DDL/cross-schema/ADMIN grant bằng runtime role thật.

Phone/key runbooks: [`PHONE_RUNBOOK.md`](PHONE_RUNBOOK.md), [`KEY_ROTATION_RUNBOOK.md`](KEY_ROTATION_RUNBOOK.md). Firebase Spark chỉ cho fictional-number proof trong dev/test; không bật trên team/prod.

Delivery: [`DELIVERY_RUNBOOK.md`](DELIVERY_RUNBOOK.md). User lifecycle + Outbox được ghi cùng transaction; SMTP/security notices dùng lease và retry không giữ DB transaction qua network I/O. Tests dùng Mailpit/Kafka thật; Gmail worker đã được kiểm chứng gửi/retry tới sender-self và xóa payload sau gửi thành công. Consent/callback Google/Facebook vẫn là bước riêng còn chờ tương tác.

Rà soát hiện tại: [`COMPLETION_AUDIT.md`](COMPLETION_AUDIT.md), [`SECURITY_REVIEW.md`](SECURITY_REVIEW.md). Hai wrapper JDK21 vừa chạy đủ75 tests (Auth62/Gateway13), không failure/error/skip. Jenkins source pin trước khi đọc Jenkinsfile và node deploy không elevated đã được kiểm chứng; build/scanner thành công vẫn không thay thế promotion thật.

## Security release gate

After tests and image build, run `IMAGE_REFERENCE=YOUR_BUILT_IMAGE sh ci/security-gates.sh`
from this repo (POSIX shell). Jenkins runs the same script before publication. Scanner
images are pinned by digest; overrides must also be digests. It redacts secret findings,
scans the packaged JAR with Trivy `rootfs`, scans the exported image (OS + JAR) without
mounting a Docker socket, and emits `build/security/sbom.cdx.json`. HIGH/CRITICAL findings,
missing JAR/OS coverage or scanner errors fail the release. No broad test-folder exclusion
or vulnerability ignore list is used. The two line-level Gitleaks annotations explain
runtime-generated test keys and the intentionally unsigned JWT rejection fixture.

Security patch overrides currently retain Boot 4.1.1: Tomcat 11.0.25, Jackson 2.21.7/3.1.7
and Bouncy Castle 1.86. Remove overrides only after a newer managed BOM and fresh tests/scans.
Windows regression runner: `../scripts/Test-AuthGatewaySecurity.ps1`; its synthetic
secret, old vulnerable JAR/image and empty-coverage cases must all fail their gates.


## Jenkins JAR build/release

Pipeline lấy source từ main; maintainer phải duyệt/bảo vệ revision trước khi đọc Jenkinsfile, chạy JDK21 bootJar, secret/dependency scan và SBOM cho JAR; khi BUILD_DOCKER_IMAGE=true thì build/scan image và publish ghcr.io/vietluccuu/auth-service theo commit SHA/digest. Lane release này không chạy test (test chạy ở máy dev và ở lane PR GitHub Actions) và không có auto-deploy qua SSH. Orchestration/deployment được quản lý tại Infra; Docker image cũng dùng được cho deployment thủ công. GitHub Actions chỉ verify trên PR/manual, không deploy production khi push.


## Current verification checkpoint — 2026-10-07

FullStack#6 actually deployed ten backend JARs + Expo static web and finished SUCCESS;
all eleven owned processes/readiness endpoints remain UP after the job finished.
Auth#12 passes62 tests; Gateway#8 passes23; eight scaffolds add8 and frontend25:118 total.
Real deployed JWT/MFA/recovery/refresh-reuse/forged-ADMIN checks passed. Durable Docker
PostgreSQL/Redis, distinct DB role passwords and external key files are configured.
Twelve Jenkins jobs pin reviewed source SHAs before reading Jenkinsfile; later doc-only
commits do not automatically advance those pins. Google Web consent passed; fresh
Facebook consent remains pending. Domain/tunnel rollout follows deployment acceptance.
See IMPLEMENTATION_STATUS.md and ../Infra/alternative/README.md for the remaining gates.
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

## SonarQube quality gate

Release: checkout/preflight, compile main classes + Sonar, chờ quality gate PASS,
sau đó mới `bootJar`, security/SBOM và Docker/publish tùy chọn. Java cần bytecode
để phân tích nhưng không đóng JAR trước gate. Không chạy test/JUnit/JaCoCo ở release;
unit/integration tests vẫn ở verify lane và máy dev. Không lấy coverage cũ để chứng minh
release. Gate vẫn bắt buộc metric bảo mật/chất lượng, bind analysisId, không bỏ qua lỗi.
Policy và regression: `../Infra/ci/README.md`. Scaffold chưa có nghiệp vụ.

## Release dependency gate — 2026-10-28

Jenkins FullStack28/Auth41 identified new HIGH advisories in transitive Snappy and Guava. Runtime constraints select snappy-java1.1.10.9 and Guava33.7.2-jre. This dependency-only patch preserves APIs/auth/events; scanner policy remains enabled. Repo wrapper tests and packaged-JAR scans must pass before promotion.
