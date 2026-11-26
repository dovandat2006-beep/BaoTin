# Analytics Service

## Auth access-token compatibility — 2026-11-10

Analytics validates Auth's signed RS256 `typ=at+jwt` access tokens explicitly.
Spring's generic `JWT` type default previously rejected valid Auth tokens with401,
which caused the frontend's existing failed-refresh path to return to sign-in.
Signature/JWKS, issuer, audience, timestamps, `token_use=access` and service-side
ADMIN/MODERATOR checks remain enforced. Missing/generic JWT types are rejected,
not accepted by disabling type validation. Real signed-token/JWKS HTTP-filter
regressions cover all three endpoints; they do not mock JWT authentication.
Run `.\gradlew.bat test` on JDK21, including `AuthAccessTokenTests`.

Tổng hợp số liệu nghiệp vụ từ domain events để phục vụ dashboard; không nằm trên critical path và không lưu raw SMS.

## Trạng thái

Read model và pipeline sự kiện admin đã triển khai (Phase 1 + Phase 2), gồm bốn endpoint chỉ đọc:

- `GET /api/v1/analytics/admin/summary`
- `GET /api/v1/analytics/admin/queue`
- `GET /api/v1/analytics/admin/counts`
- `GET /api/v1/analytics/admin/trends` (`days` từ `1..90`, mặc định `14`)

Ba endpoint health `/actuator/health`, `/actuator/health/liveness` và `/actuator/health/readiness` là public. Mọi request dưới `/api/v1/analytics/admin/**` cần bearer JWT hợp lệ (RS256, issuer/audience, `token_use=access`) và vai trò `ADMIN` hoặc `MODERATOR`. API Gateway kiểm tra lại vai trò ở edge; service tự kiểm tra trên mỗi request. Các path khác bị `denyAll`, và lỗi trả về theo `application/problem+json`.

Service sở hữu schema `analytics` do Flyway (`V1` + `V2`) quản lý:

- `analytics.moderation_queue_item` — bảng read model hàng chờ kiểm duyệt.
- `analytics.daily_metrics` — số liệu scan (`total_scans`, `text_scans`, `url_scans`, `high_risk_scans`, `risk_low_scans`..`risk_critical_scans`), report (`reports_created`, `reports_verified`, `reports_rejected`, `reports_need_more_info`) và `new_threats` theo ngày UTC.
- `analytics.inbox_events`, `analytics.processed_scans`, `analytics.known_targets` — chống trùng lặp idempotent theo `eventId`, `scanId` và `targetId`.
- `analytics.report_lifecycle_state`, `analytics.target_reputation_view` — xử lý sự kiện report/reputation đến không theo thứ tự (out-of-order) và làm giàu `riskLevel` kiểu fail-open cho `/queue`.
- `analytics.backfill_checkpoints`, `analytics.backfill_partition_checkpoints` — lưu trạng thái backfill theo lô có checkpoint từ file NDJSON hoặc Kafka topic replay.

Kiến trúc đọc kết hợp (hybrid):

- Ba endpoint `/summary`, `/queue` và `/counts` đọc trực tiếp (HTTP read-through) từ `Report-Service` (`/internal/v1/analytics/moderation`) và `Threat-Service` (`/api/v1/scans/admin/stats`), chuyển tiếp bearer JWT của caller và `X-Correlation-Id`. Khi upstream không sẵn sàng, timeout, trả 4xx/5xx hoặc payload không hợp lệ, Analytics trả `503` (`application/problem+json`, `code: ANALYTICS_UPSTREAM_UNAVAILABLE`) và không fallback về số 0 giả.
- Trên `/queue`, `AnalyticsService` làm giàu `riskLevel` theo cơ chế **fail-open** từ `analytics.report_lifecycle_state` kết hợp `analytics.target_reputation_view`: nếu target chưa có điểm reputation hoặc truy vấn DB gặp lỗi tạm thời, `riskLevel` trả `null` mà không làm hỏng `/queue`.
- Endpoint `/trends` đọc trực tiếp từ `analytics.daily_metrics` trong PostgreSQL của `Analytics-Service`, điền `0` cho các ngày UTC chưa có sự kiện trong cửa sổ `[fromDate..toDate]` và tổng hợp `totals` cùng `riskBreakdown` (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).
- Khi `ANALYTICS_EVENTS_ENABLED=true`, `AnalyticsKafkaListener` tiêu thụ `baotin.scan.events.v1`, `baotin.report.events.v1` và `baotin.reputation.events.v1` với `ack-mode=record`, tự động bỏ qua poison-pill kèm bộ đếm Micrometer `analytics.events.rejected` (chỉ log metadata, không log PII/raw JSON) và retry khi lỗi DB tạm thời.

## Chạy local

Yêu cầu JDK 21 và PostgreSQL cho profile chạy thật. Flyway tạo schema `analytics` khi `DB_CREATE_SCHEMA=true`.

```powershell
.\gradlew.bat test
.\gradlew.bat integrationTest
.\gradlew.bat bootRun
```

```bash
curl http://localhost:8189/actuator/health
curl http://localhost:8189/actuator/health/liveness
curl http://localhost:8189/actuator/health/readiness
```

