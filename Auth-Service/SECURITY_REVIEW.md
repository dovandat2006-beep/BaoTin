# Auth/Gateway — rà soát hoàn tất, 2026-10-06

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


## Current checkpoint — 2026-10-07

Fresh complete JDK21 wrappers pass Auth62/Gateway13, with zero failures/errors/skips.
Direct source review covers JWT/session/version/role checks, MFA/recovery/step-up and
parameterized PostgreSQL identity/admin boundaries; it does not establish zero bugs.
Current JAR hashes equal the earlier real-JAR acceptance inputs. See
`COMPLETION_AUDIT.md` for the current requirement-by-requirement evidence matrix.

DEPLOY-01's old wrong-owner/main+develop findings are resolved by12 explicit reviewed
source pins before Jenkinsfile retrieval. GitHub main is still unprotected. Dind is
currently running with the previously tested targeted persistent PID guard. DEPLOY-02's
missing-node finding is resolved: the Windows node is online and non-elevated, with
one EXCLUSIVE executor, a persisted SHA-checked queue guard and actual negative
scheduling/fail-closed/copy-rollback tests. Actual target promotion remains open.
CONFIG-01 remains open for runtime keys/passwords/dependencies: `jar.env` exists only
as a public draft. Google Web passed; fresh Facebook and live native/link/cancel OAuth
paths remain open. Earlier evidence/counts/findings below are historical, not current state.

**Chưa hoàn thành toàn bộ goal.** Rà soát này giữ nguyên yêu cầu JWT, RBAC, SMTP,
authenticator 2FA, PostgreSQL isolation, provider integration và deployment đã chốt.

## Bằng chứng hiện tại

- Hai wrapper JDK21 chạy lại `test bootJar --no-daemon --console=plain --max-workers=1 --rerun-tasks`:
  Auth52 + Gateway13 = **65** tests, không có failure/error. Không bỏ test.
- `scripts/Test-AuthGatewaySecurity.ps1 -OutputRoot M:/BaoTin/.changes/auth-completion-review/security-regression`:
  `SECURITY_TESTS PASS cases=6 positive=2 secret=denied vulnerable_jar=denied vulnerable_image=denied empty_scan=denied`.
- Hai positive gates không có secret hoặc HIGH/CRITICAL trong packaged JAR/image OS+JAR;
  CycloneDX SBOM được tạo. Negative controls trả exit1/1/1/3 như yêu cầu.
- SHA256 `/app/app.jar` trong cả `security-check` và `release-check` của hai service khớp
  chính xác JAR hiện tại. Scan không dựa trên image cũ khác artifact vừa test.
- Jenkins thật xác thực và chấp nhận ba pipeline với SHA256 source hiện tại; đây là
  declarative validation, không phải build, scan trong Jenkins hay promotion.

## Đường kiểm soát đã đọc trực tiếp

| Yêu cầu | Source trực tiếp | Test/bằng chứng tương ứng |
|---|---|---|
| JWT RS256, issuer/audience/type/kid/time/use | `security/JwtTokens.java`; Gateway `GatewayConfig.java` | `decoderRejectsWrongAudienceIssuerUseAlgorithmAndKey`, signed-token Gateway tests |
| Session/version/role/MFA kiểm tra ở Auth boundary | `security/SessionGuard.java`, `application/AuthService.java` | disabled/demoted token rejection, refresh reuse/concurrency, logout/revocation |
| ADMIN operator-only; API không grant ADMIN | `application/AdminService.java`, `api/AdminController.java`, V4 + provisioning grants | operator activation requires verified MFA; runtime DDL/cross-schema/ADMIN grant denied |
| TOTP/recovery/step-up không bypass | `security/AuthCrypto.java`, `AuthService.java`, `AdminService.java` | replay/attempt bounds/reset preserves MFA; fresh TOTP for step-up/recovery regeneration |
| Web cookie/Origin/CSRF tách native | `api/WebAuthController.java`, refresh transport checks | actual HTTP cookie rotation, wrong Origin/missing/stale CSRF and transport rejection |
| Không mass assignment hoặc SQL từ input | explicit controller records, parameterized `IdentityStore`/service queries, explicit DB grants | unknown role/status fields, forged headers, cross-user sessions, real DB negative tests |
| OAuth identity không auto-link | `oauth/OAuthProviders.java`, `OAuthService.java`, provider/link methods | signed provider fixture state/nonce/audience/app/browser/PKCE/link/replay tests |
| Phone proof dev-only và đúng owner/event | `phone/PhoneService.java`, `PhonePolicy`, SDK adapter | real fictional-number SDK binding + wrong-owner/replay/revoked checks |
| Key overlap/envelope và rollback compatibility | `SigningKeys.java`, `EncryptionKeyRing.java`; V9 + deployment helpers | persistent-key/key-ID/rewrap tests, populated-DB staging rollback |
| Gateway giới hạn/spoofed header/Redis fail closed | `EdgeProtection.java`, `PrincipalRateLimit.java`, both proxy parsers | actual ingress/tailnet peer counters, header/body/CORS/Redis outage regressions |

