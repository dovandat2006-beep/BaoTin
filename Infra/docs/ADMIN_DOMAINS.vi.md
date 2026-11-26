# Domain quản trị — 2026-11-17

| HTTPS hostname | Origin loopback | Authentication |
|---|---|---|
| `prometheus.rimiprod.me` | `http://127.0.0.1:3000` | Grafana login bắt buộc |
| `sonarqube.rimiprod.me` | `http://127.0.0.1:9001` | SonarQube account hiện hữu |
| `jenkins.rimiprod.me` | `http://127.0.0.1:8081` | Jenkins account hiện hữu |

`prometheus` là hostname của dashboard Grafana tổng hợp. Raw Prometheus9090 và
Blackbox9115 vẫn chỉ nội bộ, không có public ingress. User Grafana `baotin-admin`,
bootstrap secret ngoài Git tại `L:/BaoTin-monitoring/grafana-admin.secret`.
Không in/chia sẻ mật khẩu hoặc đưa token vào URL/command line.

## Cấu hình đã áp dụng

Ba proxied CNAME được thêm bằng `cloudflared tunnel route dns`, trỏ tới named
tunnel hiện hữu `34db1b8a-3b88-426a-92d1-3f41c1bec1d9`. Không tạo connector/tunnel
song song. Config đang dùng `C:/Users/ngoan/.cloudflared/config.yml`; ba rule
được chèn trước catch-all404, giữ nguyên rule BảoTin API/web và Moodle:

```yaml
  - hostname: prometheus.rimiprod.me
    service: http://127.0.0.1:3000
  - hostname: sonarqube.rimiprod.me
    service: http://127.0.0.1:9001
  - hostname: jenkins.rimiprod.me
    service: http://127.0.0.1:8081
```

Windows service Cloudflared đã restart một lần để nạp config; sau đó chỉ thấy
một connector active. Không restart Jenkins/SonarQube/backend/web, không thay
build queue, agent URL/token, quality gates, DB hoặc volume. Monitoring stack
được khởi động lại có ownership guard để nạp Grafana root_url HTTPS và Secure
cookie; vẫn không có cửa sổ plugin bật lên. Jenkins canonical root URL được cập
nhật `https://jenkins.rimiprod.me/`; URL nội bộ của agent/CI giữ nguyên.
Sonar serverBaseURL không đổi; hai credential quản trị local lưu trước đó không
xác thực được, nên không reset password/token hay bypass quyền để sửa setting.

## Kết quả kiểm tra

Qua public Cloudflare HTTPS, TLS verification bật:

- Grafana login200; authenticated dashboard200/9panels; anonymous dashboard401.
- Grafana metrics bị tắt; khách gọi `/metrics` bị redirect về login302,
  không nhận telemetry qua hostname public.
- Sonar login200; anonymous project API401.
- Jenkins login200; anonymous root API403.
- BảoTin web200, anonymous auth/me401; public Actuator404.
- Moodle502 có từ baseline trước thay đổi; ingress và origin không đổi.

Các request public đã được kiểm tra từ máy triển khai qua Cloudflare edge;
chưa phải bằng chứng kiểm tra độc lập từ mạng di động/máy khác.
Native application login đang bảo vệ ba hostname. Cloudflare Access chưa được
tạo; không gọi cơ chế native login là Access hoặc suy diễn có MFA ở edge.

## Rollback

Bản gốc và bản sửa, SHA256/diff/test nằm ngoài Git:
`M:/BaoTin/.changes/admin-domains-20261117/`.
`ROLLBACK.sh` chỉ restore fixture copy để kiểm thử không gây downtime.
Rollback vận hành cần kiểm tra config live chưa bị người khác sửa rồi restore
`config.original.yml` về đúng config Cloudflared và restart đúng service.
DNS mới có thể giữ lại và nhận404 khi rule bị bỏ; không xóa record cũ khác.
Khôi phục Grafana ini từ `grafana.original.ini` và restart monitoring bằng
Stop/Start ownership guard nếu cần. Jenkins URL cũ ở `jenkins-url.original.txt`,
khôi phục qua API quản trị authenticated; không reset hoặc restart controller.
Giữ secret, data/TSDB, logs cần thiết và các bản rollback.
