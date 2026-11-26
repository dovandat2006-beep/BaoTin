# BảoTin: domain, Cloudflare Tunnel và Tailscale

Hướng dẫn thao tác sau khi release/health đã PASS. Tài liệu không tự tạo account,
đổi DNS, cài connector hoặc mở route. Domain đã chọn: `rimiprod.me`; website/API
cùng origin `https://baotin.rimiprod.me`, API giữ nguyên `/api/v1/...`.

## 0. Nghiệm thu triển khai — 2026-10-24

- Đã đọc session bàn giao và đồng bộ14 repo của nhóm bằng fetch/fast-forward;
  `Contracts` nhận commit mới. Phần sửa agent dở dang được hoàn tất, test và push
  vào Infra `9381946`; không ghi đè source chưa commit ở repo ngoài nhóm.
- Jenkins **FullStack#24 SUCCESS**, cả11 child jobs SUCCESS trước promotion.
  Kiểm tra sau khi job kết thúc:11 PID/start-time khớp, non-elevated, chỉ loopback,
  readiness UP và hash JAR/web khớp artifact của đúng build.
- Public HTTPS: `/`200, `/healthz`200, `/api/v1/auth/me`401 Problem Details kể cả
  invalid JWT/forged role headers; CORS origin lạ403, exact origin hợp lệ200;
  `/internal/v1/inference` và `/actuator/env`404 JSON. Response có Cloudflare edge
  headers; không dùng bỏ qua xác minh TLS. Đây không thay thế kiểm thử đăng nhập/
  consent OAuth trên domain mới hoặc tích hợp nghiệp vụ frontend.
- Model Inference từng DOWN vì artifact ONNX chỉ cho Administrators đọc. Đã cấp
  user runtime quyền đọc (không quyền ghi), checksum18 file không đổi; cả hai model
  qua native load/parity/warm-up/readiness dưới user thường. Không chạy service
  elevated hoặc dùng model giả để làm readiness xanh.
- Tunnel/service/DNS hiện hữu được giữ nguyên; config hash không đổi, Moodle200.
  Không tạo tunnel/connector thứ hai, không restart Jenkins hay xóa volumes.
- Máy vẫn dùng startup app thủ công như đã chọn. Sau reboot, kiểm tra agent và
  metadata PID stale theo `../direct/MANUAL_START.md` trước khi chạy release mới;
  connector Automatic không tự khởi động các JAR/web.

### Cấu hình tunnel hiện hữu — checkpoint 2026-10-23

- Zone `rimiprod.me` đã **Active**; authoritative NS là `ivy.ns.cloudflare.com` /
  `tom.ns.cloudflare.com` (delegation tại registrar đã đổi).
- Connector chạy bằng Windows service `Cloudflared` (Automatic), binary
  `C:\Users\ngoan\AppData\Local\Programs\cloudflared\cloudflared.exe`, config
  `C:\Users\ngoan\.cloudflared\config.yml`, tunnel locally-managed
  `moodle-rimiprod` = `34db1b8a-3b88-426a-92d1-3f41c1bec1d9`.
- Tunnel này **dùng chung** với `moodle.rimiprod.me` → `http://localhost:8088`;
  thêm hostname mới bằng cách bổ sung ingress rule, không tạo tunnel thứ hai.
- `baotin` đã có proxied CNAME tới `34db1b8a-....cfargotunnel.com` (tạo bằng
  `cloudflared tunnel route dns`). Rule thứ tự: `^/api(/.*)?$` → `127.0.0.1:8090`,
  catch-all → `127.0.0.1:8091`, sau đó moodle, cuối cùng `http_status:404`.
- Lane JAR + Expo web đã chạy qua Jenkins (reviewed pins): `8090`/`8091` listen,
  readiness tất cả dịch vụ `UP`, `https://baotin.rimiprod.me/` trả `200`,
  `/api/v1/auth/me` trả `401 application/problem+json`. `502` chỉ còn nghĩa là
  origin down (deploy chưa chạy hoặc dịch vụ chết), không phải lỗi DNS/tunnel.
- Sửa `config.yml` phải restart service (đang là service, không phải process tay):
  `Restart-Service Cloudflared -Force`. Sau restart, connector cũ có thể còn
  đăng ký vài phút và gây timeout xen kẽ; kiểm tra
  `cloudflared tunnel info 34db1b8a-3b88-426a-92d1-3f41c1bec1d9` phải thấy đúng
  **một** connector trước khi nghiệm thu.
