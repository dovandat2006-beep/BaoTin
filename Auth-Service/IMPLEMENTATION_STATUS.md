# Auth/Gateway implementation status

## Accepted milestone — 2026-10-08 (authoritative)

Auth/API-Gateway and both deployment lanes passed this milestone. All older checkpoints
below are historical, including remaining-gate markers superseded by this acceptance.

| Acceptance condition | Observed result |
|---|---|
| Actual primary promotion | FullStack#8 terminal SUCCESS, all three stages SUCCESS; ten owned non-elevated backend JARs plus Expo web remain readiness UP after job completion |
| Tests/source lineage | Auth#14=73, Gateway#10=24, eight scaffold jobs#8=8, Frontend#8=25:130 pass, zero failures/skips; successful child BuildData and upstream cause match approved FullStack#8 |
| Auth release identity | Running JAR byte-matches authenticated Auth#14 artifact, source14a6250; local Windows JAR has a different build hash and is not substituted |
| JWT/roles/MFA | RS256/JWKS/session/revocation, TOTP/recovery and new USER email-OTP tested; forged ADMIN403; replay/old session401; ADMIN/MODERATOR stay TOTP-only |
| Real migration | Reader2 bridge deployed on schema9 first; protected fresh backup, separate migrator, reviewed grants; actual schema10/readerFloor2/BK1, keys unchanged |
| SMTP/email | Real Mailpit inbox plus HTTP integration; prior actual Gmail send/retry. Final primary and alternative EMAIL HTTP checks use only their generated fixture's encrypted outbox proof, not a claim of Gmail inbox consumption |
| Docker alternative | All eleven immutable images/JARs verified, OS/JAR scans zero HIGH/CRITICAL, eleven SBOMs, nonroot/read-only/scoped secrets; live TOTP/EMAIL/JWT/refresh and invalid-Gateway503 then restored same-session200 pass |
| Final runtime state | Primary eleven processes UP; alternative eleven applications manually stopped; durable PostgreSQL/Redis healthy, volumes/images retained |
| Jenkins boundary | Twelve approved SCM pins, EXCLUSIVE one-executor non-elevated node, SHA-checked guard, signup off and one operator; doc-only commits do not auto-advance pins |
| Provider proof | Real Google and Facebook Web consent accepted. Native/link/denied-consent provider variants have server fixtures, not additional live-provider certification |

Compatible application rollback retains the accepted reader2 JAR/image; never use reader1
or down-migrate after V10. Manual reboot recovery: `../Infra/direct/MANUAL_START.md`.
No automatic startup, domain/DNS/tunnel rollout or eight-service business implementation
is claimed. The frontend artifact builds/serves; its business integration remains separate.
The tested negative cases/scans are evidence, not a guarantee of zero vulnerabilities.
Operator-local evidence is under `.changes/email-release-promotion/`; it is not shipped
with clones. Clone users follow committed runbooks and rerun the gates.


This file tracks the original full goal; green unit tests are not proof of provider or deployment completion.

## Current checkpoint — 2026-10-07 (after actual promotion)

Actual FullStack#6 is terminal SUCCESS: Auth#12 (62 tests), Gateway#8 (23 tests),
eight scaffold jobs#6 (8 tests), Frontend#6 (25 tests): 118 tests total.
Ten JAR security/SBOM gates and the web export passed. The promoted ten JARs + web
remain live with PID/start-time ownership and readiness UP after job completion.
Real deployed HTTP USER/JWT, forged ADMIN403, TOTP/recovery replay rejection and
refresh reuse revocation pass; the generated identity is disabled/anonymized afterward.
PostgreSQL/Redis now run in durable Docker dependencies with distinct DB role passwords
and existing persistent signing/encryption keys. External configuration is populated.

Twelve Jenkins jobs use reviewed SHA pins before Jenkinsfile loading; unprotected
GitHub main is not treated as automatic approval. The non-elevated EXCLUSIVE deployment
node and persisted queue guard are verified. Documentation-only publication does not
advance a job's approved source pin. See Infra/direct/README.md for this release lane.
Fresh Facebook Web consent now passes actual callback/exchange/USER/admin403/PKCE/
transport/replay/CSRF/refresh/logout/revocation with one isolated provider account,
zero privileged roles/active sessions and temporary Serve routes cleaned. Google
Web consent, Gmail delivery/retry and dev-only fictional Firebase binding passed.
Domain/tunnel rollout is deferred by the operator. Docker alternative final image gates
and publication are tracked in Infra/alternative; older entries below are historical.

