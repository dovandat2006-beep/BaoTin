# BảoTin: origin đã chuẩn bị cho Cloudflare Tunnel

Deployment chính: Jenkins → backend JAR + Expo Web trên Windows. Docker images
là phương án thay thế; không cần khởi động lại `baotin-alt`. DNS/tunnel chưa được
tự cấu hình. Frontend hiện vẫn có mock/integration TODO của nhóm; hosting PASS
không biến các chức năng đó thành backend integration đã hoàn thành.

## 1. Origins

| Mục đích | Origin trên máy ứng dụng | Public hostname/path sau này |
|---|---|---|
| API-Gateway | `http://127.0.0.1:8090` | `https://baotin.rimiprod.me/api/...` |
| Expo static web | `http://127.0.0.1:8091` | `https://baotin.rimiprod.me/` |
| SonarQube quản trị | `http://127.0.0.1:9001` | tách khỏi hostname ứng dụng |
| Jenkins quản trị | `http://[::1]:8080` | không route lên hostname ứng dụng |

Auth8181, tám scaffold8182..8189, PostgreSQL15432 và Redis16379 chỉ dùng loopback
hoặc network Docker nội bộ. Không forward các port này trên router. `/internal/**`
và `/actuator/**` không phải routes public. Chỉ map `/api/...` tới Gateway.

`AUTH_ALLOWED_ORIGINS`/Gateway được cấu hình chính xác
`https://baotin.rimiprod.me`, giữ origin test đã duyệt; không dùng `*` hoặc wildcard
subdomain. Native API tiếp tục dùng JWT; web refresh dùng HttpOnly cookie/CSRF.
Không tự đổi key, reset DB, gán ADMIN hoặc chạy migration trong deployment thường.

## 2. Kiểm tra sau deploy/reboot

```powershell
Invoke-RestMethod http://127.0.0.1:8090/actuator/health/readiness -NoProxy
Invoke-RestMethod http://127.0.0.1:8091/healthz -NoProxy
Invoke-WebRequest http://127.0.0.1:8091/ -NoProxy
Invoke-RestMethod http://127.0.0.1:9001/api/system/status -NoProxy
```

Hai health đầu phải `UP`, web phải trả HTML200. Health không thay thế kiểm tra
PID/start-time, quyền process và kết quả terminal SUCCESS của Jenkins.
Ownership/runtime state nằm ngoài Git tại `RUNTIME_ROOT/active.json`.

Startup hiện **thủ công** theo [MANUAL_START.md](MANUAL_START.md): Docker Desktop,
CI/dependencies, deploy node không elevated, rồi FullStack đã pin. Không cài thêm
Task Scheduler/service/autostart. Sau reboot, đừng giả định JAR/web vẫn chạy.

## 3. Map tunnel sau này

Chạy cloudflared trên cùng máy là tuyến đơn giản nhất. Trong Public Hostnames,
giữ thứ tự và **không strip/rewrite `/api`**:

1. Host `baotin.rimiprod.me`, path `^/api(/.*)?$`, HTTP `127.0.0.1:8090`.
2. Cùng host, catch-all path, HTTP `127.0.0.1:8091`.

Web8091 không proxy API. Nếu `/api/...` trả HTML200, sửa thứ tự ingress; đó không
phải API thành công. Giữ HTTPS public và forwarded scheme của Cloudflare; không
đăng nhập web/cookie bằng một public HTTP origin. Không mở thêm DB/internal ports.

Hướng dẫn account, domain/nameserver, token-file, connector Windows và topology qua
Tailscale: [CLOUDFLARE_TUNNEL.vi.md](../docs/CLOUDFLARE_TUNNEL.vi.md).
Không cần router port-forward8090/8091 khi dùng Cloudflare Tunnel.

## 4. OAuth/email khi hostname đã live

Sau khi DNS/tunnel HTTPS hoạt động, đăng ký callback chính xác trong provider console:

- Google: `https://baotin.rimiprod.me/api/v1/auth/oauth2/callback/google`.
- Facebook: `https://baotin.rimiprod.me/api/v1/auth/oauth2/callback/facebook`.

Sau đó đổi callback tương ứng trong config-tree Auth ngoài Git và redeploy; giữ
client ID/secret ngoài source. Callback test hiện hữu không tự thay bằng callback
chưa được provider duyệt. Firebase client project/native redirect và frontend Auth
integration tiếp tục theo TODO/contract; không suy diễn từ web export thành OAuth PASS.
Kiểm tra lại redirect allowlist, HTTPS cookie/CSRF, login/refresh/logout và mail links
trên hostname thật trước khi sử dụng public.

## 5. SSH/VPS vẫn chưa kết nối

[`../vps/README.vi.md`](../vps/README.vi.md) hướng dẫn tạo deploy key, xác minh host
fingerprint/known_hosts và manifest11 immutable images. Jenkins có target `vps`,
nhưng chỉ dùng sau khi operator điền các external paths; lần này target là `jar`.
Không có SSH/VPS connection hoặc Cloudflare/DNS change trong lần deploy này.
