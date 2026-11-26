# BaoTin Frontend

## SDK release alignment — 2026-11-19

Aligned Expo58.0.26, expo-file-system58.0.14, expo-web-browser58.1.0 and jest-expo58.0.15 after fresh CI rejected the prior versions. Independent locked source copy: Doctor20/20, typecheck,169unit tests and54tooling tests passed. Native APK and Jenkins promotion remain separate acceptance gates.

## Supplied branding — 2026-11-17

Original APK/Web logos are preserved under `assets/branding/originals`. Android launcher/adaptive icons use image1; the responsive Web header/sidebar uses the cropped image2 via `BrandWordmark`. Native header text is unchanged. Android candidate0.1.4/code5 is rebuilt and independently verified before replacing the downloadable APK; a source icon setting alone is not an APK publication claim. Regression: `node --test scripts/branding.test.cjs` and shell/navigation browser checks.

## Frontend redesign release — 2026-11-16

The18-target redesign uses the shared Material3/Roboto/FontAwesome system in DESIGN.md. Runtime code256f817 was published by Jenkins Frontend19 / FullStack25; independent checks confirmed11readinessUP and byte-identical deployed web artifacts. Public URL/SMS checking succeeded. PR6 and latest main were integrated without discarding local redesign work; incoming Analytics overview/trends remain accessible.

Verification: typecheck;154Jest tests;48tooling tests;Doctor20/20;Web/Android/iOS Hermes exports;standard demo/report/search/admin/account lanes;combined60foundation+156quality checks;public12width/theme checks. See REDESIGN_PROGRESS.md for exact evidence and limitations. Android/iOS Hermes export is not physical-device acceptance. No ESLint script is configured. Final close-out documentation is separate from deployed runtime code.


## Android download restoration — 2026-11-10

The Web header retains the verified Android download through an explicit Jenkins
publication pin. Web changes no longer require relabelling/rebuilding an unchanged
Android release. Hash, size, signing metadata, source ID and Gateway-origin checks
remain mandatory. The displayed Android version is independent of the Web revision;
see [ANDROID_BUILD.md](ANDROID_BUILD.md). Regression: `npm run test:tooling`.

## Minimal customer copy — 2026-10-30

Customer screens keep concise action guidance, not architecture/protocol notes.
OAuth callback parsing accepts only Meta's empty `#_=_` marker in addition to an
empty fragment; arbitrary fragments, wrong return addresses and cancellation
remain rejected. PKCE, browser binding, one-use tickets and server authorization
are unchanged. Regression: `npm test -- --runTestsByPath src/api/oauth.test.ts`
and `npm run test:tooling`; actual Facebook consent remains a separate live check.

## Existing API connection increment — 2026-10-29

See [ACCOUNT_API_INTEGRATION.md](ACCOUNT_API_INTEGRATION.md) for the connected
profile/recovery/password/MFA/session/ADMIN user-management/OAuth/device APIs,
notification delete/server unread count and history delete-all. This increment
is being validated locally before push. Public Auth callback/return configuration
is applied and Google/Meta reach their login pages without redirect mismatch;
positive consent and automatic platform push provisioning still require proof;
no provider/device acceptance is inferred from browser transport fixtures.

## Admin boundary — 2026-10-28

Quản trị is absent for guests, USER and MODERATOR in both navigation layouts.
Only a server-profile ADMIN can render that section; moderators use Báo cáo.
Refresh accepts no identity until `/auth/me` succeeds, and logout invalidates
in-flight restoration. Role/session checks run on resume, every 30 active seconds
and after 403; failures discard the session. This is UI freshness, not an access
control boundary: Gateway requires ADMIN and Auth independently validates the
current account/session, verified email, TOTP and fresh step-up on admin requests.
Normal access-token expiry rotates and rechecks the profile. Requests capture the
session generation: late private responses are discarded and an old operation is
never retried under a newly signed-in account. Report workspace state is remounted
when the role changes so old moderation details do not survive demotion.
No client role picker exists in live mode. The ADMIN-only Analytics dashboard
reads the live admin API; upstream failures render an error instead of fake metrics.
Public JavaScript is not treated as a secret.
Same-origin Web refresh/logout reread the current CSRF cookie, so a sequential
rotation in another tab does not leave a stale in-memory nonce. A different API
origin uses only its server-returned nonce; another page's cookie is not sent.
CSRF checks, session-generation guards and one-time-action no-replay stay enabled.
Refresh also requires the same server user ID as the current account; another
tab's account switch expires this tab instead of replaying requests as that user.
This does not claim to fix the separately reported intermittent guest scan error.
Regression commands: `npm test`, `npm run test:tooling`, then export a live-mode
`dist-web-live` and run `npx playwright test -c playwright.admin.config.ts`.
The latter uses explicit transport fixtures to test UI invalidation; actual Auth
and Gateway authorization is verified separately by their JDK21 HTTP tests.