## Implemented and locally verified
- [x] PostgreSQL Flyway identity/session/MFA/mail schema; JDBC queries and transaction locking.
- [x] Email/username password auth, Argon2id, RS256 access JWT and public-only JWKS.
- [x] Native refresh rotation with reuse-family revocation, logout and immediate session/security-version checks.
- [x] Web refresh HttpOnly/Secure cookie transport; exact Origin and session-bound CSRF; native/web token separation.
- [x] TOTP enrollment confirmation, replay protection, 5-attempt MFA challenge limit, hashed single-use recovery codes.
- [x] Verify email/password reset/change; reset preserves MFA and revokes sessions.
- [x] Encrypted SMTP outbox with bounded retry; actual Gmail worker delivery/retry and cleared payload verified with external credentials.
- [x] DTO unknown-field rejection, profile/session ownership and coarse IP/account limits.

## Required before the full goal is complete
- [x] Google/Facebook code exchange, PKCE/state/nonce/browser binding, safe linking and signed-provider HTTP fixture tests.
- [x] PKCE-bound no-email provider contact onboarding with unverified contact and no automatic linking.
- [x] OAuth return configuration fails closed at startup: Web HTTPS and hierarchical native app/HTTPS targets only; unsafe schemes, userinfo, wildcard/query/fragment/opaque/relative targets rejected without logging raw configuration. Existing exact-match PKCE checks remain.
- [x] External callback URIs match the current HTTPS hostname; Meta App ID/secret recognized by official endpoints.
- [x] Live Google Web consent/exchange/profile/USER/admin403/PKCE/replay/CSRF/refresh/logout/revocation; status-only partial result preserved before user stopped the fixture.
- [x] Meta legacy JSON media compatibility repaired with strict JSON/trailing-script rejection, sanitized diagnostics,9 HTTP regressions and actual old/new framework probes against Meta.
- [x] Fresh Facebook Web user-consent/callback passes after compatibility repair; native/linking/denied-consent live checks remain separate and are not inferred from Web PASS.
- [x] Firebase dev/test fictional-number proof, owner/session/auth_time/replay binding, phone identifier/schema and team/prod disabled policy.
- [x] Real Firebase fictional-number sign-in and complete BảoTin binding through actual Admin SDK: wrong-owner401, verify204, old-session401, phone-login200, USER role and auth-event replay401. No real SMS.
- [x] Operator-only ADMIN request/activate/recover/revoke CLI/SQL; pending-MFA onboarding and USER/MODERATOR APIs require recent TOTP step-up.
- [x] PostgreSQL runtime/migrator/operator separation, protected ADMIN grants, append-only runtime audit and DDL/cross-schema negative tests.
- [x] RS256 public-JWKS overlap, persistent key-file restart tests, authenticated AES key-id envelopes and migrator rewrap CLI/tests.
- [x] Docker Auth/Gateway restart/overlap/retirement/refresh continuity and rewrapped MFA enrollment E2E with isolated migration job credentials.
- [x] Fresh TOTP step-up for admin actions/linking and auth_time for OAuth-only password initialization.
- [x] Password/MFA/identity/admin reauthentication paths, bounded factor/password attempts and authenticated recovery-code regeneration.
- [x] Durable generic security-change notices, encrypted leased SMTP queue and real Mailpit retry/delivery tests.
- [x] Transactional lifecycle outbox, immutable contracts/envelopes, real Kafka publish/outage/retry and crash-resend eventId checks.
- [x] Audited operator-only exhausted delivery requeue without mutating event envelope.
- [x] Actual Gmail send/retry through Auth worker and PostgreSQL outbox: sender-self mail, STARTTLS/hostname validation, preserved lease/backoff after fixture connection failure, successful retry and cleared payload.
- [x] Explicit trusted-proxy IP parsing, canonical metadata, cross-route rate buckets, cookie CSRF/CORS/chunked-body/Redis outage tests.
- [x] Actual Tailscale/ingress forwarding verified with an observed Docker Desktop host IPv6 /128; real peer counters and forged-header rejection pass.
- [x] Actual Nginx/Gateway/Auth staging boundary: forged XFF/internal/role headers ignored, canonical PostgreSQL/Redis peer buckets and host bridge chain verified. Nginx synthetic OAuth query leak fixed and 413 Problem Details UUID verified. Live tailnet peer/HTTPS checks are separately verified below.
- [x] Tailnet preflight rejects URI userinfo/extra origins/wrong port, BảoTin Funnel and occupied TCP/Web handler; other Serve state is preserved. Local parser tests do not replace live Tailscale smoke.
- [x] Actual Jenkins FullStack#3 build-only orchestration:11 successful child jobs; Auth62 + Gateway13 + eight scaffolds8 =83 backend tests, frontend25; total108 tests with no failed/skipped backend tests. Ten JAR secret/dependency/SBOM gates and frontend typecheck/export/artifact verified.
- [x] Twelve jobs pin reviewed SCM SHAs before Jenkinsfile loading; GitHub main remains unprotected. Pins are operator-reviewed, not auto-advanced.
- [x] Restricted non-elevated EXCLUSIVE Windows deployment node, external config and real FullStack#6 promotion; terminal SUCCESS and post-job 11-process/readiness checks verified.
- [x] External-token-file Jenkins validation runner and 17 synthetic HTTP/ACL/redaction/stale-report tests; anonymous endpoint identification precedes credential reads. Real authenticated declarative validation accepts all three current pipeline hashes. Build/promotion remains a separate live gate.
- [x] Actual pinned Gitleaks/Trivy secret, packaged-JAR and image OS/JAR release gates plus CycloneDX SBOM; both services pass. Synthetic secret, vulnerable JAR/image and empty scan coverage fail as expected. Tomcat/Jackson/Bouncy Castle security patches verified with 60 service tests and container rotation/MFA E2E.
- [x] Windows agent preflight regression: actual remoting parser expands secret file without connecting; jar hash, controller URLs and language-independent SID ACL checks reject invalid inputs. Live node/job configuration and Jenkins execution remain pending.
- [x] Cipher-reader/schema compatibility gate for deployment rollback; populated DB upgrade, unhealthy-image auto rollback, manual rollback, reader/cipher floor rejection before container/state mutation and encrypted MFA confirmation verified on isolated staging.
- [x] Auth SMTP health follows delivery enablement: disabled credentials do not block health; both environment and config-tree enable the mail indicator. Auth 49 tests and Gateway 11 tests pass after the fix.
- [x] Current Tailscale hostname/HTTPS and exact Expo Origin verified; temporary9443 removed and existing443/8443 preserved. Provider-registered callbacks remain under live Google/Facebook smoke.
- [x] Container E2E register/login/refresh/logout with separate runtime/migrator DB roles and denied direct ADMIN grant.
- [ ] Complete provider-backed/config/rotation E2E and final full-plan completion audit.

