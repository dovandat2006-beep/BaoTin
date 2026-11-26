# TODO - BaoTin Frontend

Chỉ liệt kê tính năng chưa có trong frontend hiện tại.

2026-10-30: customer-facing architecture explanations were removed/shortened.
Meta's empty callback fragment has a parser regression; retain all other callback
validation. Fresh local CI, matching bundles and Jenkins promotion of this new
increment are still required before claiming it is live.

2026-10-29 source increment: account/profile/recovery/password/MFA/sessions,
ADMIN user management with TOTP step-up, OAuth/explicit linking, notification
deletion/device operations and history delete-all are wired to existing Gateway
APIs. See ACCOUNT_API_INTEGRATION.md; local source tests do not yet establish final
real-provider/platform acceptance. The checked source items below distinguish completed wiring from
provider/platform acceptance and final deployment, which remain open.

SDK58/real-mode update: normal Web/Expo Go runs use real Gateway APIs, not demo
fallback. Guest URL Search is connected to its public read projection; the
ADMIN Analytics adapter calls the live Analytics-Service through Gateway, and
errors are not replaced with metrics. Non-URL Search and Campaign remain backlog. See `SDK58_REAL_API.md`. Explicit demo build fixtures remain only for
UI regression tests.

Android build lane: explicit live-API Hermes export and externally signed native
release AAB/APK commands are documented in `ANDROID_BUILD.md`. Device/emulator
execution is deliberately left to the user for this task; do not infer installed
Android acceptance from Web browser tests or compilation results.

Public acceptance checkpoint 2026-10-27: FullStack27 / Frontend22 passed real-domain
Web owner and MFA moderator flows, refresh/logout, scan/history, watchlist and
report/evidence/notification integration. Privileged fixture roles were removed
and audit retained. Device acceptance, SSO/recovery UI and scaffold screens below
remain separate; see Infra's `docs/api-integration-acceptance-2026-10-27.md`.

## P1 - Đa ngôn ngữ

- [ ] Thiết lập i18n cho `vi-VN` và `en-US`.
- [ ] Chuyển toàn bộ chuỗi UI sang translation keys có kiểu TypeScript.
- [ ] Thêm bộ chọn ngôn ngữ và lưu lựa chọn cục bộ.
- [ ] Mặc định theo locale thiết bị; fallback về tiếng Việt.
- [ ] Kiểm thử độ dài tiếng Anh/Việt tại 320, 375, 414, 768 và 1440 px.

## SDK58 patch alignment — 2026-10-29

- [ ] Promote RN0.88.1 only after fresh Expo Doctor, locked install/typecheck/tests,
  Web/Android/iOS exports and signed native APK/source-hash checks pass. Prior
  cached doctor acceptance for RN0.88.0 is not a waiver for the fresh CI mismatch.

## P1 - Authentication còn lại

- [x] Regression for sequential Web multi-tab CSRF rotation: same-origin
  refresh/logout follow the current cookie; cross-origin cookie is not reused.
  This source increment still needs final bundle/CI/promotion evidence; the guest
  scan incident remains separate and has not been reproduced.

Report integration update: password login/register/MFA, server profile roles, native SecureStore and web HttpOnly refresh are connected. Remaining Auth items below apply to SSO, recovery and live deployment acceptance, not the former report demo login. Reports/Evidence/Moderation use real Gateway APIs; Notification and URL Watchlist share the existing Auth/API client; Scan and history also use Gateway APIs; Search normal mode uses the live public URL projection; Analytics calls the live Analytics-Service through Gateway without replacing failures with demo data.

- [x] Tạo giao diện quên/đặt lại mật khẩu; đã kiểm thử API thật/reset/replay/password change.
- [x] Nối login/register bằng mật khẩu với `Auth-Service` khi `EXPO_PUBLIC_API_URL` được đặt.
- [x] Nối Google/Facebook login/linking qua Gateway với PKCE và callback binding.
- [x] Callback Google đã đăng ký: Google/Meta tới form login, Gateway Web/native200 và browser binding đạt.
- [ ] Nghiệm thu consent/exchange/linking bằng provider test thật cho Google/Facebook.
- [x] PKCE/state/nonce, callback native/Expo Go, SecureStore và refresh-session rotation; device acceptance do người dùng thực hiện.
- [x] Nhập mã dự phòng/khôi phục MFA và resend email MFA; đã kiểm thử API thật, cooldown và mã cũ sai.
- [ ] Hoàn thiện session-expired flow: hiện 401 xóa phiên và đưa về đăng nhập, nhưng chưa có thông báo trước khi token hết hạn.
- [ ] Kiểm thử autofill với backend thật.

## P1 - Notification và URL Watchlist

