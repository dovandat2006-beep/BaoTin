# Import dashboard BảoTin — không cần chia sẻ mật khẩu

## 1. Chỉ import dashboard

1. Tự đăng nhập `https://prometheus.rimiprod.me` bằng tài khoản của bạn.
2. Mở **Dashboards → New → Import** (hoặc **Import dashboard**).
3. Upload **BaoTin-Operations-Grafana.json**.
4. Chọn folder BảoTin (hoặc folder bạn muốn).
5. Ở ô **Prometheus**, chọn datasource **BaoTin Prometheus** đang có, rồi **Import**.

Nếu chưa có datasource, tạo loại Prometheus với URL backend
`http://127.0.0.1:9090` cho installation Windows hiện tại, **Save & test** trước.
URL này là địa chỉ Grafana server kết nối tới Prometheus, không phải URL trình duyệt.
Không dùng domain Grafana làm URL datasource.

Dashboard UID `baotin-operations` khác bản cũ `baotin-overview`; import lần đầu
không ghi đè dashboard cũ. Dashboard cho phép chỉnh sửa và refresh30giây.
Import lại cùng UID sẽ hỏi overwrite; chỉ chọn nếu muốn cập nhật dashboard này.
Bạn có thể star dashboard rồi chọn nó làm **Home dashboard** trong Preferences.

## 2. Dashboard có gì?

12 panel thuộc4 nhóm:

- Readiness10 backend và Expo Web.
- Supporting services: SonarQube, Jenkins, MinIO, Grafana HTTP health;
  PostgreSQL/Redis/Kafka TCP reachability.
- HTTPS của website BảoTin và3 trang login quản trị; hạn TLS, HTTP response code.
- Availability theo khoảng thời gian, timeline, thời gian synthetic probe,
  scrape health, firing alerts, RAM/CPU của collector.

`NO DATA` không có nghĩa là healthy. TCP connect chỉ chứng minh cổng có thể kết
nối, không chứng minh truy vấn DB, Redis auth hoặc Kafka produce/consume đúng.
Probe duration không phải API request p95. Collector RAM/CPU không phải tài
nguyên toàn máy/JVM. Không có số liệu giả cho request, business events hoặc JVM.

**Import JSON không tự thêm scrape target vào Prometheus.** Cấu hình hiện đang
chạy có12 probe BảoTin/web. Panel supporting services và các domain quản trị
sẽ có NO DATA cho đến khi bạn áp dụng phần3. Panel còn lại dùng dữ liệu sẵn có.

## 3. Thêm đầy đủ monitor (tuỳ chọn, bạn tự áp dụng)

Thư mục `prometheus/` chứa:

- `prometheus.yml`:24 scrape target,22 probe (11 app/web,4 HTTPS,7 supporting).
- `blackbox.yml`: HTTP200, JSON top-level UP, Grafana DB health, HTTPS HTML/TLS,
  và TCP connect modules.
- `alerts.yml`:6 rule readiness/supporting/HTTPS/scrape down, TLS expiry và slow probe.
- `alerts.test.yml`: fixture kiểm tra delay, recovery và threshold của6 rule.

Các port dành cho tuyến Windows hiện tại: Gateway8090, web8091,
backend8181..8189, PostgreSQL15432, Redis16379, Kafka19092, MinIO11900,
Sonar9001, Jenkins8081, Grafana3000, Prometheus9090, Blackbox9115.
Không dùng nguyên port/loopback này cho một host Docker/Kubernetes khác.

Trước khi áp dụng, backup4 file hiện có và kiểm tra:

```powershell
# Chạy từ thư mục đã giải nén gói này.
$promtool='L:/BaoTin-monitoring/bin/prometheus-3.16.0.windows-amd64/promtool.exe'
& $promtool check config ./prometheus/prometheus.yml
& $promtool test rules ./prometheus/alerts.test.yml
```

Để áp dụng trên installation hiện hữu: copy `prometheus.yml`, `blackbox.yml`,
`alerts.yml` vào `M:/BaoTin/Infra/monitoring/` sau khi backup, rồi restart
**chỉ monitoring stack** bằng Stop/Start ownership guard theo runbook
`M:/BaoTin/Infra/monitoring/README.vi.md`. Không restart Jenkins/Sonar/backend.
Prometheus hiện không bật HTTP lifecycle reload; không giả định POST/-/reload
sẽ nạp cấu hình. Chờ ít nhất60giây sau restart để có đủ scrape đầu tiên.

Kiểm tra Prometheus localhost9090 `/targets`:24 target; query `probe_success`
có22 series. Target scrape UP có thể chứa probe_success0 nếu dịch vụ thật down.
Không sửa probe để biến lỗi thật thành xanh. Alert rules ở Prometheus tự đánh
giá; gói này không cấu hình gửi email/Telegram/Alertmanager.

Rollback: restore4 file backup, restart đúng monitoring stack bằng ownership
guard; không xóa TSDB/Grafana DB. Dashboard import là file độc lập; có thể xóa
riêng dashboard UID `baotin-operations` trong Grafana nếu muốn bỏ nó.

## Quyền riêng tư và kết quả kiểm tra

Gói này không có mật khẩu, token, cookie, DB dump hoặc `.env`; không cần gửi
mật khẩu cho người tạo file. Không đổi password/authentication và không tự áp
dụng vào runtime. Bạn tự chọn datasource, folder, quyền dashboard trong Grafana.
Xem `VERIFICATION.txt` và `SHA256SUMS.txt` để kiểm tra gói.

Sau giải nén có thể kiểm tra file (không đăng nhập, không thay runtime):

```powershell
pwsh -NoProfile -File ./Test-Package.ps1 `
  -PromtoolPath L:/BaoTin-monitoring/bin/prometheus-3.16.0.windows-amd64/promtool.exe
```

Tuỳ chọn `PromtoolPath` kiểm tra thêm cấu hình/rule; bỏ tuỳ chọn này nếu chỉ
muốn kiểm tra checksum và dashboard JSON. Bộ22 probe đã được thử riêng bằng
Blackbox trên port tạm, tất cả22 thành công tại thời điểm tạo gói; tiến trình
kiểm thử đã dừng, không thay tiến trình monitoring thật. Việc import và áp dụng
cấu hình vẫn do bạn thực hiện sau khi đăng nhập.
