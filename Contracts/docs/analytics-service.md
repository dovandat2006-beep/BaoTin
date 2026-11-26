# Analytics Service

Analytics-Service owns the admin dashboard read API and asynchronous analytical
projections. It does not persist report bodies, evidence, raw messages,
`reporterUserId`, `traceId`, tokens, scan identity or target content. Live
moderation endpoints (`/summary`, `/queue`, `/counts`) compose role-checked read
models directly from the owning services, while Phase 2 adds Kafka event
ingestion into `analytics.*` tables for `/trends` and fail-open `riskLevel`
enrichment:

| Dashboard data | Source | Notes |
| --- | --- | --- |
| Queue rows, `pendingReview`, `needMoreInfo`, `resolvedToday`, `byStatus`, `byPriority` | Report-Service `GET /internal/v1/analytics/moderation` | Caller's `Authorization` bearer token and `X-Correlation-Id` are forwarded; Report-Service revalidates `ADMIN`/`MODERATOR`. |
| `scansToday`, `highRisk` | Threat-Service `GET /api/v1/scans/admin/stats` | Caller's `Authorization` bearer token and `X-Correlation-Id` are forwarded; Threat-Service revalidates `ADMIN`/`MODERATOR` and counts every scan (including guest scans) without storing scan identity or raw input. |
| Queue `riskLevel` | Event-backed `analytics.report_lifecycle_state` + `analytics.target_reputation_view` | Single fail-open query per queue page populated from `baotin.report.events.v1` and `baotin.reputation.events.v1`. Missing records or DB lookup failures return `null` without failing `/queue`. Clients must display an explicit unknown state and must not render `null` as `LOW`. |
| `/trends` (`AnalyticsTrends`) | Local `analytics.daily_metrics` | Populated by Kafka consumer group `analytics-service-v1` and `AnalyticsBackfill` CLI; never calls upstream HTTP read APIs. |

## Endpoints

All public endpoints are exposed through API-Gateway under `/api/v1/analytics/admin/**`
and require `ADMIN` or `MODERATOR`:

- `GET /api/v1/analytics/admin/summary`
- `GET /api/v1/analytics/admin/queue?status=&priority=&page=&size=&sort=`
- `GET /api/v1/analytics/admin/counts`
- `GET /api/v1/analytics/admin/trends?days=14`

The queue endpoint supports `submittedAt,asc` and `submittedAt,desc` (other sort
fields return `400 INVALID_INPUT`); `page` is limited to `0..10000` (default `0`)
and page `size` is limited to `1..100` (default `20`). The trends endpoint
accepts optional `days` (`1..90`, default `14`), returning `AnalyticsTrends`
(`days`, `fromDate`, `toDate`, `totals`, `riskBreakdown`, and a contiguous UTC
chronological `series` zero-filled for days without rows). Because `/trends`
reads local `analytics.daily_metrics` PostgreSQL projections only, it returns
`200`, `400`, `401`, `403`, or `500` (`application/problem+json` with
`X-Correlation-Id`) and does not return `503 ANALYTICS_UPSTREAM_UNAVAILABLE`.
Invalid status, priority, sort, paging, `days` bounds, or an unknown route
returns a problem document (`application/problem+json`).

## Internal Report-Service read contract

Analytics reads moderation data from Report-Service via:

```http
GET /internal/v1/analytics/moderation?status=&priority=&page=0&size=20&sort=submittedAt,desc
Authorization: Bearer <caller ADMIN/MODERATOR access token>
X-Correlation-Id: <correlation-id>
```

- Query parameters: optional `status` (`SUBMITTED|PENDING_REVIEW|NEED_MORE_INFO|VERIFIED|REJECTED`),
  optional `priority` (`LOW|MEDIUM|HIGH`), `page` (`0..10000`, default `0`),
  `size` (`1..100`, default `20`), and `sort` (`submittedAt,asc` or
  `submittedAt,desc`, default `submittedAt,desc`). Invalid values return `400`
  (`INVALID_MODERATION_FILTER`).