Không xác nhận thêm một application authorization bypass trong các đường đã đọc/kiểm
chứng ở trên. Kết quả này không phải khẳng định mọi input, cấu hình hay dependency đều
không có bug; raw provider consent và target deployment phải có bằng chứng riêng.

## Finding/gate còn mở

| ID | Mức độ | Bằng chứng trực tiếp | Thao tác cần hoàn tất |
|---|---|---|---|
| DEPLOY-01 | High trước khi chạy privileged build | Jenkins Auth-Service/API-Gateway: `main_only=False`, `owner_matches=False` | Xác định URL private repo chính xác; bảo vệ job/revision và chọn main-only trước khi Jenkinsfile được tải |
| DEPLOY-02 | Operational gate | `dedicated_deploy_node=False`; dind restart count tiếp tục tăng | Operator chọn targeted dind repair; đăng ký/kết nối node chỉ-deploy rồi chạy pipeline/scanner/promotion thật |
| OAUTH-01 | Verification gate | Local fixture PASS; chưa có user consent/callback thật; Serve9443/9444 đang absent | Xác nhận receiver9444; tương tác tài khoản test Google/Facebook; kiểm chứng Web/native/link/denied paths |
| CONFIG-01 | Deployment gate | Các đường mặc định `C:/Users/ngoan/.baotin/team.env`, secrets DB/Redis/key và agent files hiện absent | Operator cung cấp/cấu hình đường external thực tế; không đặt secret trong Git hoặc chat |

`DEPLOY-01` không được đóng bằng assertion main nằm trong Jenkinsfile: assertion đó chỉ
chạy sau khi nguồn pipeline đã được nạp. Không build/push/deploy từ SCM hiện tại để
thay thế việc xác định và bảo vệ nguồn. Những job/service khác và controller hiện hữu
không bị sửa trong lượt rà soát này.

## Evidence locations và bước tiếp

- `.changes/auth-completion-review/jenkins-state.json`: chỉ status/boolean/branch metadata;
  không chứa raw job XML, credential hoặc response body nhạy cảm.
- `.changes/auth-completion-review/security-regression/`: actual scanner reports/SBOM;
  negative secret fixture được tạo riêng, redacted và đã dọn.
- `.changes/auth-registry-owner/`: sửa namespace/default owner, 13 regressions và copy rollback.
- `.changes/auth-live-oauth/`: local preflight/receiver/script tests, không có live consent claim.
- `.changes/auth-live-provider-probe/`: Gmail/Firebase actual binding proof đã hoàn tất.

Goal chỉ hoàn thành sau khi các gate còn mở có evidence tương ứng, không chỉ sau khi
test local xanh. README/status được đồng bộ với evidence, không tăng phần trăm bằng
việc coi giả lập là provider hoặc deployment thật.


## Updated full-stack checkpoint

All confirmed repositories are initialized/pushed with dodinhhoanganhcmc. Jenkins SCM now matches vietluccuu and main-only for ten backend jobs, Frontend and FullStack; DEPLOY-01's old wrong-owner/develop findings are resolved. Dedicated read-only baotin-github-scm credential is still operator-supplied. Primary deployment is backend JAR + static frontend web on the Windows deploy-only node, Docker images optional. Auth now has a read-only RuntimeDatabaseState CLI for actual DB reader/schema/cipher-floor checks; its real PostgreSQL runtime-role test passes. Latest tests: Auth53 + Gateway13 + eight scaffolds =74 backend tests; frontend25 tests, typecheck and web export pass. Old image/JAR fingerprint evidence is historical after the new Auth helper; Docker alternative must rebuild/scan its current artifact. Actual full-stack Jenkins execution, deploy-node connection, external runtime config and Google/Facebook consent remain pending.
