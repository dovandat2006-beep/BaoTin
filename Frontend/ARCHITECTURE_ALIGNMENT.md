# BaoTin Frontend - Backend Alignment

## Phạm vi phiên này

Scan/history gọi Gateway API; khi `EXPO_PUBLIC_API_URL` được đặt, Auth, Notification,
URL Watchlist và Report/Evidence/Moderation dùng chung client xác thực qua Gateway.
Report nhận input từ kết quả scan và xác minh nguồn scan tại Threat-Service khi có liên kết.
Native refresh token dùng SecureStore; web dùng HttpOnly cookie và CSRF. Search/analytics
còn adapter demo. SSO, quên mật khẩu, FCM và i18n nằm trong `TODO.md`.
Dark mode là một phần của design system hiện tại.

## Mapping chức năng

| Frontend | Backend owner | Contract / event |
|---|---|---|
| Login bằng identifier + password | Auth Service | `POST /api/v1/auth/web/login` |
| Register username + email/phone | Auth Service | `POST /api/v1/auth/register` |
| Refresh/CSRF/logout web | Auth Service | `POST /api/v1/auth/web/refresh`, `/logout` |
| MFA challenge | Auth Service | `POST /api/v1/auth/web/mfa/challenges` |
| Google/Facebook SSO | Auth Service | `/api/v1/auth/oauth2/{provider}` + PKCE |
| Kiểm tra 6 loại đối tượng | Threat Service | `POST /api/v1/scans` |
| Snapshot lịch sử | Threat Service | `GET /api/v1/scans/history` |
| Xóa lịch sử | Threat Service | `DELETE /api/v1/scans/history/{id}` |
| So sánh uy tín hiện tại | Threat + Reputation aggregate | `ThreatTarget`, `target_reputation`, `score_history` |
| Tra cứu theo type/risk | Search Service | `GET /api/v1/search` |
| Báo cáo của tôi | Report Service | `GET /api/v1/reports/me` |
| Tạo báo cáo | Report Service | `POST /api/v1/reports`, `Idempotency-Key` |
| Evidence mock | Report Service + MinIO | `POST /api/v1/reports/{id}/evidence` |
| Watchlist | Notification Service | `/api/v1/watchlist/**` |
| In-app notifications | Notification Service | `/api/v1/notifications/**` |
| Risk-change alert | Reputation + Kafka + Notification | `reputation.score.changed` |
| Report status alert | Report + Kafka + Notification | `report.reviewed` |

## Invariants đã phản ánh trong UI

- Guest có thể kiểm tra URL/SMS; history, watchlist và report nhạy cảm chuyển tới login/register.
- Login nhận một trường `identifier` cho email, số điện thoại hoặc username.
- Register yêu cầu username và tối thiểu email hoặc số điện thoại; OAuth UI không tự map account ở frontend.
- `SMS` là scan tạm thời, không tự tạo `ThreatTarget`.
- URL dùng report/reputation, rule, AI và campaign signals.
- Phone/bank/email/social không bắt buộc AI.
- Risk level đúng ngưỡng: `LOW 0-24`, `MEDIUM 25-49`, `HIGH 50-74`, `CRITICAL 75-100`.
- History hiển thị score tại thời điểm quét và score hiện tại riêng biệt.
- AI không tự đổi report status; moderator feedback được hiển thị riêng.
- Report state hiện có: `SUBMITTED`, `PENDING_REVIEW`, `NEED_MORE_INFO`, `VERIFIED`, `REJECTED`.
- Evidence giới hạn 5 file, JPG/PNG/WEBP/PDF, 10 MB/file trong UX copy.
- Dữ liệu nhạy cảm dùng masked display trong mock list.
- API errors dùng `ProblemDetails`; pagination dùng `page/size/totalItems/totalPages`.
- Tạo report gửi `Idempotency-Key` trong typed API adapter.

## Chuyển từ mock sang backend

Đã nối:

1. `src/lib/apiConfig.ts` đọc `EXPO_PUBLIC_API_URL`; `apiEnabled=false` giữ nguyên mock/E2E.
2. `createAuthApi` dùng cookie refresh HttpOnly + CSRF, access token chỉ trong bộ nhớ.
3. `createBaoTinApi` gọi notification/preferences/watchlist qua typed adapter; 401 được refresh
   một lần rồi mới báo lỗi.
4. UI notification/watchlist lấy dữ liệu từ API, có loading/error/retry và optimistic read.
5. `ApiProblem.problem` được dùng để hiển thị lỗi; không tính lại risk ở frontend.

Còn lại:

1. Thay seed arrays của scan/history/report/search/admin bằng query state/cache.
2. Map `ApiProblem.problem.code` sang message theo từng field và giữ `correlationId` cho hỗ trợ.
3. Nối FCM/service worker và device token native trước khi tuyên bố push đa nền tảng.

Frontend không gọi `/internal/v1/**`; mọi public request đi qua API Gateway.