- `moodle.rimiprod.me` có lúc trả `500` do chính origin Moodle (log Apache ghi
  `GET / 500`); chưa xử lý trong phạm vi tài liệu này.

## 1. Chọn topology

**Khuyến nghị: connector trên chính máy chạy JAR + web.** Không cần Tailscale trong
đường public; vẫn dùng Tailscale riêng để quản trị Jenkins. Không cần port-forward router.

```text
Browser HTTPS -> Cloudflare -> cloudflared trên máy ứng dụng
  baotin.rimiprod.me /api/... -> http://127.0.0.1:8090 (Gateway)
  baotin.rimiprod.me còn lại  -> http://127.0.0.1:8091 (Expo static web)
```

**Connector trên máy khác, nối qua Tailscale:** chỉ dùng khi máy đó thuộc tailnet,
online và được phép tới đúng origin ports. Không trỏ DNS public tới IP100.x hoặc
hostname `.ts.net`; DNS vẫn trỏ Cloudflare Tunnel. Tailscale là đoạn private giữa
connector và máy ứng dụng, không phải nơi đăng ký public domain.

## 2. Tạo account Cloudflare và đưa domain vào zone

1. Đăng ký tại <https://dash.cloudflare.com/sign-up>, xác minh email, bật2FA account.
2. Add a domain/site `rimiprod.me`, chọn plan Free phù hợp. Không chọn paid add-on
   nếu chưa cần; kiểm tra màn hình billing trước khi xác nhận.
3. Review/import DNS hiện hữu. Giữ MX/SPF/DKIM/DMARC và record ứng dụng khác.
4. Cloudflare cấp **hai nameserver thật**. Copy nguyên văn; không dùng ví dụ tự đặt.
5. Tại registrar đang giữ domain, đổi authoritative nameservers sang hai giá trị đó.
   Nếu có DS/DNSSEC cũ, xử lý DS theo hướng dẫn migration trước để tránh SERVFAIL;
   chỉ bật DNSSEC mới sau khi zone Cloudflare Active và thêm đúng DS mới ở registrar.
6. Chờ zone báo Active. Kiểm tra:

   ```powershell
   Resolve-DnsName rimiprod.me -Type NS
   ```

   Kết quả phải là nameserver Cloudflare được cấp cho zone. Không xóa toàn bộ DNS.

## 3. Đăng ký named Cloudflare Tunnel

1. Vào Cloudflare One/Zero Trust dashboard: <https://one.dash.cloudflare.com>.
2. Mở **Networks -> Connectors -> Cloudflare Tunnels** (UI khác có thể gọi là
   Networks -> Tunnels), chọn **Create a tunnel**, loại **cloudflared**.
3. Đặt tên, ví dụ `baotin-local`. Đây là named/managed tunnel, không phải Quick Tunnel.
4. Chọn OS/architecture của connector. Giữ tunnel token riêng; không gửi vào chat,
   Git, Jenkins console hay file `.env.example`. Token cho phép chạy connector.

## 4. Connector Windows, dùng ổ M:

1. Tải binary Windows đúng architecture từ release chính thức:
   <https://github.com/cloudflare/cloudflared/releases>. Kiểm tra SHA256 theo release
   đã chọn. Đặt executable, ví dụ `M:\Tools\cloudflared\cloudflared.exe`.
2. Tạo file UTF-8 plain-text chỉ chứa tunnel token, ví dụ
   `M:\BaoTin-secrets\cloudflare\baotin.token` (**ngoài** `M:\BaoTin`). Giữ ACL chỉ
   account chạy connector, SYSTEM và Administrators. Không đặt token trong argv/history.
3. Kiểm tra version/help; `--token-file` cho managed tunnel cần cloudflared2025.4.0+:

   ```powershell
   & 'M:\Tools\cloudflared\cloudflared.exe' --version
   & 'M:\Tools\cloudflared\cloudflared.exe' tunnel run --help
   & 'M:\Tools\cloudflared\cloudflared.exe' tunnel run --token-file 'M:\BaoTin-secrets\cloudflare\baotin.token'
   ```

