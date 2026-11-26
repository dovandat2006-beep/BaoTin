# BảoTin API Gateway

## Bounded catalog matching and proxy values — 2026-11-01

Malformed long endpoint configuration now fails closed without recursive regex
stack overflow. The accepted route syntax, service namespaces and authorization
allowlist are unchanged. CIDR records compare/hash their address bytes by content
and retain defensive copies; literal parsing and trusted-proxy boundaries remain
unchanged. `StaticBugRegressionTests` covers the original failures and the existing
full wrapper suite remains required before promotion.

## Admin edge boundary — 2026-10-28

GET `/api/v1/auth/admin/users` and PATCH user `role`/`status` require a verified
ADMIN JWT at the edge, not authentication alone. Guest/USER/MODERATOR and spoofed
identity headers never reach the Auth upstream. Auth still checks current role,
account/session revocation, verified email, TOTP and recent step-up independently;
the edge JWT check does not replace these live checks. Unknown admin operations
remain deny-by-default. `AdminBoundaryTests` exercises actual HTTP and signed JWTs
against a deliberately permissive upstream, including tampered-role rejection.

## Report/Evidence and Moderation routes

The reviewed Report-Service endpoints are explicitly listed in the shipped endpoint configuration; a service address alone enables no endpoint. Owner routes require JWT; moderation requires MODERATOR/ADMIN at both edge and service. External manual endpoint lists replace defaults and must explicitly retain the desired reviewed Report operations, with anonymous report access rejected. Unknown actions/internal APIs remain denied. CORS allows `Idempotency-Key`; only multipart POST `/api/v1/reports/{uuid}/evidence` receives an 11 MiB body bound. Other request bodies remain 64 KiB. Configure `REPORT_SERVICE_URI`; no database/business logic is added to Gateway.

Verification: 13 route/catalog/proxy tests pass with real signed JWT and HTTP upstream, using a Redis fixture for the new route tests. Existing 15 Docker/Redis integration tests require Docker, unavailable on this host; their environmental failure is not a passing result. Full suite remains required in the deployment/CI environment.

## Analytics routes

The reviewed Analytics admin read API is enabled by default: `application.properties` registers the service as `gateway.catalog.services.analytics=${ANALYTICS_SERVICE_URI:http://127.0.0.1:8189}` and explicitly lists three exact GET paths:

- `/api/v1/analytics/admin/summary`
- `/api/v1/analytics/admin/queue`
- `/api/v1/analytics/admin/counts`

All are non-anonymous. The Gateway requires `MODERATOR` or `ADMIN` at the edge, and Analytics-Service repeats the role check on every request. Analytics composes the response from the Report, Threat and Reputation services; it keeps no analytics database. Unknown Analytics paths, near matches, and non-GET methods remain denied by the exact catalog and default deny policy. The Gateway adds no business logic and has no database; it keeps correlation IDs, CORS, rate limiting, body limits and JWT/JWKS validation.

Deployment note: these routes require the Analytics-Service composed read API. The earlier DB-only Analytics scaffold returns empty metrics and is not sufficient for production acceptance.

Regression covers shipped route registration, exact empty/custom replacement, and real signed-JWT HTTP authorization against a fixture upstream. Run the full JDK21 wrapper suite (including real Docker/Redis tests) before promotion.

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


Edge duy nhất cho Expo Web và React Native. Gateway sẽ chịu trách nhiệm route allowlist, JWT/JWKS validation, CORS, Redis rate limiting, correlation ID và request limits; không chứa business logic hoặc truy cập PostgreSQL.

## Trạng thái

Spring Cloud Gateway WebFlux đã có route Auth, RS256/JWKS validation, method/path allowlist, CORS, Redis fixed-window limit, giới hạn body 64 KiB và loại header danh tính client. Test dùng signed JWT, upstream fixture và Redis container thật.

Regression tests cover identical CORS-header deduplication when Auth also emits CORS,
and exact IPv6 host-proxy traversal without trusting adjacent peers or tailnet clients.
Live Tailscale HTTPS Web login/refresh/CSRF/logout is verified through the team stack.

## Chạy

```powershell
.\gradlew.bat test
.\gradlew.bat bootRun
```

Xem `../BaoTin_Backend_Architecture_Master_Plan.md`, `AGENTS.md` và contracts trong `../Contracts` trước khi thêm route.

## Cấu hình

Service catalog + endpoint thủ công + HTTPS/Cloudflare: [MANUAL_ROUTES.md](MANUAL_ROUTES.md).
Catalog có sẵn các địa chỉ services, nhưng không tự mở endpoint scaffold. Service restart
tại cùng địa chỉ tự kết nối lại; danh sách endpoint/policy được duyệt trong file ngoài Git.

