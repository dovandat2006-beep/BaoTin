# TODO — Analytics Service

> Trạng thái hiện tại: Phase 1 (HTTP read-through `/summary`, `/queue`, `/counts` từ Report-Service và Threat-Service) và Phase 2 (Flyway `V2`, Kafka event ingestion idempotent + out-of-order, fail-open `riskLevel` enrichment trên `/queue`, backfill CLI có checkpoint `AnalyticsBackfill`, và API xu hướng `GET /api/v1/analytics/admin/trends`) đã hoàn tất. Master plan: `../BaoTin_Backend_Architecture_Master_Plan.md`.

## P0 — Contract và security boundary

- [x] Accept Auth's existing `typ=at+jwt` access tokens through the real decoder;
  reject generic/missing type, wrong signature/algorithm/issuer/audience/use and
  expired tokens. Signed USER remains403; signed ADMIN/MODERATOR reach all three
  read endpoints. Fresh Jenkins promotion remains a separate delivery proof.

- [x] Tạo/cập nhật OpenAPI trong `../Contracts` (bao gồm `/api/v1/analytics/admin/trends`); thống nhất error `application/problem+json`.
- [x] Thêm Spring Security Resource Server trước endpoint nghiệp vụ đầu tiên; xác minh issuer/audience/algorithm và authorize tại service.
- [x] Chốt schema ownership: service chỉ đọc/ghi schema `analytics`, không cross-schema.
- [ ] Cấu hình runtime DB role tối thiểu chỉ có quyền trên schema `analytics` (ops/grant, không nằm trong Flyway).
- [x] Định nghĩa liveness/readiness dependency policy; liveness độc lập, readiness gồm database.

## P1 — Nghiệp vụ

- [x] Thiết kế Flyway `V1` + `V2` cho schema `analytics` (`moderation_queue_item`, `daily_metrics`, `inbox_events`, `processed_scans`, `known_targets`, `report_lifecycle_state`, `target_reputation_view`, `backfill_checkpoints`, `backfill_partition_checkpoints`).
- [x] Chốt event subscriptions và consumer group `analytics-service-v1` cho `baotin.scan.events.v1`, `baotin.report.events.v1` và `baotin.reputation.events.v1`.
- [x] Cài Inbox/idempotent consumer (`AnalyticsEventIngestor` + `AnalyticsKafkaListener`) trước khi cập nhật aggregate.
- [x] Tạo daily/risk aggregates (`GET /api/v1/analytics/admin/trends`) và backfill job có checkpoint (`BackfillService` + `AnalyticsBackfill` CLI cho NDJSON file và bounded Kafka replay).
- [x] Mở API dashboard phân trang/lọc trạng thái/priority, xác thực JWT và giới hạn ADMIN/MODERATOR.
- [x] Đọc read-through admin summary/queue/counts từ Report-Service và Threat-Service (forward bearer JWT + `X-Correlation-Id`, map lỗi upstream sang `503 ANALYTICS_UPSTREAM_UNAVAILABLE`).
- [x] Tích hợp sự kiện `reputation.score.changed` và `report.*` để làm giàu `riskLevel` kiểu fail-open trên `/queue` (trả `null` khi chưa có điểm hoặc khi DB lookup lỗi tạm thời).
- [x] Thêm kiểm thử 401/403, UTC `resolvedToday`, `highRisk` chưa xử lý, sort/filter và empty-store.
- [x] Thêm kiểm thử duplicate event, out-of-order event, checkpoint resume và không lưu/log PII/raw SMS.

## P2 — Vận hành

- [x] Structured logs/correlation ID, Micrometer metrics (`analytics.events.rejected`) không rò rỉ PII.
- [x] Testcontainers cho PostgreSQL 17 và Kafka (`@Tag("external-infrastructure")` trong `AnalyticsPostgresKafkaIntegrationTests`).
- [ ] Jenkins: unit/integration/security scan, image theo digest, smoke test và rollback.
- [x] Cập nhật README, runbook và Definition of Done khi hoàn tất Phase 2.
