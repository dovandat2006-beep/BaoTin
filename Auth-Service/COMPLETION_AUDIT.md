# Auth/Gateway full-goal checkpoint — 2026-10-06

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

## Latest checkpoint — 2026-10-07; supersedes historical deployment status below

FullStack#6 terminal SUCCESS is actual promotion, not build-only: ten backend JARs +
Expo web,118 tests (Auth62/Gateway23/scaffolds8/frontend25), ten JAR scan/SBOM gates.
Post-job PID/start-time ownership and all11 readiness endpoints are UP; actual HTTP
JWT/MFA/recovery replay/refresh reuse/forged ADMIN403 pass. Docker PostgreSQL/Redis,
external persistent keys and distinct DB role credentials are configured. Twelve jobs
pin reviewed SHAs before Jenkinsfile loading; the restricted non-elevated Windows node
and queue guard pass. Earlier NODE/CONFIG/DEPLOY pending entries are historical.
Fresh Facebook consent still needs interactive verification; Google Web consent passed.
Domain/tunnel rollout is explicitly deferred. Current Docker alternative evidence and
publication are tracked in Infra/alternative, separately from primary promotion.

## Current checkpoint — 2026-10-07

**Full goal remains open.** This checkpoint supersedes the historical SCM, dind,
agent/config absence and test-count statements below; earlier records remain history.

| Requirement | Current authoritative evidence | Status |
|---|---|---|
| JWT RS256/JWKS, issuer/audience/type/kid/time; refresh rotation/reuse/logout | Fresh JDK21 complete wrappers: Auth62 and Gateway13, zero failures/errors/skips; signed-token and real session tests | Verified locally |
| TOTP enrollment/replay, bounded challenges, recovery, reset-preserves-MFA, fresh ADMIN step-up | Current Auth tests including operator activation and runtime-role privilege negatives; source reviewed in `AuthService`, `AdminService`, `SessionGuard` | Verified locally |
| SMTP + durable lifecycle delivery | Real SMTP/Kafka regression in the fresh Auth suite; earlier actual Gmail delivery/retry and real fictional Firebase binding | Verified for tested environments |
| Current packaged application matches runtime acceptance | Rebuilt Auth SHA256 `268976747beabf4aaeca638be22e39bfc12d13f379c320d0f03a291e2dfb4f99`; Gateway `81b039c5a493fa6a7d93ffa74e8cfefafe0a039053ac8bc0296c64c0f2e71058`, equal to the 2026-10-07 real-JAR acceptance inputs | Artifact identity verified |
| Jenkins source trust before Jenkinsfile retrieval | Live12 job definitions match persisted explicit approved SHAs, correct `vietluccuu` origins/folder credential and lightweight=false; Auth#9 SUCCESS62 tests, Gateway#5 SUCCESS13 tests | Verified; GitHub main itself remains unprotected |
| Full-stack build/test/security artifacts | Actual FullStack#3 SUCCESS with83 backend +25 frontend tests and JAR scan/SBOM gates; no promotion | Build-only verified |
| Deploy-only Windows node | Live `baotin-deploy-desktop` online, EXCLUSIVE/one executor/WebSocket, actual Java token non-elevated; real foreign freestyle/Pipeline tasks denied; invalid-loader copy disables executors then restores node; ownership-copy rollback passes | Connection/negative scheduling verified, not actual authorized promotion |
| Deployment files/dependencies | Public `C:/Users/ngoan/.baotin/jar.env` draft exists; runtime DB/Redis password and JWT/MFA key files are still absent at its configured paths | Operator setup pending |
| Google/Facebook live OAuth | Google Web result has exchange/profile200, ADMIN403, bad-PKCE/transport/replay401, CSRF403, refresh200/logout204/revoked401; Meta strict JSON-media fix is tested | Fresh Facebook consent plus live native/link/cancel paths pending |
| JAR/web target release and HTTPS | Isolated real-JAR restart/populated-DB rollback passed; existing Serve routes preserved and temporary OAuth routes removed | Target configuration, actual promotion and live target acceptance pending |

