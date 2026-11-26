# VPS / SSH deployment (chưa cấu hình máy đích)

Jenkins FullStack có lựa chọn `DEPLOY_TARGET=vps`. Mặc định vẫn `jar`;
`DEPLOY_RELEASE=false` không kết nối SSH. Cloudflare/tunnel chưa triển khai.
Script này dùng Docker Compose và đủ 11 image (10 backend + Expo web), không
đụng Jenkins hoặc chạy provisioning/migration bằng runtime credentials.

## 1. Chuẩn bị VPS khi có máy

Ubuntu/Debian hỗ trợ Docker Engine26+ và Compose2.40+; cài `jq`, `curl`, OpenSSH.
Tạo account riêng `baotin_deploy`, không dùng root SSH. Quyền Docker tương đương
quyền quản trị host: chỉ giao cho deploy account và job đã pin/review.
Firewall chỉ mở SSH cho IP người quản trị/agent; DB/Redis và app vẫn private.
Chưa mở website ra Internet trong bước này.

## 2. Tạo SSH key ngoài Git

Trên deployment agent (PowerShell):

```powershell
ssh-keygen -t ed25519 -f C:\Users\USER\.baotin\vps-deploy-key
```

Đưa **public key** vào `~baotin_deploy/.ssh/authorized_keys` của VPS; chmod700
thư mục `.ssh`, chmod600 file. Giữ private key ở agent với ACL chỉ account deploy,
SYSTEM và Administrators. Nếu key có passphrase, dùng cơ chế agent được quản trị;
không ghi passphrase vào repo/env. Chế độ này dùng `BatchMode=yes`.

Lấy fingerprint host trực tiếp từ console nhà cung cấp VPS:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Sau khi so fingerprint qua kênh độc lập, lưu host key vào file `vps-known_hosts`
ngoài repo. `ssh-keyscan` chỉ thu thập, không chứng minh danh tính. Script bắt buộc
`StrictHostKeyChecking=yes`, không có tùy chọn bỏ kiểm tra hoặc root login.

## 3. Dependencies và secrets trên VPS

- Tạo `/opt/baotin`, account deploy có quyền ghi thư mục releases/config.
- Chạy PostgreSQL/Redis theo `../direct/dependencies` với secret files trên VPS;
  chỉ private network. Không sao chép dữ liệu thật hoặc password từ ví dụ.
- Provision DB `baotin`, runtime/migrator/operator riêng, migrate bằng JAR đã duyệt
  và grants đã review. Runtime giữ `baotin_auth`, Flyway disabled.
- Chuẩn bị key/config-tree ngoài source theo `../alternative/README.md`; dùng
  `prepare-secrets` để chuyển ownership Linux, không nhét secret vào image.
- Lưu config public-path/network/origin/IP/port và đủ 11 image keys trong
  `/opt/baotin/runtime.env`. Lấy layout từ `alternative/Initialize-Config.ps1`;
  IP/subnet phải theo network thật của VPS, không copy IP máy phát triển.
- `AUTH_ALLOWED_ORIGINS` phải là HTTPS origin chính xác. Chưa có ingress thì chưa
  chứng nhận Web cookie/OAuth trên Internet; health chỉ dùng loopback8095.

## 4. Immutable images

Build/publish các service bằng Jenkins `BUILD_DOCKER_IMAGES=true` sau các gate.
GHCR credential cần quyền push package; VPS cần pull private packages. Đăng nhập
registry bằng token-file ngoài repo và `docker login --password-stdin` trên VPS.
Không truyền registry token trong SSH argv hoặc lưu vào manifest.
Điền `images.example.json` bằng **11 digest đã scan/review**, lưu ngoài Git.
Placeholder, tag mutable hoặc repo khác `vietluccuu` bị từ chối.

## 5. Cấu hình deployment node

Copy `.env.example` ra ngoài repo, điền host/user/port/root/known_hosts path.
Thêm **đường dẫn, không secret value** vào `jar.env` của deployment node:

```dotenv
VPS_CONFIG_FILE=C:/Users/USER/.baotin/vps.env
VPS_IMAGE_MANIFEST=C:/Users/USER/.baotin/vps-images.json
VPS_SSH_KEY_FILE=C:/Users/USER/.baotin/vps-deploy-key
```

Kiểm tra không kết nối:

```powershell
.\vps\Deploy-SSH.ps1 -ConfigFile EXTERNAL_VPS_ENV -ImageManifest EXTERNAL_DIGEST_MANIFEST -KeyFile EXTERNAL_PRIVATE_KEY -ValidateOnly
```

Sau đó chạy job FullStack đã pin, chọn `DEPLOY_RELEASE=true`,
`DEPLOY_TARGET=vps`, duyệt promotion. Job chạy Sonar/security trước deploy.
Release lane không chạy tests; verify workflow riêng sở hữu tests. Sonar chạy trước
đóng gói và security/SBOM gates vẫn bắt buộc. SSH upload release vào GUID directory; remote kiểm tra DB reader/schema/cipher,
scoped secrets, Compose health và web health. Không tự migrate hoặc đổi key.

## Rollback

Sau một lần deploy thành công, `/opt/baotin/accepted.env` là manifest rollback.
Candidate unhealthy khôi phục compatible accepted images; lần đầu thất bại chỉ
stop candidate. Không `down -v`, prune hoặc down-migrate. Giữ các image và volume.
Reader1 không tương thích schema10/floor2; rollback phải dùng reader2+ phù hợp.
Lưu backup DB và key ring riêng trước migration; deployment không thay thế backup.

Hiện chỉ có runner/template và offline preflight/syntax tests. Chưa có VPS/key thật,
nên không có kết nối hoặc chứng nhận deployment VPS thật.

Regression không kết nối: `Test-SSHPreflight.ps1` kiểm tra9 cấu hình; thêm
`Test-SSHTransport.ps1` chạy toàn bộ nhánh client bằng native SSH/SCP shims
(success/connection failure/upload failure/remote failure), kể cả paths có khoảng trắng.
Shims được xác nhận trước khi gọi; không socket, không Docker/VPS thật.