The goal remains active until every required item has implementation and direct verification evidence.

Latest staging evidence: `Infra/team/Test-TeamRuntime.ps1 -SkipBuild` returned
`TEAM_RUNTIME PASS releaseA=healthy releaseB=healthy populated_db=preserved failed_release=rolled_back manual_rollback=healthy session=preserved images=digest reader_floor=denied cipher_floor=denied rejected_rollback=no_mutation encrypted_mfa=preserved`.
This does not certify live provider credentials or an executed Jenkins promotion/scanner pipeline.

Live Tailscale regression is resolved: Docker Desktop alternated a host IPv6 peer not
present in the original trust chain. GATEWAY_HOST_PROXY_IPV6_CIDR accepts one observed
ULA /128; wider masks and tailnet client prefixes are rejected. The next test exposed
duplicate CORS response headers from Auth+Gateway; both Gateway routes now deduplicate
identical origin/credentials values without changing cookies or broadening origins.

Verified: HTTPS registration/login/profile, canonical peer bucket, forged ADMIN/IP
rejection, single CORS values, Secure cookie, CSRF-protected refresh rotation, stale-CSRF
403, logout204 and revoked-token401. Final suites: Auth49, Gateway13 (62 total).
Command: Infra/team/Test-TeamRuntime.ps1 -SkipBuild -TestTailnet -HostProxyIpv6Cidr fd5e:a313:f28:2::6/128
Temporary9443 removed; existing443/8443 unchanged. Re-observe the peer on other hosts.
See COMPLETION_AUDIT.md. Jenkins API token is external and actual declarative validation accepts all three pipelines.

