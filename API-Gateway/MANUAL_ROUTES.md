# Services tự kết nối; endpoint duyệt thủ công

Gateway đã đăng ký địa chỉ Auth và tám services8181..8189 như port map của Infra.
Không có service discovery/publication tự do. Text/URL Scan v1 và lịch sử đã có allowlist mặc định trong application.properties; các scaffold khác không tự được mở;
riêng Notification-Service đã có block route notification/watchlist đã duyệt trong
`manual-routes.properties.example`, vẫn phải copy sang file ngoài Git và redeploy Gateway.
Khi service giữ cùng địa chỉ, deploy/restart không cần sửa Gateway: request mới tự kết nối;
service đang dừng trả503, khi chạy lại route phục vụ tiếp.

## Thêm endpoint khi service sẵn sàng

1. Team hoàn thành endpoint và authorization/ownership tại service, cập nhật OpenAPI trong Contracts.
2. Copy `manual-routes.properties.example` ra file ngoài Git; khai báo endpoint đã duyệt.
3. Điền `GATEWAY_ROUTES_FILE` trong `jar.env`, chạy test và duyệt source/config trước release.
4. Redeploy Gateway khi thay đổi danh sách endpoint/chính sách. Deploy riêng service sau đó không cần sửa Gateway.

Notification/watchlist endpoints in the example are already matched to
`Contracts/openapi/notification-service.yaml`; do not add wildcard paths or infer
endpoints from the service. All require JWT, and Notification-Service re-checks ownership.

Target overrides: `THREAT_SERVICE_URI`, `REPORT_SERVICE_URI`, `NOTIFICATION_SERVICE_URI`,
`SEARCH_SERVICE_URI`, `CAMPAIGN_SERVICE_URI`, `ANALYTICS_SERVICE_URI`. Direct JAR dùng
loopback8182/8183/8186/8187/8188/8189; Docker/cloud dùng DNS/IP nội bộ cố định của service.
Inference/Reputation được ghi trong catalog nội bộ nhưng không được khai báo route public.
Auth routes giữ nguyên implementation và cookie/CSRF hiện hữu.

Mỗi endpoint có `id`, `service`, `path`, `methods`, `anonymous` (mặc địnhfalse/JWT required).
Path là literal hoặc `{id}`, không wildcard/regex; namespace phải đúng service.
Không có input URL tùy ý, internal/admin actuator forwarding hay client-role headers.
Endpoint JWT vẫn yêu cầu backend tự authorize; Gateway không thay thế policy nghiệp vụ.
Các endpoint literal được ưu tiên trước parameter patterns. Health/readiness của service
do Jenkins/Infra kiểm tra trước release; catalog không tự khẳng định service đã healthy.

## Internet / Cloudflare

Chọn cùng origin: web `https://baotin.rimiprod.me`, API `/api/v1/...` trên cùng host.
Không cần subdomain API riêng và không có vấn đề cookie khác site trong topology này.
Origin của Web phải nằm trong `AUTH_ALLOWED_ORIGINS` và `GATEWAY_ALLOWED_ORIGINS`; không `*`.
CORS không phải authentication: JWT, Origin/CSRF cho Web cookie, ownership/role vẫn bắt buộc.
Nếu đổi sang `api.baotin.rimiprod.me` thì cập nhật CORS, frontend API base và provider callback
cùng lúc, giữ cookie host-only; không đặt cookie Domain `.rimiprod.me`.

Cloudflare forwarding giữ nguyên `/api` và kết nối Gateway/Web trên loopback. Chỉ trust peer
proxy thật; `CF-*`, role/user/forwarded headers từ client không được chuyển thành quyền.
`X-Forwarded-For` được đọc theo chain từ phải sang trái qua CIDR proxy đã duyệt, không trust
toàn bộ Internet/Cloudflare visitor IP. Không mở DB/Redis/inference/internal endpoints lên tunnel.
Callback Google/Facebook mới phải đăng ký chính xác trên provider console trước khi đổi config;
callback Tailscale đã kiểm chứng không tự bị thay thế bởi lựa chọn domain mới.

Config loader và các test HTTP/Redis/JWT chứng minh behavior qua proxy/origin giả lập.
Chỉ test thật sau DNS/Cloudflare provisioning mới chứng minh Internet/TLS/provider callback.

## Text/URL Scan v1

Default shipped routes: anonymous POST `/api/v1/scans`, `/api/v1/scans/text`, `/api/v1/scans/url`; authenticated GET/DELETE `/api/v1/scans/history` and `/api/v1/scans/history/{id}`. Threat revalidates JWT and history ownership, applies stricter guest quotas and calls inference using its own private credential. No model routes are published.

An external `gateway.catalog.endpoints` list replaces the default list as a whole. The example now includes the five scan routes after the existing notification/watchlist routes; keep both blocks when importing it. Existing external deployments must append these reviewed entries (without duplicate indices/paths) and restart Gateway.

Set `THREAT_SERVICE_URI` to the private Threat origin. For Expo development add the exact Web origin to `GATEWAY_ALLOWED_ORIGINS`; for same-origin production use the existing frontend `/api/` reverse proxy. Never embed `MODEL_SERVICE_TOKEN` in Expo environment variables.
