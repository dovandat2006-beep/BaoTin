# TODO — Campaign Service

> Trạng thái hiện tại: startup scaffold; chưa có nghiệp vụ. Master plan: `../BaoTin_Backend_Architecture_Master_Plan.md`.

## P0 — Contract và security boundary

- [ ] Tạo/cập nhật OpenAPI hoặc event schema trong `../Contracts`; thống nhất error `application/problem+json`.
- [ ] Thêm Spring Security Resource Server trước endpoint nghiệp vụ đầu tiên; xác minh issuer/audience/algorithm và authorize tại service.
- [ ] Chốt schema ownership, runtime DB role tối thiểu và cấm cross-schema access.
- [ ] Định nghĩa liveness/readiness dependency policy; không làm liveness phụ thuộc dịch vụ ngoài.

## P1 — Nghiệp vụ

- [ ] Thiết kế Flyway cho schema `campaign` và DB role riêng.
- [ ] Chốt rule cùng domain/phone/bank account, temporal burst và report pattern.
- [ ] Consume threat/report/reputation events qua Inbox, xử lý duplicate và out-of-order.
- [ ] Tạo campaign/member/link aggregates và publish `campaign.detected|updated` bằng Outbox.
- [ ] Mở API đọc campaign qua Gateway; endpoint thay đổi nội bộ phải bị chặn khỏi public routes.
- [ ] Thêm test determinism, duplicate membership, concurrent update và false-positive fixtures.

## P2 — Vận hành

- [ ] Structured logs/correlation ID, Micrometer metrics và alerts phù hợp domain.
- [ ] Testcontainers cho PostgreSQL/Kafka/Redis/MinIO chỉ khi service thực sự dùng dependency đó.
- [ ] Jenkins: unit/integration/security scan, image theo digest, smoke test và rollback.
- [ ] Cập nhật README, runbook và Definition of Done khi chuyển khỏi scaffold.

Search backfill: owning-role read-only snapshot exporter implemented/tested; no unrelated business endpoint or scheduler added.
