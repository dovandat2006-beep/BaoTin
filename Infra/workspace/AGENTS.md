# AGENTS.md — BảoTin workspace

## Bắt buộc trước khi sửa
- Đọc `BaoTin_Backend_Architecture_Master_Plan.md`, README/TODO của repo đang làm và AGENTS gần nhất.
- Nếu người dùng gọi `/graphify`, dùng skill graphify trước mọi thao tác khác.
- Đây là workspace nhiều repo; không giả định một commit/build bao phủ toàn bộ thư mục.

## Kiến trúc đã chốt
- Client là Expo Web + React Native; bỏ mọi giả định Flutter.
- Client chỉ gọi API-Gateway. `/internal/**`, PostgreSQL, Redis, Kafka, MinIO admin và Model Inference không public.
- PostgreSQL một database vật lý, schema/role riêng từng service; không cross-schema query/FK/transaction.
- Auth sở hữu identity/JWT/RBAC/MFA. Gateway xác minh ở edge; service nghiệp vụ vẫn phải authorize tại boundary.
- Kafka chỉ cho domain events; state + Outbox cùng transaction, consumer dùng Inbox/idempotency.
- AI là signal, không quyết định report VERIFIED.

## Quy tắc thay đổi
- Làm thay đổi nhỏ nhất đáp ứng task; không triển khai TODO của repo khác.
- Contract public/internal hoặc event phải cập nhật `Contracts` trước/đồng thời và có compatibility path.
- Không commit secret, token, private key, `.env`, dữ liệu thật hoặc log nhạy cảm. Chỉ thêm placeholder vào `.env.example`.
- Thêm test cho hành vi mới; chạy wrapper của chính repo. Không dùng `-x test` để xác nhận hoàn thành.
- Error API dùng `application/problem+json`, camelCase, UUID và ISO-8601 UTC.
- Không tin role/user headers từ client; không nhận role/status/verified flags qua mass assignment.

## Definition of Done tối thiểu
- Build/test pass bằng JDK 21; health/readiness pass trong container.
- README/TODO/contract liên quan được cập nhật.
- Không mở thêm host port hoặc quyền DB nếu chưa có lý do và negative test.
- Log không chứa password, OTP, JWT, refresh token, OAuth code hay raw PII.