4. Giữ terminal chạy trong lúc thử. Dashboard phải báo connector Healthy/Connected.
   Máy ngủ/tắt hoặc process dừng thì tunnel mất kết nối. Chưa tự cấu hình autostart.
5. Nếu firewall chặn outbound: kiểm tra Cloudflare Tunnel connectivity, UDP7844 cho
   QUIC hoặc TCP7844 cho HTTP/2; DNS và HTTPS control-plane/update cũng cần hoạt động.
   Không mở inbound8090/8091/9000/8080/5432/6379 trên router chỉ để dùng Tunnel.

Sau khi manual test ổn mới cân nhắc service/autostart riêng. Nếu nghi token lộ,
rotate/revoke tại dashboard rồi cập nhật file và restart đúng connector, không Jenkins.

## 5. Route public hostname về máy này

Đầu tiên khởi động primary JAR + exported web đúng runbook `../direct/MANUAL_START.md`.
Kiểm tra local; điều chỉnh port theo external config thật, không mặc định rằng đã chạy:

```powershell
Invoke-RestMethod http://127.0.0.1:8091/healthz -NoProxy
Invoke-RestMethod http://127.0.0.1:8090/actuator/health/readiness -NoProxy
```

Trong tunnel -> Published application routes/Public Hostnames, thêm **cùng hostname**
`baotin.rimiprod.me` theo thứ tự:

| Priority | Hostname | Path regex | Service | Origin |
|---|---|---|---|---|
| 1 | baotin.rimiprod.me | `^/api(/.*)?$` | HTTP | `127.0.0.1:8090` |
| 2 | baotin.rimiprod.me | để trống/catch-all | HTTP | `127.0.0.1:8091` |

Giữ nguyên `/api`, không rewrite/strip prefix. Route API đứng trước catch-all;
Node static web8091 **không proxy API**. Nếu `/api/...` trả HTML200, kiểm tra thứ tự
route thay vì coi đó là API thành công. Không route `/internal`, `/actuator`, JWKS
quản trị, PostgreSQL, Redis, Sonar9000, Jenkins8080 hay CLIProxyAPI lên hostname này.

Dashboard thường tự tạo DNS proxied CNAME `baotin` tới `<TUNNEL_UUID>.cfargotunnel.com`.
Nếu record `baotin` đã tồn tại, review/thay đúng record đó; không đụng record khác.
Không thêm A record tới100.x, private LAN hoặc IP WAN của máy nhà cho topology này.

Docker alternative: frontend8095 đã proxy `/api`, nhưng config Nginx hiện ghi lại
X-Forwarded-For thành peer address. Chỉ trỏ catch-all tới8095 sau khi đã review/test
chuỗi proxy/client-IP thực tế; chưa chứng nhận rate-limit theo visitor qua cấu hình đó.
Topology JAR split-route phía trên tránh phụ thuộc proxy frontend này.

## 6. Route qua máy trong Tailscale

Giả sử **A** chạy ứng dụng, **B** chạy cloudflared. Không phải B tự gọi được127.0.0.1
của A: localhost trên B là B. Dùng Tailscale Serve HTTPS trên A cho origin private:

1. Cả A/B đăng nhập tailnet. Trên B kiểm tra `tailscale ping APP_NODE` và hostname
   `APP_NODE.TAILNET.ts.net` thật từ trạng thái Tailscale; không copy hostname cũ trong chat.
2. Trên A đọc `tailscale serve status` trước. Giữ routes443/8443 hiện hữu. Chỉ dùng
   9443/9444 nếu đang trống và được duyệt; bật HTTPS/Serve trong admin console nếu yêu cầu.
3. Tạo hai route riêng trên A:

   ```powershell
   tailscale serve --bg --https=9443 http://127.0.0.1:8090
   tailscale serve --bg --https=9444 http://127.0.0.1:8091
   tailscale serve status
   ```

4. Tailnet ACL/grants chỉ cho B (hoặc tag connector được quản trị) tới A TCP9443/9444.
   Thêm rule vào policy hiện hữu; không thay bằng allow-all và không mở toàn tailnet.
5. Trên B gọi health qua hostname HTTPS thật để xác nhận DNS/ACL/TLS. Giữ kiểm tra
   certificate; không dùng `noTLSVerify`, `-k` hoặc `SkipCertificateCheck`.