Liveness chỉ dùng `livenessState`; readiness gồm `readinessState` và `db`, nên database không sẵn sàng sẽ làm readiness DOWN. Docker image chạy non-root và dùng `/actuator/health` làm healthcheck.

### Backfill CLI (`AnalyticsBackfill`)

Công cụ vận hành nội bộ `com.baotin.analytics.backfill.AnalyticsBackfill` (không mở qua HTTP) dùng `ANALYTICS_BACKFILL_URL`, `ANALYTICS_BACKFILL_USER` (`baotin_analytics` hoặc `baotin_analytics_migrator`) và `ANALYTICS_BACKFILL_PASSWORD_FILE`:

- `file <path> [batchSize]` / `resume-file <runId> <path> [batchSize]`
- `replay <bootstrapServers> [batchSize] [topicsCommaSeparated]` / `resume-replay <runId> <bootstrapServers> [batchSize]`
- `status <runId>`

### Cấu hình

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `SERVER_PORT` | `8189` | Cổng HTTP của service |
| `DB_URL` | `jdbc:postgresql://localhost:5432/baotin` | JDBC URL PostgreSQL |
| `DB_USER` | `baotin_analytics` | Runtime DB user |
| `DB_PASSWORD` | rỗng | Mật khẩu DB, cấp ngoài Git |
| `DB_MIGRATION_ENABLED` | `true` | Bật/tắt Flyway |
| `DB_CREATE_SCHEMA` | `true` | Cho Flyway tạo schema `analytics` |
| `JWT_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | JWKS của Auth-Service |
| `JWT_ISSUER` | `baotin-auth` | Issuer bắt buộc |
| `JWT_AUDIENCE` | `baotin-api` | Audience bắt buộc |
| `ANALYTICS_REPORT_URI` | `http://localhost:8183` | Base URI của Report-Service |
| `ANALYTICS_THREAT_URI` | `http://localhost:8182` | Base URI của Threat-Service |
| `ANALYTICS_UPSTREAM_TIMEOUT_MS` | `3000` | Timeout kết nối và đọc HTTP upstream (ms) |
| `ANALYTICS_EVENTS_ENABLED` | `false` | Bật/tắt Kafka listener (`true` trong Compose/prod) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Danh sách Kafka brokers |
| `ANALYTICS_KAFKA_GROUP` | `analytics-service-v1` | Consumer group ID |
| `SCAN_EVENTS_TOPIC` | `baotin.scan.events.v1` | Topic sự kiện scan và threat target |
| `REPORT_EVENTS_TOPIC` | `baotin.report.events.v1` | Topic sự kiện vòng đời report |
| `REPUTATION_EVENTS_TOPIC` | `baotin.reputation.events.v1` | Topic sự kiện thay đổi điểm reputation |

Runtime DB user chỉ nên có quyền trên schema `analytics`; cấu hình grant tối thiểu thuộc phần vận hành, không nằm trong migration.

## Ownership

- Sở hữu: schema `analytics` (`moderation_queue_item`, `daily_metrics`, `inbox_events`, `processed_scans`, `known_targets`, `report_lifecycle_state`, `target_reputation_view`, `backfill_checkpoints`, `backfill_partition_checkpoints`).
- Không đọc chéo schema, không lưu raw SMS/PII, không thay đổi dữ liệu nguồn.
- Public/internal APIs và events phải được chốt trong `../Contracts` trước khi tích hợp.

## Tiếp tục triển khai

Xem [`TODO.md`](TODO.md).

## Jenkins JAR build/release

Pipeline lấy source từ main đã được bảo vệ, chạy JDK21 bootJar, secret/dependency scan và SBOM cho JAR; khi BUILD_DOCKER_IMAGE=true thì build/scan image và publish ghcr.io/vietluccuu/analytics-service theo commit SHA/digest. Lane release này không chạy test (test chạy ở máy dev và ở lane PR GitHub Actions) và không có auto-deploy qua SSH. Orchestration/deployment được quản lý tại Infra; Docker image cũng dùng được cho deployment thủ công. GitHub Actions chỉ verify trên PR/manual, không deploy production khi push.

## SonarQube quality gate

Release: checkout/preflight, compile main classes + Sonar, chờ quality gate PASS,
sau đó mới `bootJar`, security/SBOM và Docker/publish tùy chọn. Java cần bytecode
để phân tích nhưng không đóng JAR trước gate. Không chạy test/JUnit/JaCoCo ở release;
unit/integration tests vẫn ở verify lane và máy dev. Không lấy coverage cũ để chứng minh
release. Gate vẫn bắt buộc metric bảo mật/chất lượng, bind analysisId, không bỏ qua lỗi.
Policy và regression: `../Infra/ci/README.md`.

## Additive Analytics V2 rollout

`analytics.daily_metrics.sms_scans` remains the canonical stored text-scan counter;
`textScans` is the unchanged API name. V2 preserves `sms_scans`, `active_users`
and all existing values. Back up the owning schema and run the operator-only
`com.baotin.analytics.operator.DatabaseMigration` from the approved JAR with
`DB_URL` and external `DB_MIGRATION_PASSWORD_FILE` before startup. Keep runtime
Flyway disabled and the runtime account distinct. Enable the listener with
`analytics.events.enabled=true` and the reviewed broker in the external config.
