# Email OTP MFA — additive v1 contract

USER-only opt-in; verified email + password and authenticated owner required.
Existing TOTP/recovery and ADMIN/MODERATOR TOTP requirements are unchanged.
Email cannot complete a TOTP challenge. Enabling/upgrading/disabling revokes old sessions.

## New authenticated POST endpoints
- `/api/v1/auth/mfa/email/enrollments`: `{password}` -> `{challenge,expiresIn,mfaMethod:"EMAIL"}`.
- `/api/v1/auth/mfa/email/enrollments/confirmation`: `{challenge,code}` ->204.
- `/api/v1/auth/mfa/email/action-challenges`: `{purpose}` -> same pending response;
  purpose is `CHANGE_PASSWORD`, `DISABLE_MFA` or `STEP_UP`. Bound to current session.
- `/api/v1/auth/mfa/email/step-up`: `{challenge,code}` ->204; USER email mode only.

## New anonymous challenge resend
- Native `/api/v1/auth/mfa/email/challenges`: `{challenge}` ->202.
- Web `/api/v1/auth/web/mfa/email/challenges`: same, exact allowed Origin required.
An existing opaque EMAIL login challenge is required; no identifier/contact override.
Three deliveries total, 30-second cooldown, account limit; old code invalidated and
original five-minute expiry preserved. TOTP challenges never become email challenges.

## Existing endpoint compatibility
Login/provider-login MFA responses add `mfaMethod` (TOTP or EMAIL); no session before proof.
Existing `/mfa/challenges` and `/web/mfa/challenges` accept EMAIL login code + opaque
challenge while enforcing original Native/Web transport. Profile adds `mfaMethod`
(NONE/TOTP/EMAIL); `mfaEnabled` indicates either confirmed method.
For EMAIL-mode password change/MFA disable, existing `code` is the composite
`challenge.code` from a matching purpose/session-bound action challenge. TOTP/recovery
code formats remain unchanged. Reset password preserves MFA; old proof/version fails.

## Proof/security/storage
Six random digits, five minutes, five tries, single-use/concurrent consumption.
Hash binds code to opaque challenge secret + purpose; only encrypted leased SMTP
outbox carries delivery content. No code in API response/log/contract example.
Verify owner/session/security-version/verified-contact snapshot at use. No contact or
role fields accepted. Failures use existing problem+json; generic401 for bad proof,
429 for limits/cooldown,503 while email-MFA schema is unavailable.

## Deployment compatibility
Reader2 bridge supports schema9 but email endpoints fail closed there. Deploy bridge
first, then back up/migrate/grant schema10 and advance reader floor2. Old reader1 must
not be used after email mode exists; compatible reader2 rollback keeps data/keys.
