# Auth email/security notices and lifecycle outbox

## SMTP
Gmail uses SMTP 587, STARTTLS required, certificate hostname validation and an App Password.
Fill `SMTP_ENABLED`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM` outside Git.
Set `SMTP_HELO_HOST` to a configured service hostname; an explicit EHLO/mail host avoids
unbounded reverse-DNS lookup during message generation. Mailpit is a dev/test SMTP sink,
not a Gmail replacement for the live delivery gate.

Verification/reset payloads are encrypted in PostgreSQL. A worker claims a bounded batch,
commits a five-minute lease, then sends without holding a DB transaction over network I/O.
Message-ID derives from the durable outbox ID. Completion/failure updates must match the
current lease; a crashed worker's expired lease can be retried. Five failed mail attempts
stop automatic delivery; retention removes expired one-day payloads. Sent payload is cleared.

Audited security changes create a notice atomically through a DB trigger, including
operator changes. Verified-email accounts receive a generic alert; no password, OTP,
refresh/access token or raw phone is included. Unverified/phone-only accounts do not receive
email to an unproven address. SMTP downtime does not undo the security state change.
At-least-once SMTP delivery may duplicate after send-before-DB-mark crash; Message-ID assists
client dedupe but is not an exactly-once delivery guarantee.

## Auth events
User row changes and `auth.outbox_events` are in the same PostgreSQL transaction, enforced
by a SECURITY DEFINER trigger owned by the migrator. Runtime cannot forge/update envelopes.
Events use `baotin.auth.events.v1`; schema is in Contracts/events/auth/user-events-v1.schema.json.
The payload only contains user UUID, role/status/securityVersion, never contact or credentials.

`AUTH_EVENTS_ENABLED=true` starts the Kafka producer. Configure bootstrap/security protocol
and credentials outside Git. The producer waits for broker acknowledgement before marking
published. Failed sends back off; retry/crash resend retains eventId and aggregate key.
Only the earliest unpublished event per aggregate is eligible, preserving source order.
Consumers in other services must implement Inbox eventId dedupe; broker idempotence alone
does not remove crash-after-send duplicates.

Pending/failed counts are registered as `baotin.auth.delivery.pending/failed{kind}`.
Management metrics remain non-public; expose/scrape only through reviewed internal monitoring.
An exhausted earliest event blocks that aggregate. The isolated `deliveryOperator` CLI may
retry an exhausted, pending and unleased EVENT with an audit reason; MAIL must additionally
be less than 15 minutes old so stale verification/reset content is not blindly resent.
Runtime cannot execute the retry function or alter event envelopes.

```powershell
.\gradlew.bat deliveryOperator --args='EVENT EVENT_UUID "broker restored approved replay"'
```

## Proof
Tests use real Mailpit SMTP and a real Kafka broker: sending, security alerts, broker/SMTP
outage and retry, stale lease rejection, lease recovery, transaction rollback and duplicate
stable-eventId resend. Actual Gmail delivery now passes using the production Auth worker
with isolated PostgreSQL and sender-self mailbox. `Infra/team/Test-LiveGmailDelivery.ps1`
sends one verification fixture message; `-TestRetry` first points only that fixture worker
at an unreachable loopback port, asserts lease release/backoff, then restores Gmail587.
The same outbox row completes on attempt2 and its encrypted payload becomes empty.
Reports do not contain the mailbox, password, OTP or token. SMTP acceptance is not an
inbox-read/client-activation claim. The fixture is temporary; its mailed verification
code is not intended for a permanent user account.