## Current runtime — Expo SDK58 / real APIs

The project-owned `index.ts` registers `./App` with Expo. It keeps run/export
commands bound to this checkout even when a same-machine dependency store is
linked from another drive; the dependency's `expo/AppEntry` must not select that
store's sibling App. The bootstrap is included in typecheck and Sonar analysis.

See [SDK58_REAL_API.md](SDK58_REAL_API.md) for the SDK58-compatible dependency set,
Expo Go/run commands (port18081), real Gateway defaults, Web dev cookie proxy,
environment/secret rules and verification. Normal runs no longer default to demo
Auth/data. Guest Search queries the public URL projection through Gateway; the
ADMIN Analytics dashboard uses Analytics-Service, while real moderation remains in
Reports. Older acceptance checkpoints are historical.

## Android standalone builds

See [ANDROID_BUILD.md](ANDROID_BUILD.md) for guarded live-API Hermes export,
signed native AAB/APK builds with external credentials, SDK/JDK requirements and
the distinction between build checks and user-run device acceptance.

## Public API acceptance — 2026-10-27

FullStack **#27 SUCCESS** deployed Frontend **#22** with the real Gateway origin.
Actual Web registration/password/MFA, watchlist, real scan/history, reports/private
evidence and moderator verification passed on `https://baotin.rimiprod.me` without
API mocking. Separate privileged test fixtures were demoted to USER with sessions
revoked and audit retained. The local development mock default described below is
not the deployed release mode. See the [acceptance matrix](../Infra/docs/api-integration-acceptance-2026-10-27.md)
for exact proofs and remaining SSO/recovery/device/scaffold work.

Expo universal app: Web, Android, iOS. Mặc định dùng API Gateway thật. Đặt
`EXPO_PUBLIC_API_URL` để chọn HTTPS Gateway khác; demo chỉ dành cho test tường minh.

## Chạy

```bash
npm install
npm run web
npm run android
npm run ios
```

Test ngay trên web:

```bash
npm run web
# mở URL Expo in ra, mặc định http://localhost:18081
```

Test trên điện thoại thật: cài Expo Go, máy tính và điện thoại cùng mạng, chạy `npm start`, rồi quét QR.

## Chế độ API

```bash
cp .env.example .env
# sửa EXPO_PUBLIC_API_URL thành Gateway origin thật
npm run web
```

Khi biến chưa được đặt, app dùng Gateway công khai mặc định. Các luồng sau gọi API thật:

- Đăng nhập/đăng ký bằng mật khẩu qua Auth-Service; access token chỉ giữ trong bộ nhớ,
  refresh dùng cookie `__Host-` HttpOnly + CSRF, có xử lý challenge MFA và refresh một lần
  khi gặp 401.
- Danh sách thông báo, unread count, đánh dấu đã đọc/đã đọc tất cả và tùy chọn thông báo.
  UI poll mỗi 30 giây khi tab đang hiển thị; thông báo mới có thể hiện bằng Notification API
  của trình duyệt sau khi người dùng bấm bật. Mở notification watchlist/report chuyển tới
  đúng màn hình tương ứng.
- URL Watchlist: thêm URL, chuẩn hóa phía server, bỏ theo dõi và tùy chọn thông báo.

Kiểm tra, Lịch sử, Báo cáo/Bằng chứng/Kiểm duyệt và dashboard Quản trị đã nối API.
Tra cứu hiển thị trạng thái chưa triển khai thay vì mock. Google/Facebook login/link
và khôi phục tài khoản đã nối Gateway; nghiệm thu consent phụ thuộc cấu hình provider
như ACCOUNT_API_INTEGRATION.md. Đăng ký token FCM thật đã nối API; tự cấp token,
service worker/native push và giao tin khi app đóng chưa được nghiệm thu. Thông báo
web hiện được hiển thị cục bộ từ polling, không giả lập push delivery.

