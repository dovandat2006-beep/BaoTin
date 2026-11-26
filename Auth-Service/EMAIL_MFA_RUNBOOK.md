# USER email OTP MFA

Email mode is explicit USER opt-in, not an authenticator fallback. Verified email,
password and the current owner session are required for enrollment; confirmation
revokes previous sessions. ADMIN/MODERATOR retain TOTP. Existing TOTP/recovery formats
are unchanged and cannot be completed by an email proof.

1. POST `/api/v1/auth/mfa/email/enrollments` with bearer access + `{password}`.
2. Receive `{challenge,expiresIn,mfaMethod:EMAIL}`; read the six-digit email code.
3. POST `/api/v1/auth/mfa/email/enrollments/confirmation` with bearer access and
   `{challenge,code}`. Log in again after204 because enrollment revokes old sessions.
4. Password/provider login now returns `mfaRequired`, `mfaMethod:EMAIL`, `challenge`.
   Confirm through existing Native/Web MFA endpoint; no session is issued beforehand.
5. Resend via Native `/mfa/email/challenges` or Web `/web/mfa/email/challenges` (exact
   Origin). Three deliveries total, cooldown30 seconds, original expiry unchanged.

Codes expire after300 seconds, five attempts; hashes bind purpose + opaque challenge.
Mail content is encrypted in the leased SMTP outbox. Do not log code/token/payload or
enable mail/HTTP wire debug. Delivery failure never switches to TOTP bypass/one factor.

For password change/MFA disable, obtain `/mfa/email/action-challenges` using the current
bearer session and `purpose:CHANGE_PASSWORD`/`DISABLE_MFA`. Existing `code` field takes
the composite `challenge.code`; also supply the current password. Proof is single-use
and cannot be reused for another session/purpose. For USER identity linking, request
`STEP_UP`, confirm `/mfa/email/step-up`; this never authorizes ADMIN endpoints.
Password reset keeps email MFA enabled. Confirming a new TOTP authenticator atomically
turns off email mode and revokes prior email sessions/proofs. No TOTP-to-email fallback.

## Accepted promotion — 2026-10-08

FullStack#8 finished SUCCESS and deployed Auth#14/Gateway#10 with all eight scaffold
jobs and Frontend#8. Reader2 was verified on schema9 before the protected backup and
separate-migrator migration. The actual database is now schema10, readerFloor2, BK1;
explicit runtime grants were applied and persistent key hashes did not change.
Both primary JAR and Docker alternative HTTP email enrollment/login/replay/ADMIN/
refresh-reuse acceptance passed. Live acceptance consumed the generated fixture's own
encrypted outbox proof, not a Gmail inbox; real SMTP inbox consumption is covered by
Mailpit integration tests. Generated identities were disabled/anonymized and revoked.

Contracts: `../Contracts/docs/email-mfa-v1.md` and `openapi/auth-service.yaml`.
Email mode is live on the accepted primary release. Docker alternative is manually
stopped after acceptance, with its images/volumes retained. Startup stays manual.

## Regression and rollback evidence

Auth73/Gateway24 full JDK21 tests pass. Real Mailpit inbox consumption + HTTP covers
email enrollment/login, anti-replay/transport/ADMIN; an isolated actual-image rollout
covers schema9 bridge503, migration10/floor2, old JWT and encrypted TOTP enrollment
continuity, EMAIL session restart, failed-key candidate503 and compatible reader2
rollback200 for the same email session. Both candidate images and both packaged JARs
pass zero HIGH/CRITICAL with actual JAR/OS coverage and SBOMs. Actual FullStack#8 child
reports confirm130 passing tests: Auth73/Gateway24/scaffolds8/frontend25, no failures/skips.

Retain the current reader2 Auth JAR/image as the compatible application rollback target.
Reader1 is incompatible after V10; the older release referenced by `active.json.previous`
is historical, not an approved rollback target. Do not down-migrate or run old local
FullStack#6 recovery scripts. See `../Infra/direct/MANUAL_START.md` for manual recovery.
