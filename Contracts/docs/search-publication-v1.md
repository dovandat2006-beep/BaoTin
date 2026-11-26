# Complete public Search read projection — compatibility extension

The public GET remains anonymous through Gateway and read-only at the service.
type now accepts URL, PHONE, BANK_ACCOUNT, EMAIL, CAMPAIGN, OTHER. Existing URL
responses/IDs remain compatible. referenceId/status/metadata are persisted rather
than invented by a client. Only ACTIVE, explicitly public, non-empty snapshots are
searchable. Unknown risk remains null; risk comes from Reputation, never AI status.

## Owner streams and five domain events

- baotin.threat.search.events.v1: threat.created, threat.updated, producer
  threat-service, aggregate ThreatTarget; payload.threatTargetId and targetType.
- baotin.campaign.search.events.v1: campaign.created, campaign.updated, producer
  campaign-service, aggregate Campaign; payload.campaignId.
- Existing baotin.reputation.events.v1: reputation.score.changed remains unchanged.
- Existing baotin.search.targets.v1/search.target.updated remains supported during
  rolling upgrades. Existing risk-domain streams are not changed or repurposed.

Domain snapshots carry optional publicSearch. Absent means no public content may
be inferred from other payload fields. visibility PRIVATE, or status REMOVED,
withdraws prior content and lookup keys atomically; older events cannot resurrect
it. PUBLIC snapshots require matching documentType/referenceId, safe title/display,
status ACTIVE and an allowlisted metadata object. Metadata only permits campaignId
(UUID), bankCode (ASCII uppercase letters) and referenceType (contract enum).

Source services own publication decisions and write their domain state + Outbox
in one transaction. Search never reads their schema, changes a domain status or
marks a Report VERIFIED. The source Threat currently owns URL and message-template
targets: URLs publish hostname only; templates publish a fingerprint, not SMS text.
Campaign-Service business development remains owned by that repository; Search
supports and tests its contract without manufacturing production campaign data.

## Contact/account privacy and exact lookup

PHONE title is Điện thoại, BANK_ACCOUNT title is Tài khoản ngân hàng, EMAIL title
is Email. Phone/account display includes masking stars and exactly four digits;
email local part exposes at most one alphanumeric plus at least three stars.
No raw contact/account value enters title, search_text, metadata or the response.
Owner PUBLIC contact/account snapshots require lookupHash; raw query values are
never emitted by Search. lookupHash is SHA-256(UTF8(documentType + ':' + canonical)):

- PHONE: remove whitespace and + - ( ) .; retain8..15 ASCII digits, nonzero first;
  Vietnamese10-digit0-prefixed input becomes84 followed by its last9 digits.
- BANK_ACCOUNT: remove whitespace and hyphens; uppercase ASCII alphanumeric6..34.
- EMAIL: trim, lowercase; exactly one @, local part1..64 without whitespace/control,
  hostname accepted by the public-host policy. Query limit is254 characters (a backwards-compatible extension of the prior200 limit).

The hash is an exact-match index, not encryption/anonymization. Apply Gateway rate
limits, private DB/broker access and retention controls. Producer and consumer
implement this versioned canonicalization; changing it requires migration/rebuild.

## Independent clocks and compatibility

Inbox eventId and projection mutation commit together before Kafka offset commit.
Publication and Reputation timestamps/event IDs are independent: score-before-title
works, late titles do not erase risk, and private withdrawals survive later scores.
Campaign document IDs are namespaced display identities; referenceId remains the
owning Campaign UUID. New additive V2 migration preserves all prior URL rows/IDs.
Old URL-only runtimes can continue reading V2; new runtimes require owning migration
before rollout. Broker topics must be provisioned before enabling the new listener.