The master plan's illustrative OAuth start GET routes are represented by the reviewed
OpenAPI POST `/oauth2/requests` handshake, with explicit transport/return URI/client PKCE.
This is not an implemented legacy GET alias; client integration follows the current
contract and `OAUTH_RUNBOOK.md`. No missing provider flow is counted as a successful login.

Current evidence: workspace `.changes/current-completion-audit/`,
`.changes/deploy-node-connection/`, `.changes/jenkins-approved-sha/`,
`.changes/fullstack-sandbox-env/`, `.changes/runtime-current-acceptance/` and
`.changes/auth-live-oauth/partial-before-user-stop.json`.
These operator-local directories are not shipped in Git. Clone users use the committed
runbooks and rerun verification; they do not assume access to a maintainer's evidence files.
Deployment setup: `../Infra/direct/SETUP.vi.md`. Existing reviewed job pins remain in force;
new documentation commits are not automatically approved pipeline source revisions.

## Historical checkpoints

Goal: implement authentication services, API-Gateway, JWT and 2FA against the master
plan and agreed PostgreSQL/Expo/Firebase-Spark/Jenkins/Docker decisions. **Not complete.**
Passing fixtures and local staging do not certify real provider or deployment configuration.

## Requirement evidence

| Requirement | Current direct evidence | Completion boundary |
|---|---|---|
| Java21/Spring Boot4.1.1; Auth MVC, Gateway WebFlux | Per-repo Gradle build; Auth52/Gateway13 test XML with zero failures/errors | Locally verified |
| PostgreSQL schema/runtime/migrator/operator separation | Real PostgreSQL negative DDL, cross-schema, protected ADMIN and audit tests; container migration job | Locally verified |
| Password, username/email/phone identifiers and profile/session ownership | Auth integration tests including phone-only dev identity, mass assignment and cross-user sessions | Locally verified; real phone ownership intentionally not enabled |
| RS256 access/JWKS strict issuer/audience/type/time/use | Signed-token negative tests and container retirement/restart E2E | Locally verified |
| Refresh rotation, reuse revocation, live session/security-version guard | Concurrent refresh tests, disabled/demoted account checks, logout E2E | Locally verified |
| Expo Web cookies/Origin/CSRF and native token separation | Actual HTTP cookie refresh/logout tests plus transport-negative tests | Backend and live HTTPS cookie/CSRF verified; client implementation remains separate |
| TOTP enrollment, challenge bounds, replay/recovery and privileged MFA | Real TOTP tests and encrypted enrollment confirmed after deployment rollback | Locally verified |
| No public ADMIN grant/recovery; operator-only lifecycle | Dedicated operator CLI/SQL role and denied runtime ADMIN tests | Locally verified |
| Email verification/reset/change, leased SMTP/security notices | Mailpit actual send/outage/retry tests; enabled/disabled SMTP health tests | Actual Gmail worker send/retry and payload clearing verified; inbox/client consumption separate |
| Google/Facebook state/nonce/PKCE, app binding, owner linking | Signed-provider HTTP fixtures and linking/onboarding/MFA negative tests | Real provider/client callbacks pending |
| Secure configured OAuth return targets | Startup tests reject HTTP Web/native, unsafe schemes, userinfo, wildcard/query/fragment and ambiguous targets without echoing config; valid HTTPS/app schemes remain supported | Locally verified; external provider setup remains pending |
| Firebase free tier agreed fictional-number dev/test proof | Admin SDK fixtures plus real SDK/phone binding, owner/replay/revocation tests and native client project matching | Real dev-project phone binding verified; no real SMS |
| Durable key files, signing overlap/retirement, encrypted key-ID rewrap | Container restart/retirement and migrator rewrap E2E | Locally verified; operator live rotation still configuration-dependent |
| Transactional lifecycle Kafka outbox and stable retries | Real Kafka outage/crash-resend/eventId tests; mail/event retry operator tests | Locally verified; target broker deployment pending |
| Gateway sole public edge, route/method denylist and request bounds | Gateway tests, actual Nginx ingress 413/503 and anonymous ADMIN/internal route denial | Local stack verified |
| Header spoofing and canonical IP/correlation/rate buckets | Staging peer through Nginx/direct Gateway/direct Auth; PostgreSQL/Redis counter assertions | Host IPv4/IPv6 chain and live Tailscale peer buckets verified |
| No secrets/raw OAuth query in ingress logs | Synthetic-code oversize/upstream-error regression fails original Nginx, passes modified Nginx | Verified for tested ingress error cases; not a universal log guarantee |
| Problem Details/camelCase/UUID/UTC contract | OpenAPI lint; HTTP Problem Details UUID and Retry-After tests | Verified changed errors; 303 callback emits expected lint warning |
| Dependency/container/secret gates and SBOM | Actual pinned Gitleaks/Trivy positive and negative scans, OS+JAR coverage | Local gates verified; execution in Jenkins pending |
| Digest release, isolated migrations and compatible populated-DB rollback | Real staging upgrade/failed-image/manual rollback; reader/cipher floor denial before mutation | Locally verified |
| Windows deployment node | Actual public remoting jar parser, SID ACL/hash/URL negative preflight tests | Real node registration/connection and promotion pending |
| Tailscale tailnet-only HTTPS, exact origins and preserved routes | URI/Funnel/TCP/Web/stale-host negative preflight tests; host bridge HTTP test; real HTTPS health and native registration/login/profile now verified | Live peer assertion and Web cookie/CSRF tests pass; provider-registered callbacks remain pending |

