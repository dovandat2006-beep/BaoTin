# BảoTin team deployment — Windows Docker Desktop + Tailscale

This stack is separate from existing Jenkins/dind and other applications. Only Nginx
ingress publishes `127.0.0.1:8090`; Auth/Gateway/DB/Redis use a private fixed subnet.
All image references must be immutable digests. Configuration examples contain paths,
not passwords/private keys. External secret directory must be outside the workspace.

SMTP delivery and its Actuator health check both follow `auth.mail.enabled` (default
false). Missing Gmail credentials do not block Auth startup while delivery is disabled.
When delivery is enabled, the SMTP connectivity health check remains active.

## External secret layout
```text
secrets/
  postgres/password                  # provisioning bootstrap password, postgres only
  migration/password                 # migrator job only
  operator/password                  # isolated operator job only
  auth-config/spring.datasource.password
  auth-config/auth.mail.enabled       # true only after real SMTP configuration
  auth-config/spring.mail.username
  auth-config/spring.mail.password
  auth-config/auth.mail.from
  auth-config/spring.mail.properties.mail.from
  auth-config/auth.oauth.google.client-id
  auth-config/auth.oauth.google.client-secret
  auth-config/auth.oauth.google.callback-uri
  auth-config/auth.oauth.facebook.client-id
  auth-config/auth.oauth.facebook.client-secret
  auth-config/auth.oauth.facebook.callback-uri
  auth-config/auth.oauth.web-return-uris
  auth-keys/private.pem
  auth-keys/encryption.bin
  gateway-config/spring.data.redis.password
  redis/password
  redis/redis.conf                    # requirepass plus appendonly/no public port
```
Use high-entropy base64url DB password files (32+ chars). Restrict Windows ACL to the
deployment account; do not share the directory with build jobs or Expo. Each container
mounts only its own secrets; runtime does not mount migration/operator passwords.

## Pipeline and deployment
1. Build/test/secret scan/dependency+container scan/SBOM using the service Jenkinsfile.
2. Push immutable image tags from trusted main only; archive a digest release fragment.
3. A restricted operator combines approved Auth/Gateway digests into release JSON:
   `{"authImage":"ghcr.io/ORG/auth-service@sha256:...","gatewayImage":"ghcr.io/ORG/api-gateway@sha256:..."}`.
4. Windows deploy-only agent runs `Test-TeamPreflight.ps1` then `Deploy-Team.ps1` using
   an approved Infra revision and external public `.env`/secret paths.

```powershell
.\Test-TeamScripts.ps1
.\Test-TeamPreflight.ps1 -EnvFile C:\Users\USER\.baotin\team.env -RequireTailscale
.\Deploy-Team.ps1 -EnvFile C:\Users\USER\.baotin\team.env -ReleaseFile APPROVED_RELEASE.json
.\Rollback-Team.ps1
```

Deploy pulls digests, inspects actual image capabilities in a networkless read-only
container, proves prior reader/cipher compatibility **before** migration, then provisions,
migrates in a separate container, grants runtime access and verifies applications.
State is committed atomically only after health/smoke. Failure restores previous
compatible digest/env and proves health again. No volume is deleted or DB schema rolled down.
Old images without compatibility metadata or BK1 support are rejected, not guessed safe.
Normal release blocks overwritten key files; key rotation follows its separate runbook.

Staging must not run concurrently with team using the same subnet/port/secrets; this
initial single-host stack intentionally has one active environment. Use a separately
reviewed network/port configuration for simultaneous environments.

## Tailscale trust
After verifying `tailscale status` is Running and the current MagicDNS hostname, add
only BảoTin's HTTPS Serve port `9443 → http://127.0.0.1:8090`; do not reset existing
Serve routes or enable Funnel. Verify Expo origin/callback port exactly. Gateway trusts
only Nginx peer `.12`, host bridge `.1` and an explicitly observed host IPv6 `/128`,
not tailnet client ranges. Host loopback is an
operator trust boundary; local untrusted processes must not be treated as isolated clients.
Actual peer/header behavior is a deployment test, not inferred from YAML validity.

Docker Desktop can forward host traffic with an IPv6 peer even when the Compose network
is IPv4. The verified peer on this machine is `fd5e:a313:f28:2::6/128`; its hashed Auth
bucket caused registration/login to split before it was identified. Do not generalize
this address to other hosts. Set `GATEWAY_HOST_PROXY_IPV6_CIDR` only after observing the
actual peer. Preflight allows one ULA `/128`, rejects broader masks, public/loopback
addresses and Tailscale's IPv6 prefix. Revalidate after Docker network changes.

Verified command on this host:
```powershell
.\Test-TeamRuntime.ps1 -SkipBuild -TestTailnet -HostProxyIpv6Cidr fd5e:a313:f28:2::6/128
```
The live test asserts both register/login IP counters, forged-header rejection, single
CORS origin/credentials headers, Secure refresh cookie, CSRF-protected refresh rotation,
stale-CSRF rejection, logout and immediate token revocation. Identical CORS headers from
Auth and Gateway are deduplicated at the edge; cookies are not deduplicated or exposed.