Điền `.env.example` bằng cấu hình bên ngoài Git. `AUTH_SERVICE_URI` trỏ Auth và `AUTH_JWKS_URI` trỏ endpoint JWKS nội bộ. Gateway không có PostgreSQL. Redis lỗi làm POST/PATCH Auth trả 503 thay vì bỏ limit.

Không route `/internal/**`, inference, actuator quản trị hoặc endpoint nghiệp vụ scaffold. Các hạng mục provider/deploy/rotation chưa hoàn thành được ghi ở `../Auth-Service/IMPLEMENTATION_STATUS.md`.

Đọc [`PROXY_RUNBOOK.md`](PROXY_RUNBOOK.md) trước cấu hình Tailscale/ingress. Chỉ trust CIDR của peer proxy/Gateway, không trust client tailnet. Gateway giới hạn IP trước JWT và principal đã xác thực sau JWT; Auth vẫn kiểm tra phiên và ownership.

## Security release gate

After tests and image build, run `IMAGE_REFERENCE=YOUR_BUILT_IMAGE sh ci/security-gates.sh`
from this repo. Jenkins uses the identical script before publication: pinned Gitleaks,
packaged-JAR dependency scan, image OS/JAR scan and CycloneDX SBOM. HIGH/CRITICAL findings
and missing scan coverage fail the release. No scanner receives the Docker socket.
Jackson uses patched 3.1.7 on the Boot 4.1.1 release line. Windows regression runner:
`../scripts/Test-AuthGatewaySecurity.ps1`. Successful local gates are not evidence that
the live Jenkins promotion job/Windows agent has been configured or executed.


## Jenkins JAR build/release

Pipeline lấy Jenkinsfile từ SHA đã được duyệt và pin trước khi tải source; GitHub main hiện chưa được bảo vệ. Pipeline chạy JDK21 bootJar, secret/dependency scan và SBOM cho JAR; khi BUILD_DOCKER_IMAGE=true thì build/scan image và publish ghcr.io/vietluccuu/api-gateway theo commit SHA/digest. Lane release này không chạy test (test chạy ở máy dev và ở lane PR GitHub Actions) và không có auto-deploy qua SSH. Orchestration/deployment được quản lý tại Infra; Docker image cũng dùng được cho deployment thủ công. GitHub Actions chỉ verify trên PR/manual, không deploy production khi push.

## Current checkpoint — 2026-10-07

Gateway#8 passes23 JDK21 tests and actual Jenkins JAR security/SBOM gates; Auth#12 passes62.
FullStack#6 actually deployed all ten JARs + web and is terminal SUCCESS (118 tests total).
All eleven owned processes/readiness endpoints remain UP after Jenkins completion.
Real USER/JWT, forged ADMIN403, TOTP/recovery and refresh-reuse revocation pass.
Manual endpoint authorization and automatic stop/restart reconnection are tested.
Reviewed Jenkins SHA pins are not auto-advanced by subsequent documentation commits.
Domain/tunnel rollout remains deferred; Docker alternative is tracked in Infra/alternative.
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
## Text/URL Scan integration — 2026-10-22

The default catalog now contains the five reviewed Scan v1 routes (guest scan, JWT-only history) from `Contracts/openapi/threat-service.json`. Model inference stays internal. `ScanRoutesTests` exercises these shipped defaults with a real Redis container, upstream forwarding, forged-header removal, history authorization and CORS. The existing manual catalog example retains Notification/Watchlist and includes Scan v1; external list configuration replaces the defaults and must preserve every desired route. This source change does not claim a Jenkins or production rollout.

## Integrated public routes — 2026-10-27

The shipped default endpoint list now includes the reviewed Notification/Devices/Watchlist contract alongside Scan/History and Report/Moderation. Every Notification operation requires a signed Auth access JWT; configuring anonymous Notification or Report routes is rejected. Service address discovery still exposes nothing by itself; custom endpoint lists replace the defaults and must explicitly preserve the required public contracts. Inference, Reputation internal endpoints and scaffold Search/Campaign/Analytics APIs remain closed.

`NotificationRoutesTests` verifies the shipped defaults (not a test-only catalog) with real HTTP and signed JWT: all 13 Notification/Watchlist operations forward, spoofed identity headers are stripped, anonymous/unknown/private/wrong-method requests are denied. Backend ownership remains independently enforced by Notification-Service. This source proof is separate from Jenkins/public-domain acceptance.

## Guest Search — 2026-11-11

Exact GET /api/v1/search is anonymous and forwards to SEARCH_SERVICE_URI; existing
edge rate limits/header sanitization remain. No wildcard, mutation or internal
Search route is exposed. Contract: ../Contracts/openapi/search-service.json.