## Evidence locations

- Current suites: each service `build/test-results/test/TEST-*.xml`.
- Delivery/key/container E2E: `.changes/auth-delivery-edge/verify-containers.ps1`.
- Release/rollback: `Infra/team/Test-TeamRuntime.ps1`.
- Real ingress boundary: `Infra/team/Test-TeamIngress.ps1` (staging only).
- Ingress privacy/Problem Details: `Infra/team/Test-NginxPrivacy.ps1`.
- Security gates: `scripts/Test-AuthGatewaySecurity.ps1`, per-repo `ci/security-gates.sh`.
- Windows preflight: `Infra/jenkins/windows-agent/Test-DeployAgent.ps1`.
- Exact command/result records and preserved originals: `.changes/auth-ingress/`,
  `.changes/auth-security-gates/`, `.changes/auth-release/` and prior tranche directories.

## Next external-state checks

1. Authenticated Jenkins declarative validation and approved-main build/promotion;
   existing dind has a changing restart count, so its build lane has not been certified stable.
   The external-token-file runner is `Infra/jenkins/Test-JenkinsPipelines.ps1`; 17 synthetic
   HTTP fixture checks prove credential/crumb handling, failure gating, redaction and stale
   report prevention. External real token now validates all three Jenkinsfiles; source hashes
   recorded. Build/promotion remain unproven. A read-only dind diagnostic found PID62 in its containerd PID file but no
   matching process; a targeted runtime repair is awaiting the operator's choice.
2. Register/connect the dedicated restricted Windows node without recreating the controller.
3. Provider-registered callbacks over the verified Tailscale HTTPS host; preserve unrelated
   Serve handlers and keep BảoTin Funnel disabled.
4. Google/Facebook user-consent/registered callbacks remain pending. Credentials now pass
   official-endpoint controls; Gmail send/retry and Firebase complete phone binding pass.
   Secrets stay in external files/config-tree; examples remain placeholders.

Current read-only live job inventory confirms both Auth/Gateway SCM definitions still
allow main+develop and do not match confirmed GitHub owner `vietluccuu`; the dedicated
Windows node is absent. Correct exact private repo URLs and restrict main **before**
Jenkinsfile retrieval; the runtime branch assertion is not an SCM trust boundary. Current
generated IMAGE fields and generator default correctly use `vietluccuu`; no SCM/job
mutation or build was performed. Default external team.env, DB/Redis/key files and node
secret/JAR paths are absent; obtain the operator's actual reviewed deployment paths.

