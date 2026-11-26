import { openNavigation } from "../navigation/helpers";
import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const root = "http://127.0.0.1:4190", gateway = "https://baotin.rimiprod.me";
const id = "00000000-0000-4000-8000-000000000001", other = "00000000-0000-4000-8000-000000000002";
async function fixture(context: BrowserContext, role: "USER" | "MODERATOR" | "ADMIN" = "USER", mfaMethod: "NONE" | "TOTP" | "EMAIL" = "NONE") {
  const state = { profile: { id, username: "account-fixture", displayName: "Account Fixture", email: "fixture@example.test", role, emailVerified: true, mfaEnabled: mfaMethod !== "NONE", mfaMethod }, requests: [] as { path: string; method: string; body: any }[], sessions: [{ id: other, createdAt: "2026-10-28T00:00:00Z", expiresAt: "2026-11-28T00:00:00Z", mfaAuthenticated: true }], devices: [{ id: other, platform: "ANDROID", deviceId: "fixture-device", active: true, createdAt: "2026-10-28T00:00:00Z", lastSeenAt: "2026-10-28T00:00:00Z" }], loginMfa: false, notifications: [] as any[], oauthLink: false, oauthContact: false, oauthMfa: false, oauthCancel: false, wrongFactor: false };
  await context.route(gateway + "/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method();
    if (!path.startsWith("/api/")) { const response = await context.request.get(root + (path.startsWith("/auth/") ? "/" : path) + url.search); await route.fulfill({ response }); return; }
    const input = route.request().postDataJSON(); state.requests.push({ path, method, body: input });
    let status = 200, body: any = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };
    const tokens = { accessToken: "fixture-access", csrfToken: "fixture-csrf", tokenType: "Bearer", expiresIn: 300 };
    if (path.endsWith("/web/refresh")) { status = 401; body = { status: 401, title: "No fixture session" }; }
    else if (path.endsWith("/web/login")) body = state.loginMfa ? { mfaRequired: true, mfaMethod: "TOTP", challenge: "fixture-challenge" } : tokens;
    else if (path.endsWith("/web/mfa/challenges")) body = tokens;
    else if (path.endsWith("/oauth2/requests") || path.endsWith("/oauth2/link-requests")) { state.oauthLink = path.endsWith("/link-requests"); body = { authorizationUrl: input.provider === "GOOGLE" ? "https://accounts.google.com/o/oauth2/v2/auth?state=fixture" : "https://www.facebook.com/v22.0/dialog/oauth?state=fixture" }; }
    else if (path.endsWith("/web/oauth2/tokens")) body = state.oauthLink ? { linked: true } : state.oauthContact && !input.contactEmail ? { onboardingRequired: true } : state.oauthMfa ? { mfaRequired: true, challenge: "fixture-challenge", mfaMethod: "TOTP" } : tokens;
    else if (path.endsWith("/auth/me")) { if (method === "PATCH") state.profile.displayName = input.displayName; body = state.profile; }
    else if (path.endsWith("/auth/sessions")) body = { items: state.sessions, page: 0, size: 20, totalItems: state.sessions.length, totalPages: 1 };
    else if (path.endsWith("/auth/sessions/" + other)) { state.sessions = []; status = 204; }
    else if (path.endsWith("/mfa/enrollments")) body = { secret: "FIXTUREONLYSECRET", otpauthUri: "otpauth://totp/fixture" };
    else if (path.endsWith("/mfa/enrollments/confirmation") && state.wrongFactor) { status = 401; body = { status: 401, code: "INVALID_CREDENTIALS", detail: "Fixture wrong factor" }; }
    else if (path.endsWith("/mfa/enrollments/confirmation") || path.endsWith("/mfa/recovery-codes")) body = { recoveryCodes: ["fixture-recovery-1", "fixture-recovery-2"] };
    else if (path.endsWith("/mfa/email/enrollments") || path.endsWith("/mfa/email/action-challenges")) body = { challenge: "e1.fixture", expiresIn: 300, mfaMethod: "EMAIL" };
    else if (path.endsWith("/auth/admin/users")) body = { items: [{ ...state.profile, status: "ACTIVE" }, { id: other, username: "target-fixture", displayName: "Target Fixture", role: "USER", status: "ACTIVE", mfaEnabled: true }], page: 0, size: 20, totalItems: 2, totalPages: 1 };
    else if (path.endsWith("/analytics/admin/summary")) body = { pendingReview: 7, needMoreInfo: 3, resolvedToday: 2, highRisk: 1, scansToday: 123 };
    else if (path.endsWith("/analytics/admin/counts")) body = { byStatus: { SUBMITTED: 7, PENDING_REVIEW: 0, NEED_MORE_INFO: 3, VERIFIED: 1, REJECTED: 1 }, byPriority: { LOW: 1, MEDIUM: 6, HIGH: 3 } };
    else if (path.endsWith("/analytics/admin/trends")) { status = 503; body = { status: 503, detail: "Fixture trends unavailable" }; }
    else if (path.endsWith("/notifications/devices") && method === "GET") body = { items: state.devices };
    else if (path.endsWith("/notifications/devices") && method === "POST") { body = { id: other, platform: input.platform, deviceId: input.deviceId, active: true, createdAt: "2026-10-28T00:00:00Z", lastSeenAt: "2026-10-28T00:00:00Z" }; state.devices = [body]; status = 201; }
    else if (path.endsWith("/notifications/devices/" + other)) { state.devices = []; status = 204; }
    else if (path === "/api/v1/notifications") body = { items: state.notifications, page: 0, size: 100, totalItems: state.notifications.length, totalPages: 1 };
    else if (path === "/api/v1/notifications/unread-count") body = { unreadCount: state.notifications.filter(n => !n.read).length };
    else if (path.startsWith("/api/v1/notifications/") && method === "DELETE") { state.notifications = []; status = 204; }
    else if (path.endsWith("/preferences")) body = { riskChangeEnabled: true, reportStatusEnabled: true, pushEnabled: false, inAppEnabled: true };
    else if (method !== "GET") { status = path.endsWith("/forgot-password") || path.endsWith("/email-verifications") || path.endsWith("/mfa/email/challenges") ? 202 : 204; }
    await route.fulfill({ status, headers: { "Access-Control-Allow-Origin": root, "Access-Control-Allow-Credentials": "true" }, ...(status === 202 || status === 204 ? {} : { contentType: status >= 400 ? "application/problem+json" : "application/json", body: JSON.stringify(body) }) });
  });
  // A fulfilled redirect chain bypasses Playwright's next route interception.
  // Navigate explicitly so this transport fixture never escapes to the live domain.
  for (const provider of ["https://accounts.google.com/**", "https://www.facebook.com/**"]) await context.route(provider, route => route.fulfill({ status: 200, contentType: "text/html", body: `<script>location.replace(${JSON.stringify(gateway + "/auth/callback?" + (state.oauthCancel ? "error=oauth_denied" : "code=" + "a".repeat(43) + (provider.includes("facebook.com") ? "#_=_" : "")))})</script>` }));
  return state;
}
async function login(page: Page) {
  await page.goto("/"); await page.getByLabel("Đăng nhập hoặc đăng ký").click();
  await page.getByLabel("Email, số điện thoại hoặc username").fill("fixture@example.test"); await page.getByLabel("Mật khẩu", { exact: true }).fill("fixture-password-only");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
}
async function account(page: Page) { await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible(); await page.getByLabel("Tài khoản của Account Fixture").click(); await expect(page.getByText("Tài khoản và bảo mật", { exact: true })).toBeVisible(); }