Live provider checkpoint: Google credentials recognized against an invalid-secret control;
Facebook app token issued; real user consent/callbacks still pending. Firebase service-account,
Android/iOS/API-key project consistency and fictional phone proof binding pass. The prior
SMS region policy failure was corrected by the user; Spark remains in use and no billing
change/real SMS was requested. Native Firebase config files were not modified.

Latest completion review: `SECURITY_REVIEW.md`. Both complete wrappers reran with JDK21,
Auth52/Gateway13 (65 total), zero failures/errors. Fresh actual secret/JAR/image/SBOM gates
pass for both services; negative secret/vulnerable-JAR/vulnerable-image/empty coverage
controls fail as required. Both scanned and staging images contain the exact current JAR
hashes. Auth/Gateway IMAGE namespaces and generator now use confirmed owner `vietluccuu`.

Read-only live Jenkins inventory still finds main+develop SCM and the wrong GitHub owner
on both existing jobs, and no dedicated deployment node. A main check inside Jenkinsfile
does not protect against loading an untrusted Jenkinsfile. Exact private repo URLs and
protected main-only job configuration must precede a build. Dind still restarts. The
interactive OAuth fixture is locally verified but no actual consent/callback is claimed;
temporary9443/9444 remain absent. Default external team deployment/key/DB/Redis/agent
paths are currently absent; operator configuration may use other reviewed paths.


## Updated full-stack checkpoint — current evidence

Source publication remains per-repository under `vietluccuu` using `dodinhhoanganhcmc`.
Auth/main `14f46a5` adds strict Meta JSON compatibility and private diagnostics; Infra/main
`bc788ee` repairs sandbox-safe build-number assignment and beforeAgent deployment gating.
Actual FullStack#3 SUCCESS: API-Gateway#5/Auth#8/eight scaffolds#3/Frontend#3; all108 tests
pass. Actual archived JAR scan/SBOM reports were reopened and verified, and primary web
archive exists. No Docker images, maintainer approval, Windows node or deployment executed.

Dedicated folder SCM credential is present and checkout verified. Actual job sources select
main, but actual GitHub branches are NOT protected; do not conflate these facts. Current
Jenkins has one local account, signup disabled and FullControlOnceLoggedIn; retain existing
apps/permissions and resolve reviewed-source/node isolation before host promotion.

Live Google passed; Facebook compatibility is repaired and actual framework decoding
against Meta succeeds while deliberately invalid token remains denied. Fresh Facebook
consent is pending readiness; old callback/state/code must not be replayed. User-stopped
OAuth fixture/9443/9444/copied secrets were cleaned; original443/8443 preserved. Default
external deployment env/agent files are absent; operator said a configured path would follow.

Remaining explicit gates: trusted reviewed source before privileged Jenkinsfile retrieval;
Facebook and remaining transport/link/cancel consent paths; external config/node and live
promotion; fresh current-artifact runtime/rotation acceptance and final full-scope audit.
Business functions in the eight other services remain their team's TODO, not implemented.

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

## USER email-OTP implementation checkpoint — 2026-10-08

Operator selected A and approved concrete design. USER-only opt-in, no TOTP/ADMIN
fallback; contracts updated before code. Auth73/Gateway24 pass, including schema9
reader2 bridge and real SMTP inbox/HTTP proof. New JAR/image gates pass zero HIGH/CRITICAL
and SBOMs. Actual isolated schema9->10 test preserves old JWT/encrypted TOTP, revokes
pre-enrollment EMAIL sessions, rejects replay/transport/admin, survives restart and
restores the SAME session after a failed-key candidate. Real DB remains schema9 and
live release unchanged until bridge deployment/backup/migration/new release acceptance.
Manual startup was chosen; no Task Scheduler installed. User-approved three SHAs were
pinned and FullStack7 build-only finished SUCCESS before this new email source existed.
Fresh Facebook Web consent also passed and temporary routes were removed. These resolve
older pending markers; native/link/cancel provider checks remain distinct, not fabricated.