- Response (`AnalyticsModerationView`):
  - `items`: array of `{ id, targetType, target, category, title, status, priority, submittedAt, evidenceCount, moderatorNote }`
    where `target` comes from `report.target_display`, `submittedAt` is
    `report.created_at`, and `evidenceCount` counts non-deleted evidence rows
    (`deleted_at IS NULL`). Report descriptions, reporter IDs, evidence files,
    and audit history are not returned.
  - `page`, `size`, `totalItems`, `totalPages`
  - `counts`: global (unfiltered by `status`/`priority` query parameters)
    `byStatus` (all 5 statuses), `byPriority` (`LOW`, `MEDIUM`, `HIGH`), and
    `resolvedToday`.
- For `/summary` and `/counts`, Analytics requests `page=0&size=1&sort=submittedAt,desc`
  so Report returns global `counts` without transferring a full queue page.
- **Internal-only rule:** `/internal/v1/analytics/**` is private to service-to-service
  calls and must never be routed through API-Gateway or added to the public
  Analytics OpenAPI `paths`.

## Time, priority and failure semantics

- `resolvedToday` uses the current UTC day and counts only `VERIFIED` or
  `REJECTED` reports whose `reviewed_at` falls within that UTC day.
- `scansToday` and `highRisk` (`highRiskScansToday`) use the current UTC day and
  include `HIGH` and `CRITICAL` scans.
- **Priority limitation:** Report-Service does not yet have a priority writer, so
  reports retain the database default `MEDIUM`. The `priority` filter and
  `byPriority` counts query the real stored column, which will reflect all
  `MEDIUM` until Report moderation/rules assign `LOW`/`HIGH`.
- **Upstream failure (`503`):** On `/summary`, `/queue`, and `/counts`, a failed,
  timed-out, 4xx/5xx, or malformed/negative Report-Service or Threat-Service
  dependency response returns `503` (`application/problem+json`,
  `code: ANALYTICS_UPSTREAM_UNAVAILABLE`) rather than synthetic zeros or fallback
  data. Logs emit only the upstream name and correlation ID.

## Phase 2 Event Pipeline, Schema Alignment & Backfill

- **Kafka consumer group (`analytics-service-v1`):** When
  `ANALYTICS_EVENTS_ENABLED=true`, Analytics consumes three versioned topics
  (`enable-auto-commit=false`, `ack-mode=record`):
  - `baotin.scan.events.v1` (`scan.completed`, `target.created`, `target.updated`)
  - `baotin.report.events.v1` (`report.submitted` with `version >= 0`,
    `report.verified`, `report.rejected`, `report.need_more_info`)
  - `baotin.reputation.events.v1` (`reputation.score.changed`)
- **Idempotency & out-of-order safety:** `analytics.inbox_events` deduplicates
  by `event_id` and SHA-256 `payload_hash` (hash conflicts are logged without
  raw payload/PII and ignored); `analytics.processed_scans` deduplicates by
  `scan_id`; `analytics.known_targets` ensures `new_threats` is credited once
  on `target.created` even if `target.updated` arrives earlier;
  `analytics.report_lifecycle_state` tracks `last_version` + independent
  per-milestone flags (`created`, `verified`, `rejected`, `need_more_info`) so
  out-of-order milestones attribute to each event's UTC `occurredAt` date
  without regressing state; `analytics.target_reputation_view` updates
  `risk_level` monotonically when `changedAt` advances.
- **Schema alignment (`sms_scans` & `active_users`):**
  - `events/scan/scan-events-v1.schema.json` emits `scanType: "TEXT" | "URL"`
    (`"SMS"` never occurs), so Flyway `V2` renames `sms_scans` -> `text_scans`
    and the API exposes `textScans` (never `smsScans`).
  - `scan.completed` intentionally emits `authenticated: boolean` without
    `userId`, and `events/auth/user-events-v1.schema.json` emits account
    lifecycle changes only, so Flyway `V2` drops `active_users` and the API
    never fabricates or exposes `activeUsers`.
  - Scan risk distribution (`risk_low_scans` .. `risk_critical_scans`) and
    `reports_need_more_info` are stored in `analytics.daily_metrics`.
    Category-level daily breakdowns remain deferred until `report-events-v1`
    carries `category`.
