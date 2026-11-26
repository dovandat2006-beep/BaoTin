# TODO — Notification Service

> Trạng thái hiện tại: API notification/watchlist, persistence, Kafka Inbox/Outbox, FCM adapter, scheduled retention và integration tests đã có. DB least-privilege, campaign events và production smoke vẫn là việc tiếp theo. Master plan: `../BaoTin_Backend_Architecture_Master_Plan.md`.

## P0 — Contract và security boundary

- [x] Tạo/cập nhật OpenAPI hoặc event schema trong `../Contracts`; thống nhất error `application/problem+json`.
- [x] Thêm Spring Security Resource Server trước endpoint nghiệp vụ đầu tiên; xác minh issuer/audience/algorithm và authorize tại service.
- [ ] Chốt schema ownership, runtime DB role tối thiểu và cấm cross-schema access. (schema/migration đã có; Infra vẫn cấp ALL trên schema)
- [x] Định nghĩa liveness/readiness dependency policy; liveness chỉ process, readiness gồm DB, không phụ thuộc Kafka/FCM.

## P1 — Nghiệp vụ

- [x] Thiết kế retention cho token/log; scheduled job xóa theo batch, giữ inbox/outbox chưa xử lý và có V2 index retention.
- [x] Tạo API device token, preferences, watchlist và inbox notifications với ownership checks.
- [x] Consume reputation/report events bằng Inbox và tạo notification idempotently. Campaign event chưa có contract.
- [x] Tích hợp FCM qua adapter, retry/backoff, invalid-token cleanup và delivery audit.
- [x] Publish event cần thiết bằng Outbox; không log provider token hoặc nội dung nhạy cảm.
- [x] Thêm JWT/RBAC, pagination và test user không đọc/sửa notification của người khác.

## P2 — Vận hành

- [ ] Structured logs/correlation ID, Micrometer metrics và alerts phù hợp domain. (correlation ID đã có)
- [ ] Testcontainers cho PostgreSQL/Kafka/Redis/MinIO chỉ khi service thực sự dùng dependency đó. (PostgreSQL đã có)
- [ ] Jenkins: unit/integration/security scan, image theo digest, smoke test và rollback.
- [x] Cập nhật README, runbook và Definition of Done khi chuyển khỏi scaffold.

## API integration delivery — 2026-10-27

- [x] Source-copy public Gateway/contract and adapter regressions pass with JDK21/typecheck/Jest.
- [x] Isolated real Auth/TOTP/Gateway/Scan/History/Report/Evidence/Moderation/Kafka/Reputation/Notification/Watchlist chain passes, including ownership and moderator denials.
- [x] Desktop/mobile real browser Web login/logout, watchlist, scan/history and report evidence pass without API mocking.
- [ ] Promote exact reviewed SHAs through Jenkins and verify public-domain artifact/process/API evidence; fixture success is not production acceptance.
