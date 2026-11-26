# AGENTS.md — Notification-Service

## Phạm vi
- Quản lý device token, preference, watchlist, in-app/push delivery và audit lần gửi..
- Ranh giới: Không gửi email xác minh/reset của Auth trong tranche hiện tại và không tự tính risk..

## Cách làm việc
- Đọc `../AGENTS.md`, `README.md` và `TODO.md` (nếu có) trước khi sửa.
- Chỉ nhận dữ liệu qua contract; không đọc/ghi schema của service khác.
- Không thêm endpoint nghiệp vụ placeholder trả dữ liệu giả. Việc chưa làm phải nằm trong TODO.
- Không commit secret. Dùng biến môi trường/file mount và cập nhật `.env.example` bằng placeholder.
- Mọi thay đổi public API/event cần sửa `../Contracts` và thêm compatibility/test.

## Kiểm chứng
- Chạy `./gradlew test` và `./gradlew bootRun` phù hợp với thay đổi.
- Với backend: health/liveness/readiness phải UP; negative authorization test quan trọng như happy path.
- Giữ diff nhỏ, không format/refactor file ngoài phạm vi task.