- **Fail-open queue `riskLevel` enrichment:** `/queue` performs one lookup per
  page joining `analytics.report_lifecycle_state` with
  `analytics.target_reputation_view`. Unknown targets or `DataAccessException`
  errors fall back to `riskLevel: null` rather than failing `/queue`.
- **Checkpointed backfill (`AnalyticsBackfill` CLI):** Packaged in the
  Analytics-Service JAR (`com.baotin.analytics.backfill.AnalyticsBackfill` via
  `PropertiesLauncher`; not exposed over HTTP). Supports `file` / `resume-file`
  (UTF-8 NDJSON wrapped `{"topic": ..., "event": ...}` or bare event envelopes,
  checkpointed via `analytics.backfill_checkpoints.last_cursor`), `replay` /
  `resume-replay` (ephemeral `group.id = null` consumer bounded by `endOffsets`
  and checkpointed per partition in `analytics.backfill_partition_checkpoints`
  without touching live `analytics-service-v1` offsets), and `status <runId>`.
  Configured with `ANALYTICS_BACKFILL_URL`, `ANALYTICS_BACKFILL_USER`, and
  `ANALYTICS_BACKFILL_PASSWORD_FILE`.

## Security

Analytics validates the Auth-issued RS256 access token (`issuer`, `audience`,
`typ=at+jwt`, `token_use=access`, signature via JWKS, and `exp`/`nbf` lifetime)
and checks `ADMIN`/`MODERATOR` roles again after Gateway, while `API-Gateway`,
`Report-Service`, and `Threat-Service` additionally enforce `kid`, `sid`,
`ver`, `jti`, `iat`/TTL, and `sub` (parsed as UUID in `Report-Service` and
`Threat-Service`). Report-Service (`/internal/v1/analytics/**`) and
Threat-Service (`/api/v1/scans/admin/stats`) are called with the caller's
forwarded `Authorization` bearer token and `X-Correlation-Id`, and each service
revalidates `ADMIN`/`MODERATOR` on every request so no separate service-to-service
credential is stored by Analytics.

## Configuration

### Local environment

```text
JWT_JWKS_URI=http://localhost:8081/.well-known/jwks.json
JWT_ISSUER=baotin-auth
JWT_AUDIENCE=baotin-api
ANALYTICS_REPORT_URI=http://localhost:8183
ANALYTICS_THREAT_URI=http://localhost:8182
ANALYTICS_UPSTREAM_TIMEOUT_MS=3000
ANALYTICS_EVENTS_ENABLED=false
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
ANALYTICS_KAFKA_GROUP=analytics-service-v1
SCAN_EVENTS_TOPIC=baotin.scan.events.v1
REPORT_EVENTS_TOPIC=baotin.report.events.v1
REPUTATION_EVENTS_TOPIC=baotin.reputation.events.v1
```

### Container and configtree deployments

Containerized compose deployments set internal service DNS addresses, timeout,
and Kafka ingestion:

```text
ANALYTICS_REPORT_URI=http://report-service:8080
ANALYTICS_THREAT_URI=http://threat-service:8080
ANALYTICS_UPSTREAM_TIMEOUT_MS=3000
ANALYTICS_EVENTS_ENABLED=true
```

Deployments that mount external Spring `configtree` directories without an
`environment` block (such as `Infra/alternative/docker-compose.yml` or Kubernetes
`ConfigMap`s) must provide the corresponding Spring properties (`analytics.report-uri`,
`analytics.threat-uri`, `analytics.upstream-timeout-ms`) or environment variables;
otherwise Analytics defaults to `localhost:8183` / `localhost:8182` and upstream
calls will fail with `503 ANALYTICS_UPSTREAM_UNAVAILABLE`.
