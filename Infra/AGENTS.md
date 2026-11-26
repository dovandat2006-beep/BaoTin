# AGENTS.md — Infra

## Phạm vi
- Docker Compose, PostgreSQL provisioning, Jenkins deployment và observability.
- Ranh giới: chỉ recreate Jenkins hiện hữu theo migration `ci-stack/` đã được người dùng duyệt, giữ nguyên volumes/dữ liệu; archive mặc định bắt buộc, chỉ bỏ qua khi operator chọn rõ `-SkipVolumeBackup` theo yêu cầu rõ ràng của người dùng. Không publish DB/Redis/internal services; image pin digest và rollback có health check.

## Cách làm việc
- Đọc `../AGENTS.md`, `README.md` và `TODO.md` (nếu có) trước khi sửa.
- Chỉ nhận dữ liệu qua contract; không đọc/ghi schema của service khác.
- Không thêm endpoint nghiệp vụ placeholder trả dữ liệu giả. Việc chưa làm phải nằm trong TODO.
- Không commit secret. Dùng biến môi trường/file mount và cập nhật `.env.example` bằng placeholder.
- Mọi thay đổi public API/event cần sửa `../Contracts` và thêm compatibility/test.

## Kiểm chứng
- Chạy lệnh kiểm tra được ghi trong README của repo phù hợp với thay đổi.
- Với backend: health/liveness/readiness phải UP; negative authorization test quan trọng như happy path.
- Giữ diff nhỏ, không format/refactor file ngoài phạm vi task.
