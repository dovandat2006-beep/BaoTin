# BảoTin monitoring — Windows primary

## Import thủ công, không chia sẻ mật khẩu

Gói mới: [BaoTin-Monitoring-Import.zip](import/BaoTin-Monitoring-Import.zip).
Chỉ cần dashboard: [JSON import](import/BaoTin-Operations-Grafana.json).
[Hướng dẫn](import/README.vi.md) gồm chọn datasource và cấu hình scrape tuỳ chọn.
Gói chưa được tự áp dụng; không thay mật khẩu hoặc dịch vụ đang chạy.

Prometheus + Blackbox Exporter + Grafana chạy riêng, không restart backend/web,
không đổi Jenkins, tunnel, DB, model hoặc startup của ứng dụng.

## Đang theo dõi

- 10 backend readiness ở Gateway8090 và service8181..8189.
- Expo Web8091 `/healthz` và website `https://baotin.rimiprod.me/`.
- Probe success, thời gian probe, sample-based availability, TLS expiry.
- 5 alert rules: readiness down, website down, collector down, TLS <14 ngày,
  probe chậm >3 giây. Rules hiển thị trong Prometheus; chưa có email/Telegram routing.
- Dashboard provisioned: `http://127.0.0.1:3000/d/baotin-overview`.

Prometheus `127.0.0.1:9090`, Grafana `127.0.0.1:3000`, Blackbox
`127.0.0.1:9115`. Dashboard Grafana được route qua tunnel hiện hữu tại
`https://prometheus.rimiprod.me/`, giữ đăng nhập bắt buộc và cookie Secure.
Hostname này mở Grafana, không mở raw Prometheus9090 hoặc Blackbox9115.
Không đổi exposure Actuator: `/actuator/**` public vẫn404.
Đây là synthetic health monitoring, không phải API request p95/JVM/business metrics.

## Cài đặt và chạy thủ công

PowerShell7, Node, Windows x64. Runtime nằm ngoài Git, volume trống ít nhất3GiB;
`packages.json` pin phiên bản và SHA256 của archive nhà phát hành.

```powershell
# Ví dụ volume có đủ dung lượng; không dùng ổ hệ thống gần đầy.
pwsh -NoProfile -File monitoring/Install-Packages.ps1 -RuntimeRoot L:/BaoTin-monitoring
pwsh -NoProfile -File monitoring/Start-Monitoring.ps1 `
  -RuntimeRoot L:/BaoTin-monitoring `
  -PrometheusHome L:/BaoTin-monitoring/bin/prometheus-3.16.0.windows-amd64 `
  -BlackboxHome L:/BaoTin-monitoring/bin/blackbox_exporter-0.29.1.windows-amd64 `
  -GrafanaHome L:/BaoTin-monitoring/bin/grafana-13.3.1
```

User Grafana `baotin-admin`; mật khẩu bootstrap random ở
`L:/BaoTin-monitoring/grafana-admin.secret`, ACL chỉ user chạy. Không đưa secret
vào Git, command arguments hoặc báo cáo. Sau đổi mật khẩu qua UI, dùng mật khẩu
mới cho kiểm thử authenticated; file bootstrap không tự thay mật khẩu DB hiện hữu.
Domain Jenkins/Sonar và rollback tunnel: [admin domains](../docs/ADMIN_DOMAINS.vi.md).

Launcher dùng **hidden new console**, để plugin con kế thừa console ẩn thay vì
tự mở nhiều cửa sổ. Chỉ bật datasource Prometheus;13 backend plugin không dùng
bị disable. Không đăng ký service, Scheduled Task hoặc autorun.
Giữ nguyên PID/start-time/path guard; không chạy trùng instance.

Prometheus giữ tối đa15ngày/256MiB block data; WAL/head có overhead riêng.
TSDB và Grafana DB nằm trong runtime, không coi là cache kiểm thử.
Sau reboot chạy thủ công; không suy diễn health khi Prometheus đã dừng.

## Kiểm thử

Từ thư mục Infra:

```powershell
L:/BaoTin-monitoring/bin/prometheus-3.16.0.windows-amd64/promtool.exe check config monitoring/prometheus.yml
L:/BaoTin-monitoring/bin/prometheus-3.16.0.windows-amd64/promtool.exe test rules monitoring/alerts.test.yml
$env:BAOTIN_MONITORING_LIVE='1'
node --test monitoring/monitoring.test.cjs
pwsh -NoProfile -File monitoring/Test-LiveMonitoring.ps1 -RuntimeRoot L:/BaoTin-monitoring
```

Live check chờ đủ chu kỳ scrape đầu, xác minh14 scrape target,12 probe,
datasource/dashboard, authentication, public actuator404 và không có cửa sổ console.
Fixture kiểm tra200/DOWN, tổng DOWN/component con UP, JSON sai kiểu/cú pháp,
503 và non-TLS đều thất bại, không dừng service thật. Readiness dùng CEL để kiểm
tra chính xác JSON `body.status == "UP"`, không match chuỗi UP ở component con.
Rule test bao phủ cả5 rule, pending/firing/recovery và ngưỡng TLS/latency.
Live verifier xác minh đúng danh tính và độ mới từng target, chạy cả9 truy vấn
panel qua Grafana datasource proxy; alert panel rỗng là hợp lệ khi không có lỗi.

## Dừng / rollback vận hành

```powershell
pwsh -NoProfile -File monitoring/Stop-Monitoring.ps1 -RuntimeRoot L:/BaoTin-monitoring -WhatIf
pwsh -NoProfile -File monitoring/Stop-Monitoring.ps1 -RuntimeRoot L:/BaoTin-monitoring
```

Chỉ dừng ba tiến trình đã khớp PID/start-time/path và plugin con đã xác minh.
Giữ dashboard, secret, TSDB, Grafana DB và ứng dụng. Khởi động lại bằng lệnh Start
ở trên. Không xóa volumes/data để sửa lỗi. Khi port đã bị chiếm hoặc PID bị tái sử
dụng, kiểm tra ownership trước, không kill theo process name hay số port.
