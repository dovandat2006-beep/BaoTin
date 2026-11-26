# Existing public API integration — 2026-10-29

All business requests use the shared Gateway client. This source increment is
under **local verification before any push**; transport fixtures are not provider
consent, real-device push delivery or a production promotion claim.

## Connected existing capabilities

| Area | Frontend entry | Existing public Auth/Notification/Threat APIs |
|---|---|---|
| Profile | Account → Hồ sơ | GET/PATCH me; resend and consume email verification |
| Password recovery | Login → recovery/verification | forgot/reset/verify-email; generic forgot response |
| Password security | Account → Bảo mật | password change/initialization; current password/factor, then local session invalidation |
| MFA | Account → Bảo mật | TOTP enroll/confirm, recovery regeneration, disable; Email MFA enroll/confirm/action challenge/step-up; TOTP step-up |
| Login MFA | Login challenge | TOTP or one-time recovery code; Email MFA resend with Web/native transport |
| Sessions | Account → Phiên | paginated session list, confirmed revoke and logout-all |
| ADMIN users | Quản trị | TOTP step-up before list; paginated users, confirmed USER/MODERATOR role and ACTIVE/LOCKED/DISABLED updates; never grants ADMIN |
| OAuth login/link | Login; Account → Bảo mật | Google/Facebook requests, explicit link-requests, PKCE-bound one-use ticket exchange, contact onboarding and MFA |
| Notifications | Notification sheet | server unread-count; confirmed delete; existing read/read-all/preferences |
| Devices | Account → Thiết bị | list/revoke; write-only registration of a real pre-provisioned FCM token |
| History | History | confirmed server-side delete-all, plus existing filters/detail/delete |

Role/user/status headers from clients confer no authority. Gateway and owning
services must still authorize every request. Wrong password/factor 401 responses
are never automatically replayed: the client revalidates identity independently.
Sensitive forms clear on background; TOTP enrollment expires after five minutes.
Recovery codes appear once after server invalidation and are not persisted.

## OAuth transport and configuration

- Web opens an exact-HTTPS Gateway-origin bridge `/auth/oauth`, so the Auth browser
  binding cookie is first-party. It sends sensitive postMessages only to exact
  allowlisted origins and the exact owned popup. PKCE verifier stays in the opener's
  memory; sessionStorage in the popup contains only origin, public PKCE challenge
  correlation and expiry, not credentials or tickets.
- Provider callback goes to Auth through Gateway; `/auth/callback` forwards only
  the one-use ticket to the opener and removes query values from browser history.
  The Web exchange returns an in-memory access token and HttpOnly refresh cookie.
- Standalone native uses registered `baotin://auth/callback` and secure refresh
  storage. Expo Go uses a fixed HTTPS `/auth/native-callback` bridge: the user copies
  a short-lived one-use ticket back to the originating app; only its pending PKCE
  verifier can redeem it. No wildcard `exp://` or insecure Web return is accepted.
- Server return allowlists must contain the exact corresponding HTTPS/custom
  URIs. Google/Meta consoles must register their public Gateway Auth callbacks.
  Current external Auth config uses the exact public callbacks
  `https://baotin.rimiprod.me/api/v1/auth/oauth2/callback/google` and `/facebook`,
  the Web return `/auth/callback`, native `baotin://auth/callback` and Expo Go
  `/auth/native-callback`. Real Gateway requests/cookie binding/cancel/replay pass.
  After the operator registered the callback, both Google and Facebook reach
  their login forms with no visible redirect mismatch; real Gateway Web/native
  requests and binding pass. Positive consent/exchange/linking remains unverified. Existing
  keys/secrets are preserved; provider-console registration is a separate step.
- Linking never changes the current signed-in identity. A wrong-kind link result
  is rejected. Existing-email accounts are not implicitly merged.

## Push/platform distinctions

FCM registration is a real API operation with a write-only provider token, not an
Expo token or invented subscription. Automatic FCM/service-worker provisioning
still needs the matching Firebase app/platform configuration. Expo Go remote push
does not provide that FCM token; in-app notifications and permitted browser polling
remain available. Dev-only phone proof stays explicitly unavailable in production.
Inference/Reputation internal routes remain private. Scaffold Search/Campaign/
Analytics business functionality is not fabricated; new remote Analytics contracts
and Threat counters require separate integration/promotion review.

## Local gates before push

Remote refresh preserved Analytics commits0920606/3033140/4ff35a6. The ADMIN
workspace exposes separate Người dùng and Thống kê tabs: user management retains
its own fresh TOTP gate; the Analytics tab retains the remote live API adapter and
dashboard, never a demo fallback. Inactive panels unmount their private state.
App/navigation and test conflicts were resolved by preserving both feature sets,
not by replacing either screen with the other. Backend Analytics deployment still
needs separate evidence; the current accepted release remains the reviewed #36.

Run typecheck, Jest, tooling/audit policy, Expo Doctor, Web/Android/iOS Hermes
exports, account/OAuth transport and authorization browser tests, existing report/
scan/demo regressions, secret/dependency scans and the unchanged Sonar gate. Then
verify candidate static assets against the actual Gateway/DB for real account and
security flows. Only after the full matrix passes may source be pushed and the
reviewed immutable changes promoted through Jenkins. No device/emulator run is
claimed; that acceptance was explicitly reserved for the user.