test("profile edits are real requests with no role assignment", async ({ page, context }) => {
  const state = await fixture(context); await login(page); await account(page);
  await page.getByLabel("Tên hiển thị", { exact: true }).fill("Changed Fixture"); await page.getByRole("button", { name: "Lưu hồ sơ", exact: true }).click();
  await expect(page.getByLabel("Tài khoản của Changed Fixture")).toBeVisible();
  expect(state.requests.find(r => r.path.endsWith("/auth/me") && r.method === "PATCH")?.body).toEqual({ displayName: "Changed Fixture" });
});
test("sessions require explicit confirmation and use DELETE/logout-all", async ({ page, context }) => {
  const state = await fixture(context); await login(page); await account(page); await page.getByRole("tab", { name: "Phiên", exact: true }).click();
  await page.getByRole("button", { name: "Đăng xuất phiên này", exact: true }).click(); expect(state.requests.filter(r => r.method === "DELETE")).toHaveLength(0);
  await page.getByRole("button", { name: "Xác nhận đăng xuất phiên", exact: true }).click(); await expect(page.getByText("Không có phiên còn hoạt động.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Đăng xuất mọi thiết bị", exact: true }).click(); await page.getByRole("button", { name: "Xác nhận đăng xuất mọi thiết bị", exact: true }).click();
  await expect(page.getByText("Đăng nhập BaoTin", { exact: true })).toBeVisible(); expect(state.requests.some(r => r.path.endsWith("/logout-all"))).toBe(true);
});
test("forgot/reset/verify use their public API without claiming account existence", async ({ page, context }) => {
  const state = await fixture(context); await page.goto("/"); await page.getByLabel("Đăng nhập hoặc đăng ký").click(); await page.getByRole("button", { name: "Quên mật khẩu hoặc xác minh email", exact: true }).click();
  await page.getByLabel("Email hoặc username khôi phục").fill("fixture@example.test"); await page.getByRole("button", { name: "Gửi yêu cầu khôi phục hoặc xác minh", exact: true }).click(); await expect(page.getByText(/Nếu thông tin khớp tài khoản/)).toBeVisible();
  await page.getByRole("tab", { name: "Đặt lại", exact: true }).click(); await page.getByLabel("Token đặt lại mật khẩu").fill("fixture-once"); await page.getByLabel("Mật khẩu khôi phục mới", { exact: true }).fill("new-fixture-password"); await page.getByLabel("Nhập lại mật khẩu khôi phục").fill("new-fixture-password"); await page.getByRole("button", { name: "Gửi yêu cầu khôi phục hoặc xác minh", exact: true }).click(); await expect(page.getByText(/Đã đặt lại mật khẩu/)).toBeVisible();
  await page.getByRole("tab", { name: "Xác minh email", exact: true }).click(); await page.getByLabel("Token xác minh email").fill("fixture-once"); await page.getByRole("button", { name: "Gửi yêu cầu khôi phục hoặc xác minh", exact: true }).click(); await expect(page.getByText(/Đã xác minh email/)).toBeVisible();
  expect(state.requests.filter(r => /forgot-password|reset-password|verify-email/.test(r.path)).map(r => r.path)).toHaveLength(3);
});
test("TOTP enrollment invalidates the session and shows recovery codes only once", async ({ page, context }) => {
  const state = await fixture(context); await login(page); await account(page); await page.getByRole("tab", { name: "Bảo mật", exact: true }).click(); await page.getByRole("button", { name: "Thiết lập ứng dụng xác thực", exact: true }).click();
  await page.getByLabel("Mật khẩu hiện tại", { exact: true }).fill("fixture-password-only"); await page.getByRole("button", { name: "Tiếp tục thiết lập", exact: true }).click(); await expect(page.getByText("FIXTUREONLYSECRET", { exact: true })).toBeVisible();
  await page.getByLabel("Mã xác thực bảo mật").fill("123456"); await page.getByRole("button", { name: "Xác nhận bật xác thực hai bước", exact: true }).click(); await expect(page.getByText("fixture-recovery-1", { exact: true })).toBeVisible(); await page.getByRole("button", { name: "Đã lưu mã, tiếp tục đăng nhập", exact: true }).click(); await expect(page.getByText("fixture-recovery-1", { exact: true })).toHaveCount(0); await expect(page.getByText("Đăng nhập BaoTin", { exact: true })).toBeVisible();
  expect(state.requests.some(r => r.path.endsWith("/mfa/enrollments/confirmation") && r.body.code === "123456")).toBe(true);
});
test("ADMIN has no user fetch until TOTP step-up and cannot grant ADMIN", async ({ page, context }) => {
  const state = await fixture(context, "ADMIN", "TOTP"); await login(page); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible(); await openNavigation(page, "Quản trị");
  expect(state.requests.filter(r => r.path.includes("/auth/admin/"))).toHaveLength(0);
  await page.getByLabel("Mã xác thực quản trị").fill("123456"); await page.getByRole("button", { name: "Xác thực và tải người dùng", exact: true }).click(); await expect(page.getByText("Target Fixture · target-fixture", { exact: true })).toBeVisible();
  expect(state.requests.findIndex(r => r.path.endsWith("/step-up"))).toBeLessThan(state.requests.findIndex(r => r.path.endsWith("/auth/admin/users")));
  await page.getByRole("button", { name: "Quản lý tài khoản target-fixture", exact: true }).click(); await expect(page.getByRole("button", { name: /Đặt quyền Quản trị viên/ })).toHaveCount(0); await page.getByRole("button", { name: "Đặt quyền Kiểm duyệt viên cho target-fixture", exact: true }).click(); await page.getByRole("button", { name: /^Xác nhận (đổi quyền|khóa|kích hoạt|vô hiệu hóa)$/, exact: true }).click(); await expect(page.getByText(/Đã cập nhật/)).toBeVisible(); expect(state.requests.some(r => r.path.endsWith("/role") && r.body.role === "MODERATOR")).toBe(true);
});
test("remote Analytics and local user administration remain independently accessible", async ({ page, context }) => {
  const state = await fixture(context, "ADMIN", "TOTP"); await login(page); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible(); await openNavigation(page, "Quản trị");
  await expect(page.getByText("Quản lý người dùng", { exact: true })).toBeVisible(); await page.getByRole("tab", { name: "Tố giác", exact: true }).click(); await expect(page.getByText("Thống kê tố giác", { exact: true })).toBeVisible(); await expect(page.getByText("Kiểm duyệt báo cáo", { exact: true })).toBeVisible(); await expect(page.getByText("Dữ liệu minh họa", { exact: true })).toHaveCount(0); expect(state.requests.some(r => r.path === "/api/v1/moderation/reports")).toBe(true); expect(state.requests.some(r => r.path.includes("/analytics/admin/"))).toBe(false);
  await page.getByRole("tab", { name: "Tổng quan", exact: true }).click(); await expect(page.getByLabel("7 báo cáo đang chờ kiểm duyệt", { exact: true })).toBeVisible(); expect(state.requests.some(r => r.path.includes("/analytics/admin/summary"))).toBe(true);
  await expect(page.getByTestId("admin-trend-chart").getByText("Fixture trends unavailable", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "Người dùng", exact: true }).click(); await expect(page.getByLabel("Mã xác thực quản trị")).toBeVisible(); expect(state.requests.some(r => r.path.includes("/auth/admin/users"))).toBe(false);
});
test("device list/revoke/registration uses write-only provider tokens", async ({ page, context }) => {
  const state = await fixture(context); await login(page); await account(page); await page.getByRole("tab", { name: "Thiết bị", exact: true }).click(); await expect(page.getByText("Đã đăng ký", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Thu hồi đăng ký Android, fixture-device", exact: true }).click(); await page.getByRole("button", { name: "Xác nhận thu hồi thiết bị", exact: true }).click(); await expect(page.getByText("Chưa có thiết bị nhận thông báo.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Đăng ký thiết bị", exact: true }).click(); await page.getByLabel("Mã thiết bị", { exact: true }).fill("fixture-device"); await page.getByLabel("Mã đăng ký", { exact: true }).fill("fixture-fcm-transport-only-not-a-real-token"); await page.getByRole("button", { name: "Lưu thiết bị thông báo", exact: true }).click(); await expect(page.getByText("Đã đăng ký", { exact: true })).toBeVisible(); expect(state.requests.some(r => r.path.endsWith("/notifications/devices") && r.method === "POST")).toBe(true);
});
test("history clear-all is confirmed and server-backed", async ({ page, context }) => {
  const state = await fixture(context); await login(page); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible(); await page.getByText("Lịch sử", { exact: true }).last().click(); await page.getByRole("button", { name: "Xóa toàn bộ lịch sử", exact: true }).click(); expect(state.requests.filter(r => r.path.endsWith("/scans/history") && r.method === "DELETE")).toHaveLength(0); await page.getByRole("button", { name: "Xác nhận xóa toàn bộ lịch sử", exact: true }).click(); await expect(page.getByText("Chưa có lịch sử", { exact: true })).toBeVisible(); expect(state.requests.filter(r => r.path.endsWith("/scans/history") && r.method === "DELETE")).toHaveLength(1);
});
test("notification deletion is confirmed and server-backed", async ({ page, context }) => {
  const state = await fixture(context); state.notifications = [{ id: other, type: "REPORT_VERIFIED", title: "Fixture notification", body: "Transport fixture only", read: false, createdAt: "2026-10-28T00:00:00Z" }]; await login(page); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible(); await page.getByLabel("Thông báo, 1 chưa đọc").click(); await page.getByRole("button", { name: "Xóa thông báo Fixture notification", exact: true }).click(); await page.getByRole("button", { name: "Xác nhận xóa thông báo", exact: true }).click(); await expect(page.getByText("Chưa có thông báo", { exact: true })).toBeVisible(); expect(state.requests.some(r => r.path.endsWith("/notifications/" + other) && r.method === "DELETE")).toBe(true);
});
for (const provider of ["Google", "Facebook"] as const) test(`OAuth ${provider} popup passes a one-time ticket with PKCE through Gateway`, async ({ page, context }) => {
  const state = await fixture(context);
  await page.goto(gateway + "/"); await page.getByLabel("Đăng nhập hoặc đăng ký").click(); await page.getByLabel("Tiếp tục với " + provider).click();
  await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible();
  const start = state.requests.find(r => r.path.endsWith("/oauth2/requests")), exchange = state.requests.find(r => r.path.endsWith("/web/oauth2/tokens")); expect(start?.body.provider).toBe(provider.toUpperCase()); expect(start?.body.transport).toBe("WEB"); expect(start?.body.codeChallenge).toHaveLength(43); expect(exchange?.body.codeVerifier).toHaveLength(43); expect(exchange?.body.code).toBe("a".repeat(43));
});
test("OAuth missing contact and MFA still require their distinct confirmation steps", async ({ page, context }) => {
  const state = await fixture(context); state.oauthContact = true; state.oauthMfa = true;
  await page.goto(gateway + "/"); await page.getByLabel("Đăng nhập hoặc đăng ký").click(); await page.getByLabel("Tiếp tục với Google").click(); await page.getByLabel("Email liên hệ OAuth").fill("fixture@example.test"); await page.getByRole("button", { name: "Hoàn tất đăng nhập nhà cung cấp", exact: true }).click(); await page.getByLabel("Mã xác thực", { exact: true }).fill("123456"); await page.getByRole("button", { name: "Xác nhận mã", exact: true }).click(); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible(); expect(state.requests.filter(r => r.path.endsWith("/web/oauth2/tokens"))).toHaveLength(2);
});
test("OAuth cancellation never produces a signed-in session", async ({ page, context }) => {
  const state = await fixture(context); state.oauthCancel = true;
  await page.goto(gateway + "/"); await page.getByLabel("Đăng nhập hoặc đăng ký").click(); await page.getByLabel("Tiếp tục với Google").click(); await expect(page.getByText(/Bạn đã hủy hoặc nhà cung cấp chưa hoàn tất/)).toBeVisible(); await expect(page.getByLabel("Tài khoản của Account Fixture")).toHaveCount(0); expect(state.requests.some(r => r.path.endsWith("/web/oauth2/tokens"))).toBe(false);
});
test("explicit provider linking preserves the existing identity instead of logging in another account", async ({ page, context }) => {
  const state = await fixture(context); await page.goto(gateway + "/"); await page.getByLabel("Đăng nhập hoặc đăng ký").click(); await page.getByLabel("Email, số điện thoại hoặc username").fill("fixture@example.test"); await page.getByLabel("Mật khẩu", { exact: true }).fill("fixture-password-only"); await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await account(page); await page.getByRole("tab", { name: "Bảo mật", exact: true }).click(); await page.getByRole("button", { name: "Liên kết Google", exact: true }).click(); await expect(page.getByText("Đã liên kết Google với tài khoản BaoTin này.", { exact: true })).toBeVisible(); expect(state.requests.some(r => r.path.endsWith("/oauth2/link-requests"))).toBe(true); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible();
});
for (const flow of [
  { label: "Đổi mật khẩu", path: "/password", password: true, next: true, code: false },
  { label: "Tạo mật khẩu", path: "/password-initialization", password: false, next: true, code: false },
  { label: "Mã dự phòng", path: "/mfa/recovery-codes", password: true, next: false, code: true },
  { label: "Tắt xác thực hai bước", path: "/mfa/disable", password: true, next: false, code: true }
]) test(`security action ${flow.label} calls the existing API and invalidates the session`, async ({ page, context }) => {
  const state = await fixture(context); await login(page); await account(page); await page.getByRole("tab", { name: "Bảo mật", exact: true }).click(); await page.getByRole("button", { name: flow.label === "Mã dự phòng" ? "Tạo mã dự phòng mới" : flow.label, exact: true }).click();
  if (flow.password) await page.getByLabel("Mật khẩu hiện tại", { exact: true }).fill("fixture-password-only");
  if (flow.next) { await page.getByLabel("Mật khẩu mới", { exact: true }).fill("new-fixture-password"); await page.getByLabel("Nhập lại mật khẩu mới", { exact: true }).fill("new-fixture-password"); }
  if (flow.code) await page.getByLabel("Mã xác thực bảo mật").fill("123456");
  await page.getByRole("button", { name: flow.label === "Đổi mật khẩu" ? "Lưu mật khẩu mới" : flow.label === "Mã dự phòng" ? "Tạo mã dự phòng mới" : flow.label, exact: true }).click();
  if (flow.label === "Mã dự phòng") await page.getByRole("button", { name: "Đã lưu mã, tiếp tục đăng nhập", exact: true }).click();
  await expect(page.getByText("Đăng nhập BaoTin", { exact: true })).toBeVisible(); expect(state.requests.filter(r => r.path.endsWith(flow.path) && r.method === "POST")).toHaveLength(1);
});
test("wrong one-time MFA code is never replayed and keeps a verified session", async ({ page, context }) => {
  const state = await fixture(context); state.wrongFactor = true; await login(page); await account(page); await page.getByRole("tab", { name: "Bảo mật", exact: true }).click(); await page.getByRole("button", { name: "Thiết lập ứng dụng xác thực", exact: true }).click(); await page.getByLabel("Mật khẩu hiện tại", { exact: true }).fill("fixture-password-only"); await page.getByRole("button", { name: "Tiếp tục thiết lập", exact: true }).click(); await page.getByLabel("Mã xác thực bảo mật").fill("000000"); await page.getByRole("button", { name: "Xác nhận bật xác thực hai bước", exact: true }).click(); await expect(page.getByText("Fixture wrong factor", { exact: true })).toBeVisible(); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible(); expect(state.requests.filter(r => r.path.endsWith("/mfa/enrollments/confirmation"))).toHaveLength(1);
});
test("email MFA enrollment and purpose-bound password proof use separate challenges", async ({ page, context }) => {
  const state = await fixture(context, "USER", "EMAIL"); await login(page); await account(page); await page.getByRole("tab", { name: "Bảo mật", exact: true }).click(); await page.getByRole("button", { name: "Đổi mật khẩu", exact: true }).click(); await page.getByLabel("Mật khẩu hiện tại", { exact: true }).fill("fixture-password-only"); await page.getByLabel("Mật khẩu mới", { exact: true }).fill("new-fixture-password"); await page.getByLabel("Nhập lại mật khẩu mới", { exact: true }).fill("new-fixture-password"); await page.getByRole("button", { name: "Yêu cầu mã email cho thao tác này", exact: true }).click(); await page.getByLabel("Mã xác thực bảo mật").fill("123456"); await page.getByRole("button", { name: "Lưu mật khẩu mới", exact: true }).click(); await expect(page.getByText("Đăng nhập BaoTin", { exact: true })).toBeVisible(); expect(state.requests.find(r => r.path.endsWith("/password"))?.body.code).toBe("e1.fixture.123456"); expect(state.requests.find(r => r.path.endsWith("/action-challenges"))?.body.purpose).toBe("CHANGE_PASSWORD");
});
test("Web APK button appears only for a verified-format manifest and downloads the attached file", async ({ page, context }) => {
  await fixture(context);const sha="a".repeat(64),apk=Buffer.from([80,75,3,4]);
  await context.route("**/downloads/android.json",route=>route.fulfill({status:200,contentType:"application/json",body:JSON.stringify({schemaVersion:1,version:"0.1.0",versionCode:1,bytes:apk.length,sha256:sha,url:`/downloads/baotin-${sha}.apk`,sourceSnapshotSha256:"b".repeat(64),gatewayOrigin:"https://baotin.rimiprod.me",signingCertificateSha256:"c".repeat(64),publishedAt:"2026-10-29T00:00:00.000Z"})}));
  await context.route(`**/downloads/baotin-${sha}.apk`,route=>route.fulfill({status:200,headers:{"Content-Type":"application/vnd.android.package-archive","Content-Disposition":'attachment; filename="baotin-fixture.apk"'},body:apk}));
  await page.goto("/");await page.getByLabel("Tùy chọn ứng dụng").click();await page.getByLabel("Tải APK Android",{exact:true}).click();await expect(page.getByText("Tải ứng dụng Android",{exact:true})).toBeVisible();await expect(page.getByText(sha,{exact:true})).toBeVisible();const download=page.waitForEvent("download");await page.getByRole("button",{name:"Tải file APK",exact:true}).click();expect((await download).suggestedFilename()).toBe("baotin-fixture.apk");
});
test("missing or off-site APK manifests expose no install/download control", async ({ page, context }) => {
  await fixture(context);await context.route("**/downloads/android.json",route=>route.fulfill({status:200,contentType:"application/json",body:JSON.stringify({schemaVersion:1,url:"https://other.example/app.apk"})}));await page.goto("/");await page.getByLabel("Tùy chọn ứng dụng").click();await expect(page.getByLabel("Tải APK Android",{exact:true})).toHaveCount(0);
});


test("history expands complete masked message rather than hiding dangerous wording", async ({ page, context }) => {
  await fixture(context);
  const preview = "Cảnh báo khóa tài khoản! Nhập OTP ***; liên hệ ***. " + "Giữ nội dung để cảnh báo. ".repeat(20) + "Dấu cuối lịch sử.";
  await context.route(gateway + "/api/v1/scans/history?**", route => route.fulfill({ contentType: "application/json", headers: { "Access-Control-Allow-Origin": root, "Access-Control-Allow-Credentials": "true" }, body: JSON.stringify({
    items: [{ scanId: id, type: "TEXT", targetId: null, preview, risk: { score: 84, level: "HIGH" }, textModel: null, urlModel: null,
      rules: { score: 80, signals: [] }, urls: [], aiAvailable: false, degraded: true, warnings: [], scannedAt: "2026-11-12T00:00:00Z" }], page: 0, size: 20, totalItems: 1, totalPages: 1
  }) }));
  await login(page); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible();
  await page.getByText("Lịch sử", { exact: true }).last().click();
  await page.getByRole("button", { name: "Xem kết quả lúc kiểm tra", exact: true }).click();
  await expect(page.getByText("Nội dung tin nhắn", { exact: true })).toBeVisible();
  await expect(page.getByText(preview, { exact: true }).last()).toBeVisible();
  await expect(page.getByText("Thông tin nhạy cảm được thay bằng ***.", { exact: true })).toBeVisible();
  await expect(page.getByText("[Nội dung tin nhắn đã ẩn]", { exact: true })).toHaveCount(0);
});


test("legacy history honestly reports missing stored input instead of implying content is still hidden", async ({ page, context }) => {
  await fixture(context);
  await context.route(gateway + "/api/v1/scans/history?**", route => route.fulfill({ contentType: "application/json", headers: { "Access-Control-Allow-Origin": root, "Access-Control-Allow-Credentials": "true" }, body: JSON.stringify({
    items: [{ scanId: id, type: "TEXT", targetId: null, preview: "[Nội dung tin nhắn đã ẩn]", risk: { score: 84, level: "HIGH" }, textModel: null, urlModel: null,
      rules: { score: 80, signals: [] }, urls: [], aiAvailable: false, degraded: true, warnings: [], scannedAt: "2026-11-12T00:00:00Z" }], page: 0, size: 20, totalItems: 1, totalPages: 1
  }) }));
  await login(page); await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible();
  await page.getByText("Lịch sử", { exact: true }).last().click();
  await page.getByRole("button", { name: "Xem kết quả lúc kiểm tra", exact: true }).click();
  await expect(page.getByText("Nội dung bản ghi cũ không còn được lưu.", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("[Nội dung tin nhắn đã ẩn]", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Thông tin nhạy cảm được thay bằng ***.", { exact: true })).toHaveCount(0);
});


test("admin account status controls open visible confirmation and report failures inline", async ({ page, context }) => {
  await fixture(context,"ADMIN","TOTP");await login(page);await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible();await openNavigation(page, "Quản trị");
  await page.getByLabel("Mã xác thực quản trị").fill("123456");await page.getByRole("button",{name:"Xác thực và tải người dùng",exact:true}).click();
  await expect(page.getByText("Đã xác thực và tải danh sách người dùng.",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Quản lý tài khoản target-fixture",exact:true}).click();
  for(const status of ["LOCKED","DISABLED"]) {
    await page.getByRole("button",{name:({LOCKED:"Khóa",ACTIVE:"Kích hoạt",DISABLED:"Vô hiệu hóa"}[status])+" tài khoản target-fixture",exact:true}).click();
    const confirm=page.getByRole("button",{name:/^Xác nhận (đổi quyền|khóa|kích hoạt|vô hiệu hóa)$/,exact:true});await expect(confirm).toBeVisible();
    await page.getByRole("button",{name:"Hủy thay đổi",exact:true}).click();await expect(confirm).toHaveCount(0);
  }
  await context.route(gateway+"/api/v1/auth/admin/users/"+other+"/role",route=>route.fulfill({status:409,contentType:"application/problem+json",headers:{"Access-Control-Allow-Origin":root,"Access-Control-Allow-Credentials":"true"},body:JSON.stringify({status:409,code:"TARGET_MFA_REQUIRED",detail:"Target must verify email and confirm authenticator first"})}));
  await page.getByRole("button",{name:"Đặt quyền Kiểm duyệt viên cho target-fixture",exact:true}).click();await page.getByRole("button",{name:/^Xác nhận (đổi quyền|khóa|kích hoạt|vô hiệu hóa)$/,exact:true}).click();
  await expect(page.getByText("Tài khoản đích cần xác minh email và bật Authenticator trước khi được cấp quyền kiểm duyệt.",{exact:true})).toBeVisible();
});


test("every managed-user role/status refresh pagination and lock control provides feedback", async ({ page, context }) => {
  await fixture(context,"ADMIN","TOTP");
  let managed={id:other,username:"target-fixture",displayName:"Target Fixture",role:"USER",status:"ACTIVE",mfaEnabled:true};
  const writes:string[]=[];
  await context.route(gateway+"/api/v1/auth/admin/users**",async route=>{
    const path=new URL(route.request().url()).pathname;const headers={"Access-Control-Allow-Origin":root,"Access-Control-Allow-Credentials":"true"};
    if(route.request().method()==="GET")return route.fulfill({contentType:"application/json",headers,body:JSON.stringify({items:[managed],page:Number(new URL(route.request().url()).searchParams.get("page")),size:20,totalItems:21,totalPages:2})});
    const input=route.request().postDataJSON();if(path.endsWith("/role")){managed={...managed,role:input.role};writes.push(input.role);}else{managed={...managed,status:input.status};writes.push(input.status);}
    return route.fulfill({status:204,headers});
  });
  await login(page);await expect(page.getByLabel("Tài khoản của Account Fixture")).toBeVisible();await openNavigation(page, "Quản trị");
  await page.getByLabel("Mã xác thực quản trị").fill("123456");await page.getByRole("button",{name:"Xác thực và tải người dùng",exact:true}).click();await expect(page.getByText("Target Fixture · target-fixture",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Quản lý tài khoản target-fixture",exact:true}).click();
  for(const role of ["MODERATOR","USER"]){await page.getByRole("button",{name:"Đặt quyền "+({MODERATOR:"Kiểm duyệt viên",USER:"Người dùng"}[role])+" cho target-fixture",exact:true}).click();await expect(page.getByRole("button",{name:/^Xác nhận (đổi quyền|khóa|kích hoạt|vô hiệu hóa)$/,exact:true})).toBeVisible();await page.getByRole("button",{name:/^Xác nhận (đổi quyền|khóa|kích hoạt|vô hiệu hóa)$/,exact:true}).click();await expect(page.getByText(/Đã cập nhật/)).toBeVisible();await expect(page.getByRole("button",{name:"Đặt quyền "+({MODERATOR:"Kiểm duyệt viên",USER:"Người dùng"}[role])+" cho target-fixture",exact:true})).toBeDisabled();}
  for(const status of ["LOCKED","ACTIVE","DISABLED","ACTIVE"]){await page.getByRole("button",{name:({LOCKED:"Khóa",ACTIVE:"Kích hoạt",DISABLED:"Vô hiệu hóa"}[status])+" tài khoản target-fixture",exact:true}).click();await page.getByRole("button",{name:/^Xác nhận (đổi quyền|khóa|kích hoạt|vô hiệu hóa)$/,exact:true}).click();await expect(page.getByText(/Đã cập nhật/)).toBeVisible();await expect(page.getByRole("button",{name:({LOCKED:"Khóa",ACTIVE:"Kích hoạt",DISABLED:"Vô hiệu hóa"}[status])+" tài khoản target-fixture",exact:true})).toBeDisabled();}
  expect(writes).toEqual(["MODERATOR","USER","LOCKED","ACTIVE","DISABLED","ACTIVE"]);
  await page.getByRole("button",{name:"Người dùng trang sau",exact:true}).click();await expect(page.getByText("2/2",{exact:true})).toBeVisible();await page.getByRole("button",{name:"Người dùng trang trước",exact:true}).click();await expect(page.getByText("1/2",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Làm mới người dùng",exact:true}).click();await expect(page.getByText("Đã làm mới danh sách người dùng.",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Khóa màn hình quản trị",exact:true}).click();await expect(page.getByLabel("Mã xác thực quản trị")).toBeVisible();await expect(page.getByText("Target Fixture · target-fixture",{exact:true})).toHaveCount(0);
});
