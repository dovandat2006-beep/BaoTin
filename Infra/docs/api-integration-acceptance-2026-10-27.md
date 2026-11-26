# API integration acceptance — 2026-10-27

## Accepted release

Jenkins FullStack **#27**, terminal **SUCCESS**, promoted ten gated backend JARs and Expo Web to `https://baotin.rimiprod.me`. Frontend #22 analyzed **58/58 TypeScript files** before packaging. This report does not advance reviewed Jenkins source pins or deploy another revision.

| Flow | Observed proof |
| --- | --- |
| Auth | Public browser registration/password login, HttpOnly refresh rotation, CSRF logout/revocation, real moderator TOTP login |
| Scan/history | Real text/URL ONNX results (`aiAvailable=true`, `degraded=false`); owned history/source references and separate-user denial |
| Notification/watchlist | CRUD/preferences/read/read-all; real scan → Kafka → Reputation → exact owner's watch/risk notification |
| Report/evidence | Owned scan-linked creation, idempotent retries/conflict, private MinIO upload/download/delete, separate-user denial |
| Moderation | Actual Web queue/detail/verify; request-info → owner resubmit → PENDING_REVIEW; reject; stale version409; self-review403 |
| Lifecycle event | Actual report verification → transactional outbox/Kafka → exact report owner's VERIFIED notification |
| Runtime | Eleven PID/start-time-owned, non-elevated, loopback-only processes; readiness UP; ten JAR hashes match gated children; web archive matched |
| Data/config | Additive schema-specific migrations with real negative runtime-grant tests; Auth keys and existing credentials preserved; no tunnel/DNS edits |

Child builds: Gateway37, Auth40, Threat39, Report30, Reputation28, Model27, Notification25, Search24, Campaign24, Analytics25, Frontend22.

## Controlled privileged acceptance and cleanup

User explicitly approved separate synthetic identities. Two new fixture accounts completed application single-use email-token verification and real TOTP enrollment/confirmation. Verification tokens were consumed from only the controlled fixture's encrypted outbox; SMTP delivery is **not** asserted. An isolated operator request/activate granted a temporary fixture ADMIN. Public Auth API plus fresh TOTP step-up assigned a second fixture MODERATOR.

After public acceptance, that same managed-role API demoted the fixture MODERATOR and the existing operator CLI revoked fixture ADMIN. Both final roles are USER; both have zero active sessions. Registration, verification, MFA, role-change and operator request/activate/revoke audit records remain. No existing real account's privileges changed. Synthetic reports/evidence and their histories remain as identifiable test records; credentials/seeds/recovery codes remain restricted outside Git. No tokens, OTPs or cookies are archived.

## Evidence and rollback

Local evidence root: `M:\BaoTin\.changes\api-gateway-frontend-20261027-091037`.

`MODIFIED_FILE`, `DIFF_FILE`, `VERIFICATION.txt`, `ROLLBACK.sh` include original bundle hashes, exact commands/results, source changes, immutable pins and per-feature proofs. `live-moderator-acceptance.json` and `live-privileged-cleanup.json` cover the final public acceptance and cleanup. `final-deployment-acceptance.json` records the final runtime recheck.

The executable rollback script restores all seven original source HEADs into a **new independent directory**; the tested recovery copies are clean. It does not revert live schemas/data, modify keys or stop running services. Runtime rollback must use the existing compatible-reader Jenkins/direct-release procedure; no downgrade migration is implied. Pre-migration database backup remains outside source.

## Explicit remaining product work

Search/Campaign/Analytics remain the approved scaffold exclusions; their demo UI is not backend acceptance. Real-device FCM delivery and physical Android/iOS device picker/share acceptance remain separate. Frontend SSO/recovery screens remain its documented TODOs. Native platform exports are compilation proof, not store/device acceptance. Synchronous Threat → Reputation REST lookup is not configured; the asynchronous Reputation flow above is live-proven. Startup remains manual after reboot; no autostart, antivirus adjustment, hidden recovery worker, policy bypass or new scheduler was introduced.