API mode cần HTTPS khi dùng cookie `__Host-` (localhost được trình duyệt coi là secure
context) và origin frontend phải nằm trong `AUTH_ALLOWED_ORIGINS` lẫn
`GATEWAY_ALLOWED_ORIGINS`. Mật khẩu đăng ký tối thiểu 12 ký tự theo Auth-Service. Lỗi API
được đọc từ `application/problem+json`; không hiển thị token hay chi tiết nội bộ.

## Kiểm tra

Historical SDK58 patch alignment2026-11-04: Expo58.0.23 / Constants58.0.15 /
Sharing58.0.22 / SafeAreaContext5.11.1 / jest-expo58.0.14, with the same
React Native0.88.1. Fresh Doctor20/20 and explicit demo/live source tests are
separate from installed-device acceptance.

```bash
npm run typecheck
npm test
npm run build:web
```

## Docker

```bash
docker compose -f docker-compose.prod.yml up --build
# mở http://localhost:8080

# Truy cập từ máy khác trong LAN (mở firewall đúng cổng trước):
BAOTIN_FRONTEND_HOST=0.0.0.0 BAOTIN_FRONTEND_PORT=8095 docker compose -f docker-compose.prod.yml up -d
# mở http://<IP-LAN>:8095
```

Mặc định compose chỉ bind `127.0.0.1`. Chỉ đặt `BAOTIN_FRONTEND_HOST=0.0.0.0` khi thực sự cần demo qua LAN và giới hạn firewall theo `LocalSubnet`.

`Dockerfile` nằm ngay trong `Frontend`; compose tại `Infra` có thể dùng `context: ../Frontend` và `dockerfile: Dockerfile` khi backend được mở khóa.

## CI/CD

- `.github/workflows/ci.yml`: hai job độc lập, đều bắt buộc: `dependency-audit` (high/critical với ngoại lệ giới hạn bên dưới) và `verify` (Expo Doctor, typecheck, unit test, web build, Playwright). Audit đỏ không che kết quả kiểm thử; workflow vẫn failed nếu một job failed.
- `.github/workflows/publish-deploy.yml`: chạy thủ công để build image Docker thay thế, push GHCR bằng tag SHA; không deploy SSH. Jenkins sở hữu release/deploy.
- Giữ Expo/Jest/native modules cùng SDK58; cập nhật `package.json` và lockfile cùng lúc. Không dùng `npm audit fix --force` vì có thể kéo React Native ngoài SDK tương thích.

### Dependency audit — 2026-10-19

Đã cập nhật Expo `57.0.30`, jest-expo `57.0.6`, shell-quote `1.12.0` và source-map-js `1.2.2`. Ngày 20/10/2026, chủ dự án duyệt cho CI qua riêng hai advisory chưa có bản vá upstream: `braces <=3.0.3` (GHSA-vfj7-8cjw-p6xm), `node-forge <=1.4.0` (GHSA-86w9-cpqp-85rv). Ngoại lệ nằm trong `scripts/audit-exceptions.json`, hết hạn **19/11/2026 00:00 UTC**; không tự gia hạn. Đây là chấp nhận rủi ro dependency tooling, không phải đã vá lỗ hổng hay chứng nhận an toàn.

CI vẫn chạy `npm audit --json`, lưu raw report vào artifact và đánh giá bằng `scripts/audit-gate.mjs`. Chỉ đúng package + advisory URL + affected range được chấp nhận; findings lan truyền được đối chiếu về advisory gốc. Critical, advisory high khác, thay đổi range, exception hết hạn, report lỗi hoặc lỗi audit/network vẫn chặn. Moderate vẫn không chặn như ngưỡng `--audit-level=high` trước đây. Test/build/E2E và Sonar không thay đổi. Khi upstream có bản vá: cập nhật dependency/lockfile, bỏ ngoại lệ tương ứng và chạy lại CI.

Kiểm thử policy: `node --test scripts/audit-gate.test.mjs`. Kiểm tra một report: `node scripts/audit-gate.mjs PATH_TO_AUDIT_REPORT.json`. Không đổi release SHA pin Jenkins trong đợt này.

