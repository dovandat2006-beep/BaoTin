# Search v1 — public projection

GET /api/v1/search is anonymous, Gateway-only externally; all other Search paths
are denied. No identity/header is required for this public read. Parameters: q
(trimmed 2..200 characters), type (URL only in this increment), riskLevel
(LOW/MEDIUM/HIGH/CRITICAL), page (0..1000), size (1..50, default 20).
Response: items/page/size/totalItems/totalPages. Each item has UUID id/referenceId,
documentType URL, title/displayValue (host only), nullable riskScore/riskLevel,
status ACTIVE, metadata {}, updatedAt UTC. Missing risk is unknown, never LOW.
Ranking: exact host, FTS rank, trigram similarity, updatedAt desc, UUID asc.
Errors use application/problem+json; invalid parameters 400, database failure 503.
Never return raw URLs, URL paths/query/userinfo, SMS, report content or private metadata.

Additive event: baotin.search.targets.v1 / search.target.updated v1, produced by
Threat in the same transaction/outbox as the target. Envelope uses existing v1
conventions; payload {targetId UUID, targetType URL, displayValue host}. Host is
ASCII DNS name or IP without path/port; private/local hosts are not indexed.
Existing threat/scan events are unchanged. Consumer also reads existing
baotin.reputation.events.v1 / reputation.score.changed (ThreatTarget), takes
newScore/newLevel/changedAt without recalculating score. Independent target/risk
clocks handle reordering. Inbox eventId and projection update share a transaction;
offset commits after success. Poison messages retry, not silently acknowledge.

Rebuild: replay retained topics into a new isolated search schema/database using
SEARCH_CONSUMER_GROUP with a fresh group and auto-offset-reset=earliest. Do not
truncate a live projection; compare counts/privacy/query tests before switching.
This replays only retained history. Historical targets predating this event need
an owning-service export/backfill; never SELECT another service schema.
Campaign/PHONE/BANK_ACCOUNT/EMAIL projections, live rebuild checkpoint orchestration
and historical backfill are explicitly not implemented by this URL increment.
