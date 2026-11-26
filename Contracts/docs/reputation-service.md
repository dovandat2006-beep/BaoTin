# Reputation Service contract notes

## Ownership

Reputation owns the risk score of a `ThreatTarget`:

- component signals and final score;
- risk level and confidence;
- score history;
- calculation version/weights;
- recalculation and cache invalidation.

It does **not** verify reports, own scan input or read `auth`, `threat`, `report`, `campaign` schemas. All external data arrives through the contracts below.

## Internal API

| Method | Path | Role |
|---|---|---|
| `GET` | `/internal/v1/reputation/targets/{threatTargetId}` | `ADMIN`/`MODERATOR` JWT or private read key |
| `POST` | `/internal/v1/reputation/targets/{threatTargetId}/recalculate` | `ADMIN` |

These paths are private: the Gateway must not publish `/internal/**`. The service is still a Resource Server and validates the Auth-issued JWT (issuer, audience, `typ=at+jwt`, `token_use=access`, session/version claims) before authorizing the role.

An optional separate `X-Service-Token` authenticates Threat only for the exact
target GET. Configure a random 256-bit key as 64 lowercase hex characters using
`reputation.threat-read-token` outside Git. Blank configuration disables the key;
malformed configuration fails startup. The key grants only `SCOPE_reputation.read`,
not an ADMIN/MODERATOR role. Wrong, blank, duplicate or comma-joined keys, and
requests combining the header with any Authorization header, return 401. A valid
key on writes/other internal paths returns 403 and performs no mutation. Existing
JWT-only callers remain compatible. Never send this key to a browser, publish
this endpoint, or use the key for recalculation. Rollout and rollback:
`reputation-read-bridge-20261102.md`.

A missing target returns `404`; the service never invents a score. `POST` requires `targetType` when the target has never been seen. Operator signal/count overrides are intended for backfill and demos. The normal path is event-driven.

## Events consumed

| Topic | Event types |
|---|---|
| `baotin.report.events.v1` | `report.verified`, `report.rejected`, `report.revoked` |
| `baotin.threat.events.v1` | `threat.updated` |
| `baotin.campaign.events.v1` | `campaign.updated` |

Consumer group: `reputation-service-v1`. Each event is deduplicated by `eventId` through `reputation.inbox_events`. A failed record is retried three times and then written to `<topic>.DLT`; the offset is committed only after processing or an acknowledged DLT write.

## Event produced

Topic `baotin.reputation.events.v1`, event type `reputation.score.changed`, published through `reputation.outbox_events` in the same transaction as the score/history update. The envelope follows the Master Plan section 17.3; the payload carries old/new score and level, confidence, components, calculation version and reason.

## Calculation v1

Weights are seeded in `reputation.calculation_config` and versioned. The seed is:

| Target type | report | rule | AI | campaign |
|---|---:|---:|---:|---:|
| `URL` / `OTHER` | 35% | 25% | 25% | 15% |
| `PHONE` / `BANK_ACCOUNT` / `EMAIL` | 55% | 20% | 0% | 25% |

`finalScore = clamp(0..100, Σ weight × signal)`, rounded to 2 decimals. Risk bands:

| Score | Level |
|---|---|
| 0–24.99 | `LOW` |
| 25–49.99 | `MEDIUM` |
| 50–74.99 | `HIGH` |
| 75–100 | `CRITICAL` |

Report signal is derived from verified contributions only:

```text
raw          = Σ severity × decay(age)
reportSignal = 100 × raw / (raw + 100)
```

The saturation constant is configurable per calculation version. Time decay:

| Age | Multiplier |
|---|---:|
| 0–30 days | 1.0 |
| 31–90 days | 0.8 |
| 91–180 days | 0.6 |
| >180 days | 0.4 |

Confidence is bounded to `[0,1]`:

```text
verifiedEvidence = min(1, verifiedReportCount / 5)
reportVolume     = min(1, totalReportCount / 10)
signalCoverage   = count(non-zero component signals) / 4
confidence       = 0.50 × verifiedEvidence
                 + 0.30 × reportVolume
                 + 0.20 × signalCoverage
```

`report.rejected` and `report.revoked` mark the stored report contribution `REVOKED`; they do not delete audit data. `report.verified` upserts the contribution.

## Ordering and idempotency

- Duplicate `eventId`: acknowledged and ignored by the Inbox unique key.
- Report contributions are keyed by `reportId`; an event older than the contribution's `last_event_at` is acknowledged but does not change state.
- Threat/campaign signals are applied only if the event timestamp is not older than the target's `last_event_at`.
- The target row is updated with `SELECT ... FOR UPDATE` and a `version` column; concurrent calculations serialize and increment the version.
- The final score is always constrained to `[0,100]` by the calculator and database checks.

## Compatibility

This is the v1 contract. New optional payload fields may be added; consumers ignore unknown fields. A breaking payload or semantic change requires a new event version/topic and a migration path. The OpenAPI file is internal-only and must not be routed by the public Gateway.

## Report producer compatibility

Report-Service publishes the canonical payload in events/report/report-events-v1.schema.json: targetId, targetType (URL or MESSAGE_TEMPLATE), reporterUserId, status and version. The Reputation consumer contract accepts this payload alongside the older threatTargetId/severity projection. New consumers must use targetId from canonical events and apply their own versioned contribution policy for a binary moderator verification; Report-Service does not invent a severity score. report.submitted and report.need_more_info are on the shared topic but do not create verified contributions. No report.revoked event is produced by the MVP terminal-state workflow.

Consumer policy v1 (2026-10-27): canonical VERIFIED contributes weight 100, canonical MESSAGE_TEMPLATE maps to the existing OTHER calculation weights and response type. Canonical REJECTED revokes the contribution. Non-terminal events do not create verified contributions. Canonical targetType/status/reporterUserId/version are validated; legacy threatTargetId/severity remains unchanged. Inbox deduplication and occurredAt ordering apply to both shapes.
