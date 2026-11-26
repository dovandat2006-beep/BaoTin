# AGENTS.md — scripts

## Phạm vi
- Script sinh/cập nhật CI/CD, Docker và repository boilerplate cho workspace.
- Script là source; khi sửa generator phải cập nhật hoặc kiểm tra các file generated tương ứng.

## Quy tắc
- Đọc `../AGENTS.md` và `README.md` trước khi sửa.
- Script phải idempotent, fail-fast, không in secret và không ghi đè file người dùng ngoài allowlist.
- Không restart/recreate Jenkins hiện hữu, không xóa Docker volume/image và không chạy deploy production mặc định.
- Với thao tác file trên Windows, dùng `-LiteralPath`, kiểm tra target nằm trong workspace trước recursive move/delete.
- Thêm dry-run hoặc kiểm tra output ở thư mục tạm trước khi cập nhật nhiều repo.

## Kiểm chứng
- PowerShell script phải parse được; shell script phải qua `bash -n`.
- So sánh output generator với file hiện hữu và ghi rõ thay đổi có chủ đích.
- Không coi generator thành công nếu file generated làm build/test thất bại.
