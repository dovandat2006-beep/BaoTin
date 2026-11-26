# Google/Facebook integration

## Configuration
Fill provider values in `.env.example` outside Git. Callback URIs must be the verified HTTPS Gateway URL ending `/api/v1/auth/oauth2/callback/google|facebook`. Register each URI exactly in the provider app, including port/path. Keep provider client secrets on Auth only.

Set allowed frontend return URIs independently (`OAUTH_NATIVE_RETURN_URIS`, `OAUTH_WEB_RETURN_URIS`). No wildcard, query or fragment is accepted. Native custom scheme `baotin://auth/callback` is protected by a client PKCE verifier. Production endpoints require HTTPS. Provider configuration is optional until enabled; missing values return `OAUTH_NOT_CONFIGURED`, never a mock identity.

Auth validates configured return targets at startup, before creating any OAuth request:
Web requires hierarchical HTTPS with a host; native supports HTTPS or a hierarchical app
scheme such as `baotin://`/`exp+baotin://`. HTTP, script/data/file/FTP/WebSocket schemes,
userinfo, opaque/relative URIs, wildcards, query and fragment are rejected with a generic
configuration error that does not echo the URI. Empty lists intentionally disable that
transport's OAuth return targets. Valid defaults and existing exact-match PKCE behavior
remain unchanged. Audit old configuration before upgrading; replace HTTP targets with
registered HTTPS/app targets rather than weakening the guard.

## Client flow
1. Client generates a 32-byte random verifier (base64url, 43 chars) and its S256 code challenge, retaining the verifier in memory.
2. POST `/api/v1/auth/oauth2/requests` with provider, transport, exact return URI and challenge. Web sends allowlisted Origin and credentials so the HttpOnly OAuth-binding cookie is set.
3. Open the returned provider `authorizationUrl` using Expo AuthSession/browser. Auth stores hash of state, a nonce and encrypted provider verifier for at most 5 minutes.
4. Provider redirects to Auth callback. Auth exchanges the code, verifies Google signature/issuer/audience/nonce or Facebook app/user binding and appsecret proof. Web callback also requires the same browser-binding cookie.
5. Auth returns 303 to the allowlisted client URI with only a one-use exchange ticket (60 seconds), never access/refresh tokens.
6. Client POSTs ticket+original verifier to native `/oauth2/tokens` or Web `/web/oauth2/tokens`. Transport cannot switch. Existing TOTP requirements still produce an MFA challenge before a session.

## Identity ownership
- `(provider, provider_subject)` is unique. Verified provider email is not sufficient to merge an existing account.
- On email collision, `ACCOUNT_LINK_REQUIRED` directs the owner to sign in to the existing account and create `/oauth2/link-requests` instead. Linking requires current ownership, verified contact and fresh auth/TOTP step-up; the initiating session and security version must remain active at completion.
- Google may mark email verified only from a verified OIDC claim. Facebook email is not automatically verified; SMTP verification is queued.
- New provider-only accounts receive a unique server-assigned username. If a new provider identity has no email, token exchange returns `onboardingRequired=true` without a session; the PKCE-bound ticket remains valid for 5 minutes. Repeat exchange with `contactEmail` to create an unverified contact and queue SMTP verification. Contact collision never links/merges; supplied contact never overrides a provider email claim or earns verification by itself.
- Verified OAuth-only users may use `/password-initialization` after recent provider authentication, then enroll TOTP through the password reauthentication flow.

## Verification
Tests exercise actual HTTP code/token/profile/JWKS exchange with signed provider fixtures and negative state/nonce/audience/app/PKCE/browser-binding/replay cases. These are not a substitute for live Google/Facebook smoke checks.

After credentials are supplied: verify both providers with registered test users via Gateway, Web and native return targets; verify cancelled/denied consent, explicit linking and TOTP login. Never paste provider tokens, secrets or OAuth codes into logs/chat.

Official-endpoint controls: `Infra/team/Test-LiveProviderCredentials.ps1` recognizes Google
credentials using invalid-code versus invalid-secret responses and Facebook credentials
by app-token issuance. Neither proves user consent/callback success. Tokens never enter
reports. Firebase probes use only the server's configured fictional-number map; require
phone proof to fail closed on region policy errors. Complete BảoTin binding is separately
exercised by `Test-LiveFirebaseBinding.ps1`, using transient ACL-restricted proof files.

Interactive Web fixture: `Infra/team/Start-LiveOAuthCheck.ps1 -LocalPreflightOnly` first;
after approval of temporary9443/9444 and provider test-account readiness, run with
`-EnableTailnetReceiver`. See the team README for exact cleanup and status-only reporting.
It checks real callback/exchange/profile/USER/admin/PKCE/replay/CSRF/refresh/logout, then
destroys only its fresh fixture DB and removes both temporary Serve routes. Preserve
operator external config and unrelated443/8443. A successful Web fixture does not certify
native return, explicit linking or canceled-consent paths; those require separate checks.

## Meta Graph JSON media and failure diagnostics

Meta Graph responses may declare `text/javascript; charset=UTF-8` while carrying JSON.
The provider HTTP client accepts that observed legacy media type through a strict JSON
converter, alongside normal JSON types. It does not execute JavaScript or accept JSONP,
script bodies or trailing script. App binding, validity, expiry, `/me` subject matching,
Google signature/nonce/audience and all PKCE/browser/session checks are unchanged.

A generic `OAUTH_PROOF_INVALID` response stays generic on the public API. Operator logs
contain only `OAUTH_PROVIDER_FAILURE`, the fixed provider/stage (`TOKEN_EXCHANGE`,
`OIDC`, `TOKEN_DEBUG`, `PROFILE`), HTTP status and bounded numeric provider error/subcode.
They never include exception messages/stacks, request URLs, code/token/secret values,
response bodies or profile data. Do not enable raw HTTP wire/URI tracing to diagnose login.

The live configured App ID/secret was accepted by Meta with HTTP200; callback equality
was checked independently. A runtime-JRE transport probe observed JSON-labelled legacy
media from `debug_token`; a real local HTTP regression reproduced the public401 before
the converter change. These controls prove compatibility/network/credential properties,
not a successful Facebook user login. Retry consent with fresh one-use state/code after
the patch; a previously failed callback must not be replayed. App Admin/Developer accounts
can test Development-mode apps; never map Meta app roles to BảoTin ADMIN.
