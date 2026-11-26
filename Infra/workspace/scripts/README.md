# BảoTin Workspace Scripts

Các script trong thư mục này hỗ trợ tạo và đồng bộ boilerplate CI/CD, Docker và repository cho workspace nhiều repo.

## Nguyên tắc sử dụng

- Chạy từ workspace `M:\BaoTin` và đọc nội dung script trước khi thực thi.
- Ưu tiên dry-run/kiểm tra output; không truyền secret qua tham số hoặc commit output chứa secret.
- Jenkins controller và Docker-in-Docker hiện hữu không được recreate bởi các script dự án.
- Sau khi sinh file, chạy build/test của từng repo bị ảnh hưởng và kiểm tra diff trước deploy.

Các script hiện hữu có thể phản ánh scaffold cũ; TODO/domain README và AGENTS của từng repo là nguồn hướng dẫn gần nhất.

## Auth/Gateway CI/CD

`generate-auth-gateway-cicd.ps1` mặc định dùng namespace GitHub/GHCR **vietluccuu**,
theo owner đã xác nhận. Hai pipeline hiện tại publish image tương ứng
`ghcr.io/vietluccuu/auth-service` và `ghcr.io/vietluccuu/api-gateway`; không đổi tên
service, credential ID, test/scanner gate hoặc cơ chế digest release.

```powershell
.\scripts\generate-auth-gateway-cicd.ps1 -DryRun
.\scripts\Test-AuthGatewayCicd.ps1
```

Regression kiểm tra default owner, template/output đồng nhất, dry-run không sửa file,
owner sai bị từ chối và generation idempotent trong thư mục tạm. Generator không sửa
SCM/job trên Jenkins, không tạo Git repo hay push. URL private repo và branch main phải
được xác nhận/cấu hình ở job **trước khi tải Jenkinsfile**; kiểm tra main bên trong
Jenkinsfile không thay thế được việc bảo vệ nguồn pipeline. Legacy generator/config
cho scaffold khác vẫn là tuyến riêng; không chạy apply-config để cập nhật Auth/Gateway.