Latest direct review/scans: `SECURITY_REVIEW.md` and
`.changes/auth-completion-review/security-regression/`. Both full wrappers reran with
JDK21 (65 tests), scanner positives/negatives pass, and scanned/staging image JAR hashes
exactly equal the current build artifacts. The live Google/Facebook, trusted Jenkins
execution, node/promotion and target configuration gates still prevent full completion.

Other services remain scaffolds with their own TODO/README/AGENTS per the agreed team scope;
their future business endpoints are not faked or exposed by this implementation.

## Resolved live-tailnet regressions — 2026-10-06

The unexpected bucket c4397488908f6e6092c38c4c68e5df40460d992b63c758ccb66135c0a2cfc61c
was proven to be Docker Desktop host proxy fd5e:a313:f28:2::6, not a forged tailnet client.
A selected-header-only diagnostic of the isolated staging link identified this canonical
IPv6 peer; no token/password/body capture was persisted. Packaged RequestGuard,
AttemptLimits and AuthCrypto classes exactly matched worktree classes.
The original trust chain correctly stopped at this previously untrusted hop.

Team Compose accepts one observed host ULA /128 through GATEWAY_HOST_PROXY_IPV6_CIDR;
preflight rejects broader masks, public/loopback addresses and Tailscale's IPv6 prefix.
The unchanged register+login counter assertion now passes. Adjacent peers and tailnet
clients remain untrusted in unit tests.

The next live assertion exposed duplicate CORS Origin/Credentials headers from Auth and
Gateway. A new regression failed before the change. Both Auth routes now use RETAIN_UNIQUE
for those two headers only; cookies remain separate and conflicting values stay conflicting.
Gateway13 tests pass, including both route variants and exact IPv6 chain checks.

Test-TeamRuntime.ps1 -SkipBuild -TestTailnet -HostProxyIpv6Cidr fd5e:a313:f28:2::6/128
now completes: HTTPS/native auth, genuine peer buckets, forged-header rejection,
single-value CORS, Secure/HttpOnly cookie, missing/stale CSRF403, refresh rotation,
logout204/revoked401, then upgrade/failed-image/manual rollback with encrypted MFA.
Temporary9443 removed; original443/8443 preserved. The observed peer is host-specific
and must be revalidated elsewhere.

## Actual live-provider checkpoint

Real Gmail worker/outbox delivery and controlled connection-failure retry pass with cleared
payload and sender-self recipient. Actual Jenkins declarative validation accepts all three
current pipeline hashes; no job/build/promotion or node mutation was performed.
Google distinguishes configured credentials (invalid_grant) from wrong secret (invalid_client);
Facebook issues an app token. These controls do not prove user consent/callbacks.
Firebase service-account/project and Android/iOS client consistency pass. The user's region
policy adjustment unblocked the fictional-number token. Complete dev-only API proof passes:
actual SDK signature/project/revocation, wrong owner401, correct verify204, old session401,
phone-login200 with USER role, and reused authentication event401.


## Updated full-stack checkpoint

All confirmed repositories are initialized/pushed with dodinhhoanganhcmc. Jenkins SCM now matches vietluccuu and main-only for ten backend jobs, Frontend and FullStack; DEPLOY-01's old wrong-owner/develop findings are resolved. Dedicated read-only baotin-github-scm credential is still operator-supplied. Primary deployment is backend JAR + static frontend web on the Windows deploy-only node, Docker images optional. Auth now has a read-only RuntimeDatabaseState CLI for actual DB reader/schema/cipher-floor checks; its real PostgreSQL runtime-role test passes. Latest tests: Auth53 + Gateway13 + eight scaffolds =74 backend tests; frontend25 tests, typecheck and web export pass. Old image/JAR fingerprint evidence is historical after the new Auth helper; Docker alternative must rebuild/scan its current artifact. Actual full-stack Jenkins execution, deploy-node connection, external runtime config and Google/Facebook consent remain pending.

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