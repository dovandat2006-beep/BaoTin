# Firebase Spark phone verification — dev/test only

Firebase's no-billing Spark plan does not send real verification SMS. This integration
accepts only configured fictional Firebase phone numbers from a dedicated dev project.
It is verification proof, not passwordless login or a substitute for TOTP.

## Configure later
- `FIREBASE_PHONE_ENABLED=true` only with `SPRING_PROFILES_ACTIVE=dev` (tests use `test`).
- Supply dev project ID, service-account file mount and an exact fictional E.164 allowlist.
- Register those same fictional numbers and fixed OTPs in the Firebase dev console.
- Leave `FIREBASE_PHONE_ENABLED=false` in team/prod; enabling there fails startup.
- Do not configure Auth with `FIREBASE_AUTH_EMULATOR_HOST`; unsigned emulator tokens are not accepted.
- Keep dev database/project separate from team. A stored dev phone proof is not real-world ownership evidence.

## API flow
1. Register username/password with email and/or a configured fictional phone. Phone-only registration is dev-only.
2. Sign in with username/password to get a limited normal USER session; unverified phone identifiers cannot log in.
3. POST `/api/v1/auth/phone-verifications` with target phone and current password. OAuth-only owners need recent provider auth; existing MFA owners need recent TOTP step-up.
4. Use Firebase client Phone Authentication against the fictional number; obtain the Firebase ID token. SDK/reCAPTCHA/deep-link UI integration belongs to Frontend TODO.
5. POST `/api/v1/auth/verify-phone` with challenge and Firebase ID token using the same current BảoTin session.
6. Success verifies only the matching phone, consumes the Firebase auth event and revokes every old BảoTin session. Log in again using phone+password; TOTP still applies if enabled.

Challenges expire after 5 minutes and allow 5 proof attempts. The server uses the Admin SDK
`verifyIdToken(token, true)` (signature/project/expiry plus revoked/disabled check), requires
`sign_in_provider=phone`, matching E.164, fresh `auth_time` (not refreshed `iat`) and owner/session/security-version binding.
Consumed `(project, uid, auth_time)` is unique, so refreshing the same Firebase token cannot replay a proof.

Profile's `phoneVerified` is true only while the current environment trusts that dev allowlist.
Production never accepts phone-only login or derives contact verification from dev phone state.
ADMIN activation still requires verified email and confirmed authenticator.

## Evidence and remaining provider check
Tests cover phone-owner/challenge/attempt/replay behavior on PostgreSQL and verify the real Firebase Admin SDK
with signed certificate/HTTP transport fixtures, including project/signature/revocation failures.
Actual dev-project proof now passes through `Infra/team/Test-LiveFirebaseBinding.ps1`:
real Firebase ID token/Admin SDK, wrong-owner rejection, verification, old-session
revocation, phone login and reused-event rejection. The native Android/iOS files match
the service-account project and remain unchanged. No real SMS was sent. Test proof files,
fixture PostgreSQL volume, keys, containers and network are removed after the run.
An earlier OPERATION_NOT_ALLOWED came from SMS region policy; the user corrected the
policy for the fictional number's region without changing billing or enabling team/prod.