## Phạm vi demo tường minh cho kiểm thử UI

- Kiểm tra `URL`, `SMS`, `PHONE`, `BANK_ACCOUNT`, `EMAIL`, `SOCIAL_ACCOUNT`.
- Điểm tổng hợp từ AI, quy tắc, uy tín và chiến dịch; SMS là kết quả tạm thời.
- Lịch sử lưu snapshot và so sánh với uy tín hiện tại.
- Tra cứu Search projection theo loại đối tượng và risk level.
- Báo cáo cá nhân theo state machine, bằng chứng tối đa 5 file giả lập.
- Watchlist, in-app notifications và notification preferences trong bản test demo tường minh;
  thiếu API env trong bản chạy thông thường vẫn dùng Gateway thật, không fallback mock.
- Typed API adapter cho scan, history, search, report, notification, watchlist, Problem Details,
  pagination và `Idempotency-Key`.

Bản chạy thông thường dùng API thật kể cả khi chưa đặt `EXPO_PUBLIC_API_URL`.
History source selection chỉ dùng mock khi caller chọn demo tường minh; thiếu
capability/client trong chế độ thật trả trạng thái unavailable, không tự đổi sang
dữ liệu mẫu. Nhãn minh họa/bản nháp chỉ được truyền vào màn hình của build demo.
Guest có thể scan; history/watchlist/report yêu cầu đăng nhập. Account recovery,
SSO/linking và native SecureStore đã được nối; provider consent, tự cấp token/giao
FCM, i18n và phát hành mới vẫn có mục nghiệm thu riêng trong `TODO.md`. Dark mode
đã được triển khai. Dữ liệu demo chỉ thuộc build test chủ động, không phải fallback lỗi API.

Mapping UI, domain invariant và endpoint nằm tại `ARCHITECTURE_ALIGNMENT.md`.

UI dùng NativeWind 4.2.7 với Tailwind 3.4.17 vì đây là nhánh ổn định tương thích bộ dependency Expo SDK58 đang được kiểm chứng. `tokens.css` xuất token theo cú pháp Tailwind v4 để tái sử dụng cho web thuần.

Light UI dùng Material Grey 50 `#FAFAFA`, white surface và Material Teal `#00796B`. Dark UI dùng charcoal-teal `#101413`, surface `#171C1B`, accent `#80CBC4`. Lựa chọn theme được lưu cục bộ. Toàn bộ giao diện không dùng gradient.

## Skill cục bộ

7 skill đã dùng được lưu nguyên bản tại `.agents/skills/`. Agent làm việc trong `Frontend` có thể đọc `SKILL.md` và thư mục `references` tương ứng.

Tính năng chưa triển khai được theo dõi tại `TODO.md`.


## Typography

Toàn bộ giao diện dùng Roboto: Regular 400, Medium 500, SemiBold 600 và Bold 700. Font được đóng gói bằng expo-font cho web và native.


## Jenkins deployment

Jenkins release chạy Sonar/gate trước, sau đó typecheck và Expo web export (không test), archive build/web.tar.gz rồi chuyển cho Infra/Jenkinsfile deploy web trực tiếp cùng backend JAR. BUILD_DOCKER_IMAGE mặc định false: Docker/GHCR chỉ là phương án thay thế. Không auto SSH production từ GitHub khi push main. Backend integration trong TODO vẫn là nhiệm vụ riêng; pipeline không coi mock auth là proof của Auth-Service.

## SonarQube quality gate

Source secret gate và Sonar phải PASS trước Expo export/Docker packaging. Release
không chạy Jest/E2E hoặc nạp LCOV cũ; typecheck vẫn bắt buộc. Tests còn nguyên trong
`.github/workflows/ci.yml` và script verify của developer. Vì release không sinh coverage,
Sonar frontend gate kiểm tra bugs/vulnerabilities, không gắn coverage condition vô nghĩa.
Đây là ratchet/SAST, không phải chứng nhận zero-bug hoặc test-pass của ứng dụng.

Report/Evidence/Moderation in API mode uses the same authenticated Gateway client as Notification and Watchlist. See REPORT_INTEGRATION.md. Native refresh tokens use SecureStore; web refresh uses HttpOnly cookies.
## Live Text/URL Scan — 2026-10-22

