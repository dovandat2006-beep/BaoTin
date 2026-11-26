# TODO — Reputation Service

> Trạng thái: P0/P1 đã triển khai; P2 còn vận hành hóa. Master plan: `../BaoTin_Backend_Architecture_Master_Plan.md`.

## P0 — Contract và security boundary

- [x] OpenAPI nội bộ + event schema trong `../Contracts`; error dùng `application/problem+json`.
- [x] Spring Security Resource Server trước endpoint nghiệp vụ; xác minh issuer/audience/algorithm/`typ`/`token_use` và authorize tại service.
- [x] Chốt schema ownership, DB role tối thiểu; không cross-schema query/FK.
- [x] Liveness chỉ `livenessState`; readiness gồm `readinessState,db`; Redis/Kafka không chặn liveness.

## P1 — Nghiệp vụ

- [x] Flyway schema `reputation`, DB role riêng, `version` optimistic locking và `FOR UPDATE`.
- [x] Calculation version/weights/risk bands/confidence/time-decay lấy từ `calculation_config` (fallback default).
- [x] Consume report/threat/campaign events qua Inbox, idempotent và xử lý out-of-order.
- [x] Lưu component/history audit; publish `reputation.score.changed` bằng Outbox.
- [x] Internal read/recalculate APIs, JWT authorization, Redis cache invalidation.
- [x] Separate optional read-only Threat service key, with strict header/scope/method/path boundaries and legacy JWT compatibility.
- [x] Promote private read bridge through Jenkins and verify actual Gateway scan reputation signal (FullStack7, 2026-11-02): real stored/public score 24.96; read200, invalid/mixed401, writes403; eleven runtime owners/readiness UP.
- [x] Test score luôn [0,100], duplicate/out-of-order, report revoke, persistence/outbox, negative authorization.

- [x] Tương thích canonical Report lifecycle và legacy severity; policy VERIFIED=100, MESSAGE_TEMPLATE→OTHER; regression idempotency/rejection/out-of-order.

## P2 — Vận hành

- [ ] Structured logs/correlation ID xuyên Kafka, Micrometer metrics và alert theo domain.
- [ ] Testcontainers Kafka round-trip/DLT và Redis cache integration; load test recalculation.
- [ ] Jenkins: smoke test image, deploy theo digest và rollback tự động.
- [ ] Endpoint lịch sử score/audit nếu Report/Analytics cần.
- [ ] Runbook khi outbox `FAILED`, DLT backlog hoặc calculation config đổi version.

Search backfill: owning-role read-only snapshot exporter implemented/tested; no unrelated business endpoint or scheduler added.