6. Trên Cloudflare, thay hai origins tương ứng bằng **HTTPS**:
   `APP_NODE.TAILNET.ts.net:9443` cho API, `APP_NODE.TAILNET.ts.net:9444` cho web.
   Giữ hostname public/path priority như bước5. Không bật Tailscale Funnel.

A, B, Tailscale và connector đều phải online; nếu B là laptop hay ngủ thì không phù hợp
làm connector liên tục. Không cần đưa Tailscale admin key vào Cloudflare.

## 7. Origin/CORS, IP và OAuth trước khi public

- External config: `AUTH_ALLOWED_ORIGINS=https://baotin.rimiprod.me` và
  `GATEWAY_ALLOWED_ORIGINS` cùng giá trị; không wildcard, không trailing slash.
- Web gọi relative `/api/v1/...`, refresh cookie Secure/HttpOnly, `credentials: include`
  và CSRF theo contract. Không mở cookie Domain `.rimiprod.me` hoặc tắt CSRF để sửa CORS.
- Update Google/Facebook **registered callback URIs** cùng external Auth config:
  `https://baotin.rimiprod.me/api/v1/auth/oauth2/callback/google` và `/facebook`.
  `OAUTH_WEB_RETURN_URIS` phải là HTTPS callback route mà frontend đã implement;
  không tự đặt route chưa có. Native redirect giữ riêng; không đưa client secret vào Expo.
- Source frontend hiện có mock/backend-integration TODO: DNS/HTTPS hoạt động không
  biến mock UI thành auth/scam-detection thật. Kết nối adapter theo contract riêng.
- Gateway chỉ trust actual proxy peer /32 hoặc /128. Không trust toàn Internet,
  100.64.0.0/10, mọi RFC1918, role/user headers hay CF-* như bằng chứng quyền.
- Với Tailscale middle hop cần xác minh XFF có giữ visitor IP hay bị thay bằng B.
  Nếu cần, chỉ thêm B IP chính xác vào trusted proxy chain sau khi review; nếu Serve
  không giữ chain thì cần ingress adapter được test, không đoán hoặc trust subnet rộng.
- Test hai client và spoofed XFF/CF-Connecting-IP/role headers: bucket IP phải đúng,
  JWT/ownership vẫn enforce, forged ADMIN phải403. Chưa có live test thì chưa chứng nhận IP.
- Bypass cache cho `/api/*`; không dùng Cache Everything cho auth/refresh, giữ no-store.
  HTTPS public bắt buộc; không hạ SSL về Flexible hoặc tắt origin TLS validation.

## 8. Acceptance và rollback

```powershell
Resolve-DnsName baotin.rimiprod.me
curl.exe -i https://baotin.rimiprod.me/healthz
curl.exe -i https://baotin.rimiprod.me/api/v1/auth/me
curl.exe -i https://baotin.rimiprod.me/internal/v1/inference
curl.exe -i https://baotin.rimiprod.me/actuator/env
```

Health web phảiUP; `/auth/me` chưa login phải401 JSON, không HTML200. Kiểm tra
internal/actuator không lộ thông tin (static SPA fallback200 không phải proof403/404;
kiểm tra content). Sau đó thử Web login, cookie/CSRF, refresh/logout, OAuth thật và
negative authorization. Không paste JWT, OAuth code, cookie hoặc token vào chat/log.
Origin502/503: kiểm tra local app/port trước;1033: connector/tunnel connectivity;
TLS error private leg: kiểm hostname/certificate; lỗi CORS: kiểm exact Origin/config.

Rollback: dừng đúng cloudflared process hoặc xóa đúng hai published routes vừa thêm.
Nếu thêm Serve9443/9444 trong bước6, gỡ đúng hai port, không `serve reset`:

```powershell
tailscale serve --https=9443 off
tailscale serve --https=9444 off
```

Giữ routes443/8443, Jenkins, CLIProxyAPI, dependencies, DB volumes và dữ liệu. DNS
rollback chỉ record `baotin` đã thay; revoke connector token nếu decommission.

Nguồn chính thức (đã kiểm tra HTTP200 khi viết):
- <https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/>
- <https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/cloudflared-parameters/run-parameters/>
- <https://tailscale.com/kb/1242/tailscale-serve>