Scan now calls the real Gateway `POST /api/v1/scans/text` or `/url`; only SMS/Text and URL are selectable. No keyword mock or timer creates a scan result. The server snapshot supplies risk score/level, model labels/versions, rule signals and embedded URL results. Missing/partial AI is explicitly shown and low fallback risk never claims safety. Requests are bounded, cancellable and discard stale results on edit/type change/navigation.

Configure the existing `EXPO_PUBLIC_API_URL` with the Gateway origin to reuse real Web Auth, the in-memory access token and refresh flow. Authenticated history loads/paginates/filters/deletes server snapshots. A demo identity has no access to real history. With an empty API URL, guest scan still calls same-origin `/api/v1/scans/...`; serve the existing nginx `/api/` reverse proxy with a reachable Gateway. The other demo-only surfaces retain their existing scope. Never put `MODEL_SERVICE_TOKEN` or model paths in public Expo variables.

For a local guest website demo, see `../Threat-Service/compose.demo.yml` and its README. Model weights are not in the AI repository; missing models deliberately show fallback. Test graphs are for integration verification only.

Full-stack browser smoke (no API mocking): start the local composition, run `npx playwright install chromium` once, then `npm run test:e2e:scan`. Desktop/mobile projects check real text/URL requests, canonicalization, secret stripping, fallback warnings, history authorization and light/dark overflow, and save screenshots. The normal UI-only E2E suite uses explicit test API fixtures; those are not evidence of running backend inference.

Docker/Compose bakes public configuration at Expo build time: set `EXPO_PUBLIC_API_URL` before `docker compose build`, or pass `docker build --build-arg EXPO_PUBLIC_API_URL=https://your-gateway`. A runtime environment variable alone cannot change an exported JavaScript bundle.

### Dependency compatibility — 2026-10-27

PR integration CI found GHSA-mrhx-c7xc-m3w5 in Expo CLI zod 3.25.76. A dependency override scoped to @expo/cli pins patched zod 4.6.7 without upgrading the Expo SDK or changing audit exceptions. `node --test scripts/expo-zod-compat.test.cjs` checks both installed CLI copies, devtools plugin/output validation, MCP command parameter types and strict unknown-field rejection. Remove the override when the compatible upstream CLI adopts a patched release; rerun audit, these tests, typecheck, Jest and platform exports.

## Live API release build — 2026-10-27

Jenkins `PUBLIC_API_ORIGIN` defaults to `https://baotin.rimiprod.me` and is explicitly passed into the isolated Expo build as `EXPO_PUBLIC_API_URL`. The release validator requires an exact HTTPS origin (no credentials/path/query/fragment), and a post-export gate confirms that origin is embedded in the actual JavaScript artifact. A missing origin fails before creating build containers; a demo/stale-origin export fails packaging. Development/demo browser fixtures retain their explicit existing mode; release never treats mock Auth as live integration proof.

Regression: `node --test scripts/release-api-config.test.cjs`. The same public origin applies to native exports via `EXPO_PUBLIC_API_URL`; private service tokens, database URLs and model paths are not Expo variables. Jenkins still runs source-secret/Sonar gates before packaging and does not promote an unreviewed revision.

## Real API-chain browser test

`npm run test:e2e:integration` uses `playwright.integration.config.ts` and `tests/live/api-chain.live.ts` against the disposable real-service integration harness. It does not intercept or fabricate API responses. The harness registers fixture accounts, uses a real Auth login/TOTP for a fixture moderator, and verifies Gateway, PostgreSQL, MinIO policy/bytes, Kafka, Reputation, Notification and browser Web Auth/watch/scan/report/evidence/logout. Desktop/mobile projects are serial; Auth traces are disabled to keep JWT/cookies out of artifacts. Only the controlled harness sets BAOTIN_LIVE_CHAIN_BASE_URL; do not aim it at unrelated users/data. Existing demo and API-fixture suites remain separate.

## Sonar JavaScript/TypeScript analysis completeness

The release scanner bootstraps Node24.16.0 into the Jenkins tools volume from the official release archive, verifies the pinned SHA256 on every run, and installs locked dependencies without lifecycle scripts so Expo's tsconfig/types are available. The Node executable is explicitly passed to Sonar. No controller image or quality threshold changes are required.