`Test-TeamIngress.ps1` runs only on isolated staging: a non-proxy container forges
forwarded/identity headers through actual Nginx, directly at Gateway and directly at
Auth. PostgreSQL and Redis counters prove the genuine peer supplies the canonical IP;
anonymous ADMIN/internal routes remain denied. A separate loopback request verifies
the actual host bridge forwarding chain. This is not live Tailscale proof.

Tailnet preflight parses every Expo origin as an exact HTTPS URI on port 9444; it rejects
userinfo, unexpected port/path/query/fragment and extra origins. It rejects Funnel or
another TCP/Web handler on BảoTin port 9443 and does not modify other Serve handlers.
An old hostname on the reserved port is rejected for operator review; unrelated ports
are compared before/after Serve enablement. `Test-TeamRuntime.ps1 -SkipBuild -TestTailnet`
requires an operator-approved temporary 9443 handler and an already enabled current
tailnet Serve/HTTPS feature. It verifies real HTTPS, caller-IP counters, exact Expo
Origin/Secure cookie and rejection paths, then removes only its temporary handler.
Secret directory must not equal or fall under this workspace.

Nginx request-context access/error logging is disabled because Nginx errors can contain
raw OAuth query codes. Use sanitized Gateway/Auth correlation and health checks for
requests; master startup diagnostics remain available. Oversize 413 and upstream-down 503 errors use
`application/problem+json` with a generated UUID. Run `Test-NginxPrivacy.ps1` to reproduce
oversize/upstream failure with a synthetic code and prove it stays out of logs. Upstream
errors include Retry-After 5 seconds; responses expose no upstream details.

Existing dind restart loops, Tailscale logged-out state and missing real provider secrets
are reported by preflight. This repository does not automatically recreate Jenkins/dind.

## Interactive Google/Facebook smoke fixture

`Start-LiveOAuthCheck.ps1` reuses the locally tested Auth/Gateway image IDs, isolated
PostgreSQL runtime/migrator roles, Redis and the existing sanitized Nginx config. It never
touches Jenkins, persistent team data or original external provider configuration. Only
the seven provider configuration files are copied into an ACL-restricted temporary dir;
SMTP/Firebase/events are disabled. Private keys/passwords and provider proof data remain
transient. Canceled/incomplete checks are not recorded as PASS.

```powershell
python .\Test-OAuthCheck.py
node .\Test-OAuthBrowser.cjs
.\Start-LiveOAuthCheck.ps1 -LocalPreflightOnly
# After operator approval of both temporary HTTPS routes and readiness to consent:
.\Start-LiveOAuthCheck.ps1 -EnableTailnetReceiver -Minutes 20
```

Local preflight starts and removes the fixture, validates two configured provider request
responses/Secure binding cookies and wrong-Origin403, and adds no Serve routes. Live mode
rejects occupied9443/9444 and any existing staging subnet/8090 listener, then temporarily
serves Gateway9443 and a loopback-only receiver9444. The receiver has no request logs,
no arbitrary static-file access, exact Host/Origin/run-nonce checks, a hashed-script CSP and
status-only report fields. No browser tokens are persisted; only tab-scoped client PKCE
state lives briefly in sessionStorage and is cleared at completion/error. The callback
ticket is removed from browser history before exchange.

The operator uses **test accounts** on the official provider pages. Each successful Web
flow checks wrong PKCE/transport, ticket replay, JWT profile/USER role, admin403, cookie
refresh/CSRF, logout and immediate revocation. A separate DB query verifies no privileged
role/active session. No-email onboarding and existing TOTP are supported interactively.
Email collision intentionally remains a failed login requiring explicit linking; do not
reuse the same provider email across fresh-account smoke checks unless testing that path.
Native return/linking/real denied-consent checks remain separate acceptance items.

Every exit removes only fixture containers/volumes/network, copied secrets and temporary
Serve9443/9444, and checks original handlers are preserved. Timeout defaults to20 minutes.
Results under `.changes/auth-live-oauth/live.json` contain booleans/statuses only; use a
fresh `-OutputFile` on retries. A local preflight or synthetic receiver test is **not**
live Google/Facebook consent proof.

### Current full-stack OAuth fixture

When the primary JAR Gateway owns8090, use the separate loopback staging port8092 and
reviewed immutable image manifest (patched Nginx web image, never an unscanned old tag):

```powershell
.\Start-LiveOAuthCheck.ps1 -LocalPreflightOnly -GatewayPort 8092 -ImageManifest EXTERNAL_REVIEWED_IMAGE_MANIFEST -OutputFile FRESH_WORKSPACE_REPORT
.\Start-LiveOAuthCheck.ps1 -EnableTailnetReceiver -GatewayPort 8092 -ImageManifest EXTERNAL_REVIEWED_IMAGE_MANIFEST -Providers FACEBOOK -OutputFile FRESH_WORKSPACE_REPORT
```

The selected provider list controls completion; a one-provider result is not a claim
that both providers passed in that run. Latest Facebook Web consent passes actual
USER/profile200/admin403, wrong PKCE/transport/replay401, CSRF403, refresh200 and
logout204/revoked401. The fixture DB has one provider account, no privileged roles or
active sessions; its routes/containers/copied secrets were cleaned. Native/link/cancel
live paths remain separate. Primary8090 and original Serve443/8443 remain preserved.