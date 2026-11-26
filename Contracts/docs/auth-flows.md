# Auth contract 0.1.0 — resource model and behavior

User owns sessions, MFA enrollment/challenges, recovery codes and verification/reset tokens. IDs are UUIDs; timestamps use UTC. No cross-schema FK is permitted. `auth.users` is the source of truth for role/status/security version; signed JWT claims are not sufficient after revocation.

## Native
`register → email verification → login → MFA challenge if enabled → access/refresh`. Access lasts 600 seconds. Refresh is an opaque 32-byte random value; only hash and consumption history are stored. Every reuse revokes the whole session family. Refresh requests must be single-flight on the client.

## Web
Use `/api/v1/auth/web/login` and `/web/mfa/challenges` with exact allowlisted Origin. Refresh goes into `__Host-baotin-refresh` (Secure, HttpOnly, SameSite=Lax, Path=/), never JSON. The response includes `csrfToken` and sets readable `__Host-baotin-csrf`. `/web/refresh` and `/web/logout` require cookie, Origin and `X-CSRF-TOKEN` matching the server session. Use `credentials: include`. A web refresh token cannot be exchanged at the native refresh endpoint.

Native and Web password/MFA challenge transports are distinct; a challenge cannot move between them. Refresh responses rotate both refresh and CSRF tokens. Access tokens remain in memory, not browser localStorage. SameSite=Lax requires same-site HTTPS frontend/Gateway origins for cookie requests; separate-site hosting needs a reviewed transport change.

## MFA
Enrollment requires email verified, password reauthentication and first TOTP confirmation. Confirmation revokes existing sessions. The authenticator secret is AES-GCM encrypted, recovery codes SHA-256 hashed and single-use. Login challenge expires in 5 minutes, permits 5 failures and is consumed once. Each TOTP timestep can be used once. ADMIN/MODERATOR cannot disable MFA. Operator pending grant leaves the account USER until verified contact and TOTP are confirmed; activate/recover/revoke have no public HTTP endpoint.

## Authorization and errors
Register and profile DTOs reject unknown fields; no role/status/verification fields can be assigned. Session revocation checks user ownership. Every protected Auth request checks account status, current roles/version and active session; blocked/demoted/logout sessions fail 401. Role-sensitive endpoints must additionally enforce permissions as they are implemented.

Errors use `application/problem+json` with stable `type` URI and `code`. Login/forgot responses do not reveal whether an account exists. Rate limit is applied by account and coarse IP at Auth and Redis-backed IP at Gateway; errors return 429 or dependency failure. Login rate thresholds are temporary initial configuration, subject to team load testing.

## Administrator and OAuth additions
Recent authenticator-only `/step-up` marks one session for 5 minutes. ADMIN listing and USER/MODERATOR role/status changes require that step-up and live DB role checks; self/ADMIN targets are denied. SQL permissions forbid Auth runtime from granting ADMIN directly or executing operator functions. See Auth-Service/OPERATOR_RUNBOOK.md.

OAuth uses state/nonce, provider verification, a browser binding for Web and a 60-second client-PKCE exchange ticket. Email collision never auto-links. An explicit authenticated link request binds user, session and security version; logout/recovery invalidates the pending proof. `amr` distinguishes password vs federated and includes `otp` only after a second factor. See Auth-Service/OAUTH_RUNBOOK.md.

## Phone and key persistence
Phone identifiers/verification are restricted to configured fictional Firebase numbers in dev/test. Phone-only accounts are rejected outside that environment, and profile/contact checks never treat stored dev proof as real ownership on team/prod. Phone proof requires the same user/session/security version, exact E.164, fresh Firebase auth_time and an unconsumed `(project,uid,auth_time)` event; success revokes sessions.

JWT JWKS may publish current+previous public keys during a bounded overlap. Encryption uses authenticated key-id envelopes and retains previous decryption keys until the isolated rewrap job completes. Auth runtime has no migration credential; migrations run before runtime deployment. See Auth-Service/PHONE_RUNBOOK.md and KEY_ROTATION_RUNBOOK.md.

## Delivery and edge
Lifecycle events share the user-write transaction and contain only UUID/status/role/securityVersion.
EventId is preserved across retry/crash resend; downstream consumers must use Inbox dedupe.
Audited security changes queue generic verified-email alerts, never tokens or factors.
Trusted proxy/gateway CIDRs are explicit; client identity headers never authenticate a caller.
IP buckets apply before JWT verification and verified principal buckets after it. Error and
mutation responses include a generated/trusted correlation UUID propagated into Outbox.

Recovery-code regeneration requires current password plus a fresh TOTP, replaces every old
code and revokes sessions. OAuth contact onboarding uses the original client PKCE proof and
never accepts client-supplied email as provider verification.

## Versioning
Configured OAuth frontend return targets are validated before serving requests: Web uses
HTTPS, native uses HTTPS or a hierarchical app scheme. Neither accepts credentials in
the URI, wildcard/query/fragment or opaque/relative targets. This hardens configuration
without changing v1 request/response fields or valid exact-match/PKCE targets. Existing
HTTP/unsafe configurations must be replaced before deploying the updated service.

The public ingress returns `application/problem+json` for body-limit errors (413,
`REQUEST_TOO_LARGE`) and unavailable Auth/Gateway upstream (503, `UPSTREAM_UNAVAILABLE`).
Both carry a generated UUID in body `correlationId` and `X-Correlation-Id`; 503 carries
`Retry-After: 5`. Clients must use status/code, not depend on legacy Nginx HTML or 502
when the upstream cannot be reached. This normalizes already-failing requests and does
not change successful v1 payloads; rollout retains the same routes and client token flow.
Nginx request-context logs stay disabled so OAuth query codes do not enter logs.

The edge emits one identical `Access-Control-Allow-Origin` and
`Access-Control-Allow-Credentials` value when both Gateway and Auth authorize the same
Origin. This removes invalid duplicate CORS headers without broadening allowed origins;
different/conflicting values are not silently accepted. Refresh/CSRF cookies retain their
separate `Set-Cookie` headers and existing v1 transport.

Additive fields preserve `/api/v1`; a breaking transport/field/state change requires compatibility policy and a new version. OpenAPI only lists implemented endpoints; remaining full-plan functionality is tracked in Auth-Service/IMPLEMENTATION_STATUS.md. Provider/UI completion is not inferred from these contracts or fixture tests.