`ci/verify-sonar-analysis.cjs` rejects a scanner ERROR, an absent/empty JS/TS sensor or incomplete file count, even when the legacy scanner exits0. `node --test scripts/sonar-analysis.test.cjs` covers the observed missing-node false-green log and complete/incomplete analysis. The diagnostic scanner log is archived with the existing Sonar artifacts; tests/coverage and Expo packaging remain in their existing lanes.

`tsconfig.sonar.json` enumerates the same application source files with compiler options understood by the installed legacy analyzer. It does not replace Expo's build/typecheck configuration or exclude application files. The analysis validator also rejects reported skipped TypeScript files; the real Expo/TypeScript typecheck remains mandatory before packaging.

## Guest Search — 2026-11-11

Live Tra cứu now calls anonymous GET /api/v1/search via the configured Gateway.
URL DNS-host projection, risk filter and pagination; unknown risk is not LOW.
Loading, unavailable/retry and empty states never substitute demo data. Preserves
existing BaoTin/Roboto tokens and explicit demo screen. Backend requires Search
migration/topic/runtime configuration before release; see Search-Service/README.md.

## Verified Search / SDK patch candidate — 2026-11-12

Expo58.0.24 and the nine-package patch alignment passed the full20-step workflow
on a fresh dependency installation: Doctor20/20, TS, Jest121, tooling48, Python
verifier, Android/iOS Hermes, demo/report/live Search/admin/account browser suites.
GitHub Frontend CI43071238029 is SUCCESS at86ec161. New signed Android0.1.1/code2
is package/signature/Hermes/16KB-alignment verified. Deployment is a separate
Jenkins promotion; this source entry does not predict its result.

## Masked message lookup — 2026-11-12 (candidate, not deployed)

Message history retains readable masked wording, rather than hiding the whole
message. Detected contacts, numeric identifiers, labelled secrets/name/address
fields and URL paths/query values become `***`; automatic pattern masking is
not a guarantee of detecting every possible personal detail. High/critical text
scans publish only masked wording through the owning Threat Outbox. Search
validates the same masking policy, returns optional full `content`, and removes
it on PRIVATE/REMOVED. Existing fingerprint-only documents remain compatible.
Old hidden snapshots without retained input cannot be reconstructed.

Dataset TEXT/URL seeds use the owning Threat operator, SHA-bound JSONL and
per-row transactions/deduplication. SUSPICIOUS is a content warning only; UNKNOWN
is not a safe verdict, and no seed changes report VERIFIED or fabricates scores.
Dataset source stays internal, not a required UI field. Threat/Search additive
V4 migrations and runtime grants must precede promotion. Roll back code without
dropping these populated additive tables. Deployment/import acceptance remains
separate from passing source tests.

## Dataset binary labels — 2026-11-12 correction
SMS0=legitimate (BENIGN),1=spam/fraud (SUSPICIOUS); URL benign=BENIGN,
phishing=SUSPICIOUS. UNKNOWN is reserved for genuinely unlabelled input.
Lookup displays a dataset label independently of aggregate risk scores and report
verification. A normalized message/domain may combine records: a dangerous label
wins over a benign one; absence of a score does not discard an existing label.
Additive ThreatV6 restores previously seeded normal labels; expand Search enum
support before publishing BENIGN events. No report VERIFIED or score is inferred.

## Lookup verdict correction — 2026-11-12
Results now lead with Có rủi ro / Không có rủi ro from the existing warning/label
instead of an absent-score message. Positive warnings or MEDIUM/HIGH/CRITICAL
scores override a benign label. UNKNOWN without a score remains Chưa có đánh giá.
Numeric risk is optional secondary information; no synthetic score is generated.
Regression: searchVerdict.test.ts and real/live-mode Search browser tests.

## Report/Admin interaction fixes — 2026-11-12
Evidence uploads use a bounded120-second client deadline, not the general10-second
JSON timeout. Timeout/cancellation errors are readable; writes are not auto-replayed.
Admin user changes open an immediately visible confirmation Sheet with inline
failure/loading feedback. Fresh MFA/session/role restrictions stay enforced.
Live Admin Statistics reads counts and actionable moderation directly from the
owning Report APIs through Gateway; empty Analytics projections are not used as
the report queue. All five report states have counts, pagination and refresh.