- [x] In-app notifications: list, unread badge, đánh dấu đã đọc/đã đọc tất cả, polling khi tab hiển thị.
- [x] Tùy chọn thông báo risk/report/push/in-app qua Notification-Service.
- [x] URL Watchlist: thêm URL, bỏ theo dõi, hiển thị điểm/mức rủi ro từ API.
- [x] Hiển thị thông báo trình duyệt sau khi người dùng cấp quyền; không dùng mock token.
- [x] Nối list/register/revoke device API bằng token FCM thật được provision riêng.
- [ ] Tự provision FCM/service worker và nghiệm thu web/native push delivery; sender production hiện tắt.
- [x] Deep link trong app: mở notification watchlist/report tới đúng màn hình.
- [ ] Xử lý notification khi app đóng hoặc mở từ service worker/deep link native.
- [ ] Kiểm thử end-to-end với Auth + Notification + Gateway thật và tài khoản MFA.

## P1 - Quản trị & Analytics

### Phân quyền (auth do Auth-Service đảm nhiệm; Frontend chỉ tiêu thụ kết quả)

> API mode lấy vai trò từ Auth-Service; Report/Moderation có phân quyền độc lập ở Gateway và service. Bộ chọn vai trò chỉ còn trong demo mode. Dashboard thống kê demo không thay thế hàng chờ kiểm duyệt thật trong màn hình Báo cáo.

- [x] Khi nối API: UI Quản trị chỉ mở cho tài khoản ADMIN thật (role trả về từ `Auth-Service` khi đăng nhập); MODERATOR kiểm duyệt tại màn hình Báo cáo. **Không tự khai báo vai trò phía client** — vai trò chỉ tin từ server.
- [x] Analytics-Service kiểm tra JWT và role `ADMIN|MODERATOR` trên mỗi request; Gateway mở đúng ba route admin GET và kiểm tra role ở edge. Source tests đã chạy; nghiệm thu deployment thật vẫn theo checklist Infra. UI Quản trị vẫn chỉ mở cho ADMIN.
- [x] Gia hạn token thường xuyên: access token ngắn hạn + refresh-session rotation; mỗi lần refresh **tái kiểm tra hồ sơ/vai trò** — hết vai trò admin thì ẩn Quản trị và huỷ phiên cũ.
- [x] Chống vượt quyền: không nhận role/scope do client gửi lên; có negative authorization test (401/403) cho route quản trị.
- [x] Xử lý 401/403 phía client: dọn session, quay về màn hình đăng nhập, không giữ dữ liệu quản trị trong state/cache sau khi đổi vai trò hoặc hết phiên; có test admin-boundary cho demotion/revocation.
- [x] Live mode dùng dữ liệu thật và không còn nhãn "Dữ liệu minh họa"; `adminDemo.ts` chỉ thuộc build demo/E2E tường minh, không fallback lỗi API.

### Nối dữ liệu

- [x] Adapter của màn hình Quản trị (`AdminDashboardScreen`) gọi Gateway; Analytics-Service tổng hợp summary, hàng chờ và counts từ Report/Threat/Reputation.
- [x] Contract/OpenAPI analytics trong `../Contracts`, test mapping/upstream failure và edge fixture; service authorization có test riêng.
- [x] API mode không thay lỗi/thiếu Analytics API bằng mock; lỗi upstream hiển thị trạng thái lỗi thay vì số liệu giả.
- [x] Không có demo fallback trong normal/release; giữ build demo tường minh cho regression UI.
- [ ] Thêm biểu đồ thống kê cho màn hình quản trị khi được phê duyệt (giai đoạn này chưa có biểu đồ).

## Text/URL Scan integration

- [x] Replace scan mock with Gateway text/url APIs; restrict supported scan types.
- [x] Reuse real Auth token/refresh client for signed-in scans and history.
- [x] Display server snapshots, rule/model/embedded-link breakdown and explicit AI fallback.
- [x] Cancel/discard obsolete requests, handle transport/validation/quota failures.
- [x] Server history filters/pagination/detail/delete; no fake history for demo identity.
- [ ] Mount actual ONNX artifacts to verify real-model classifications in the website demo.

## API integration delivery — 2026-10-27

- [x] Source-copy public Gateway/contract and adapter regressions pass with JDK21/typecheck/Jest.
- [x] Isolated real Auth/TOTP/Gateway/Scan/History/Report/Evidence/Moderation/Kafka/Reputation/Notification/Watchlist chain passes, including ownership and moderator denials.
- [x] Desktop/mobile real browser Web login/logout, watchlist, scan/history and report evidence pass without API mocking.
- [ ] Promote exact reviewed SHAs through Jenkins and verify public-domain artifact/process/API evidence; fixture success is not production acceptance.

## Search increment — 2026-11-11

Live guest URL search replaces the earlier scaffold-unavailable state. Campaign
and masked phone/account/email require owning-service contracts before UI filters.
Native device acceptance remains separate from Web/JS build verification.
