# Một stack CI, bốn container

## Giới hạn RAM — 2026-10-28

Jenkins2.5GiB (RAM+swap3GiB), SonarQube3GiB (4GiB), dind4GiB (5GiB),
Sonar PostgreSQL512MiB (768MiB) được khai báo trong Compose. Trên installation
hiện hữu, áp dụng bằng `docker update` chỉ sau khi xác nhận queue/build idle;
không cần recreate/restart container hoặc thay volume. Không hạ quality gate.
WSL còn giữ kernel/file cache nên VmmemWSL lớn hơn tổng `docker stats` là bình thường.
Thu hồi clean cache bằng `sync` rồi `drop_caches` không xóa dữ liệu. Cấu hình
autoMemoryReclaim/trần RAM trong `.wslconfig` chỉ có hiệu lực ở lần WSL khởi động
tiếp theo; không shutdown WSL khi Jenkins/CLIProxyAPI/BảoTin cần tiếp tục chạy.

`compose.yml`: Jenkins + dind + SonarQube + PostgreSQL riêng của Sonar.
Đây là tuyến **adopt installation hiện hữu**, không phải fresh install. Không dùng
Compose cũ ở `jenkins/` hoặc `sonarqube/` đồng thời với stack này.

## Checkpoint đã nghiệm thu — 2026-10-19

Bốn container thuộc project `baotin-ci` healthy; Sonar9001 đã được operator mở
thành công từ mạng di động bên ngoài. Jenkins config/credential/key hashes giữ
nguyên; dind engine ID/cache và Sonar project/gate/history được giữ lại. Rollback
thật về installation cũ rồi re-adopt đã PASS. FullStack#12 sau đó PASS đủ11 child
gates và deploy JAR + web thành công. Không tạo archive dữ liệu theo yêu cầu;
không xóa volumes, nâng version hoặc kết nối SSH/VPS/tunnel. Stopped `.before-ci`
containers không chạy writer; giữ chúng để dùng được rollback đã kiểm chứng.

## Giữ dữ liệu

- Dừng/drain build và Sonar compute queue trước downtime; không abort build để migrate.
- Lưu inspect/env riêng, ACL chỉ operator/SYSTEM/Administrators, ngoài repo.
- Snapshot writable layer Jenkins và dind, giữ plugin và entrypoint đã vá.
  Snapshot là private local image; không push registry vì có thể chứa cấu hình riêng.
- Backup nhất quán tất cả volumes khi bốn container đã dừng. Kiểm tra tar và SHA256.
  `Backup-ExistingVolumes.ps1` cần Python3.11+; tar streaming dùng XZ dictionary256MiB
  để nén JAR trùng lặp, không bỏ file. Helper read-only giới hạn256MiB RAM/no swap;
  compressor nên được dự trù khoảng3GB RAM. Kiểm tra lại SHA256 toàn bộ tar sau giải nén và
  dừng/xóa riêng partial archive nếu chạm disk reserve1,5GiB.
  Helper bắt buộc `--log-driver none`: tar nhị phân không được nhân bản vào JSON
  log của Docker. Xóa container/log không đồng nghĩa VHD tự co lại trên host.
- Manifest chỉ dùng đúng volume hiện hữu, kể cả volume anonymous `/var/lib/docker`.
  Tất cả volumes/networks là `external`; không chạy `down -v` hoặc volume prune.
- Các container gốc được giữ dừng, đổi tên `.before-ci`, tách network để tránh trùng DNS.
  Không chạy hai writer trên cùng volume. Chưa xóa container gốc trước khi nghiệm thu.
- Không nâng version Jenkins/Sonar/PostgreSQL trong migration.

### Không tạo archive theo yêu cầu operator

Người dùng đã duyệt bỏ archive trong đợt triển khai này. Dùng `-Apply -SkipVolumeBackup` để
chuyển installation hiện hữu mà không tạo thêm archive. Các kiểm tra exact volumes,
writer đã dừng và giữ container gốc vẫn bắt buộc. Cờ này không xóa volume, không
tạo database rỗng, không tự nâng version và không bỏ health/readiness checks.
Snapshot image đã có từ trước được tái sử dụng; không cần tạo snapshot mới.

Private `manifest.env` theo `.env.example`; bốn env files dùng `format: raw`,
không đổi `$`, quote hoặc backslash trong password cũ. Không đưa file này vào Git.

```powershell
# Preflight với private manifest bên ngoài repo; không tạo/chuyển container.
.\Switch-ExistingStack.ps1 -StateDirectory M:\PRIVATE\ci-stack
# Đợt hiện tại: operator đã chọn rõ không tạo archive.
.\Switch-ExistingStack.ps1 -StateDirectory M:\PRIVATE\ci-stack -Apply -SkipVolumeBackup
docker compose --env-file M:\PRIVATE\ci-stack\manifest.env -f .\compose.yml ps
```

Operator muốn archive phải chạy/kiểm chứng backup trước, rồi dùng `-Apply` không
có `-SkipVolumeBackup`; không chạy cả hai nhánh adoption liên tiếp.
Restart installation đã adopt bằng `docker compose ... up -d --pull never --wait`,
không gọi lại script adoption lên các container đã được thay thế. Trên máy này,
private manifest nằm ở `M:\BaoTin-ci-state\20261016b\manifest.env`. Khi chuyển máy,
chuẩn bị lại images/external volumes/private paths; không thay anonymous dind volume
bằng một volume mới/rỗng. Manifest/snapshots riêng không được push Git/registry.

## Truy cập

Sonar host **9001 → container9000**, Jenkins host **8081 → container8080**
(`JENKINS_HOST_PORT`, mặc định `8081`), agent vẫn `50000`.
Pipeline giữ `http://sonarqube:9000`; không đổi credential hoặc source pin.
Sonar DB/dind không publish port. Anonymous project/gate access phải trả401.

`0.0.0.0:9001` nhận kết nối IPv4 từ router đã forward. Docker binding không chứng minh
Internet thông suốt: kiểm tra từ mạng ngoài bằng `http://PUBLIC_IP:9001`.
HTTP không mã hóa password/token. Dùng HTTPS reverse proxy/Cloudflare Access trước
khi đăng nhập qua Internet; Sonar9.9.8 cần kế hoạch nâng cấp riêng có backup/test.
Không đổi router/DNS/tunnel trong migration này.

Operator chạy script Sonar trên host cần truyền `-SonarUrl http://127.0.0.1:9001`;
script CI vẫn dùng hostname nội bộ. Guide domain:
[`../docs/CLOUDFLARE_TUNNEL.vi.md`](../docs/CLOUDFLARE_TUNNEL.vi.md).

## Rollback runtime

```powershell
.\Switch-ExistingStack.ps1 -StateDirectory M:\PRIVATE\ci-stack -Apply -Rollback
```

Rollback dừng/tách network candidate trước, khôi phục tên/network container gốc rồi
khởi động theo thứ tự DB/dind/Sonar/Jenkins. Cùng image/schema/volumes, không down-migrate.
Port Sonar trở về loopback9000. Archive là recovery riêng nếu volume hỏng, không tự
overwrite dữ liệu mới bằng backup. Xác minh lại jobs/history/pins/credentials,
Sonar projects/gates/users, dind images/cache và health sau mỗi chuyển đổi.
