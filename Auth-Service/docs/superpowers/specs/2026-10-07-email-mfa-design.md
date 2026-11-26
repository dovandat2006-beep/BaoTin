# USER email-OTP MFA — approved direction A, implementation design

## Task checklist
- [x] Inspect existing TOTP, sessions, SMTP outbox, DB roles and public contracts.
- [x] Compare USER email-OTP option with TOTP-only and unsafe email fallback.
- [x] User chose A: email OTP for USER, ADMIN retains mandatory TOTP.
- [x] Write concrete security/compatibility design below.
- [x] User reviews this concrete design (explicitly approved 2026-10-07).
- [ ] Implement contract, migrations, service/gateway and negative tests.
- [ ] Run complete JDK21 tests, real SMTP/HTTP acceptance, image/JAR security gates.
- [ ] Review/publish new revisions and promote through Jenkins/Docker acceptance.

## Scope and policy
Email OTP is opt-in for USER accounts with a verified email and an existing password.
TOTP and recovery-code behavior remains unchanged for existing authenticator users.
Email cannot complete a TOTP challenge or substitute for privileged TOTP step-up.
ADMIN and MODERATOR still require TOTP; no request DTO can set role or verified status.
An email-only account may upgrade to TOTP by confirming a new authenticator, atomically
disabling email mode and revoking old sessions. Moving from TOTP to email is not an
automatic fallback and is not introduced by this change.

Alternatives considered: keeping TOTP-only would omit the newly selected USER email
method; requiring email AND TOTP for every account would change existing/phone-only
flows; accepting email as TOTP fallback would weaken authenticator/ADMIN boundaries.
The selected independent USER email mode avoids these changes.

## Enrollment and sign-in
Enrollment requires the current authenticated owner, correct password and verified
email. An enrollment mail proof must be confirmed before email MFA becomes enabled.
Enabling/disabling/upgrading increments security version and revokes existing sessions.
Password and federated login both derive the required method from server state. Email
login returns a short-lived opaque challenge, never a session before OTP confirmation.
Native/Web transport remains fixed in the challenge. Web preserves exact Origin and
Secure/HttpOnly refresh + session-bound CSRF contracts.

Codes have six random digits, expire in five minutes and allow five attempts. Store
only a challenge-secret-bound hash; put delivery text only in the encrypted leased SMTP
outbox. Resend is bounded (three deliveries per challenge, account rate limit, cooldown)
and replaces the previous code without extending the original challenge lifetime.
Consumption locks the owner/challenge and is single-use, including concurrent requests.
Expired, reused, wrong-owner, wrong-transport, stale-version and changed-contact proofs
fail. SMTP failure never grants a session or changes to a weaker method.

## Sensitive actions and privilege separation
Email users obtain purpose-bound, session-bound proofs for self-service password/MFA
changes; login/enrollment proofs cannot be reused for these actions. USER email step-up
may support owner linking, but cannot authorize ADMIN endpoints. TOTP step-up remains
the only privileged method. Role upgrades require confirmed TOTP first and revoke old
sessions. Password reset preserves MFA, and contact changes invalidate email proofs.
No recovery code is silently created as an email alternative to privileged TOTP.

## Persistence and compatibility
Add reviewed email-MFA state and challenges inside auth schema only; keep runtime,
migrator and operator identities separate and explicitly grant only needed columns.
Existing TOTP records remain valid. Public v1 additions are additive and documented
in Contracts before implementation. Profile/login responses identify the method.
New reader version/schema metadata prevents rollback to an old JAR that would ignore
email-MFA state and issue sessions without its second factor. No DB down-migration.
Do not migrate the real database until the tested/scanned new release and backup are
ready; isolated tests/staging first. Current reviewed release remains running meanwhile.

Rollout order: deploy a reader-version-2 bridge that still supports existing schema9
and fails closed for email-MFA endpoints while that schema is active. Then back up,
migrate/grant schema10 and advance reader floor to2. The previous bridge is still a
compatible rollback target, while pre-bridge reader1 is rejected. Tests must cover
both schema9 bridge operation and schema10 email operation; do not blindly raise the
floor before installing the compatible reader.

## Verification
Test enrollment/password/contact ownership, encrypted queue/hash storage, delivery/
retry, expiry, five-attempt exhaustion, concurrent single-use, resend invalidation/
cooldown, Native/Web separation, CSRF, session-version/refresh continuity and logout.
Negative tests prove email cannot authenticate TOTP/ADMIN/MODERATOR, grant privileges,
reuse a proof for another purpose/session, bypass reset/revocation, or permit an old
reader after the compatibility floor advances. Include real HTTP and controlled SMTP
proof; distinguish outbox inspection from actual inbox consumption in evidence.
