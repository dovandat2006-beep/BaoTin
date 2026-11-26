import { test, expect } from "@playwright/test";

test("devices distinguish fetch failure, confirmation, write recovery and secure registration", async ({ page }, info) => {
  let failList = true, failWrite = true, deletes = 0, posts = 0;
  const id = "00000000-0000-4000-8000-000000000002";
  let devices = [{ id, platform: "ANDROID", deviceId: "fixture-device-long-name-for-responsive-verification-000001", active: true, createdAt: "2026-10-28T00:00:00Z", lastSeenAt: "2026-10-28T00:00:00Z" }];
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/**", async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    let body: unknown = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, unreadCount: 0 };
    if (path.endsWith("/web/refresh")) return route.fulfill({ status: 401, json: { status: 401 } });
    if (path.endsWith("/web/login")) body = { accessToken: "fixture", csrfToken: "fixture" };
    else if (path.endsWith("/auth/me")) body = { id: "00000000-0000-4000-8000-000000000001", username: "fixture", displayName: "Device Fixture", role: "USER", email: "fixture@example.test", emailVerified: true, mfaEnabled: false, mfaMethod: "NONE" };
    else if (path.endsWith("/notifications/devices") && req.method() === "GET") {
      if (failList) return route.fulfill({ status: 503, json: { status: 503, detail: "Fixture devices unavailable" } });
      body = { items: devices };
    } else if (path.endsWith("/notifications/devices/" + id)) {
      deletes++; devices = []; failList = true; return route.fulfill({ status: 204 });
    } else if (path.endsWith("/notifications/devices") && req.method() === "POST") {
      posts++; await new Promise(resolve => setTimeout(resolve, 300));
      if (failWrite) return route.fulfill({ status: 503, json: { status: 503, detail: "Fixture registration unavailable" } });
      const input = req.postDataJSON(); expect(input).toEqual({ platform: "ANDROID", token: "fixture-token-write-only-123456", deviceId: "fixture-new-device" });
      devices = [{ id, platform: input.platform, deviceId: input.deviceId, active: true, createdAt: "2026-10-28T00:00:00Z", lastSeenAt: "2026-10-28T00:00:00Z" }]; body = devices[0];
    }
    return route.fulfill({ json: body });
  });
  await page.goto("/"); await page.getByLabel("Tùy chọn ứng dụng").click();
  const toggle = page.getByLabel(info.project.name.endsWith("dark") ? "Chuyển sang giao diện tối" : "Chuyển sang giao diện sáng");
  if (await toggle.isVisible()) await toggle.click();
  await page.getByLabel("Đóng tùy chọn", { exact: true }).click();
  expect(await page.locator("html").evaluate(el => el.classList.contains("dark"))).toBe(info.project.name.endsWith("dark"));
  await page.getByLabel("Đăng nhập hoặc đăng ký").click();
  await page.getByLabel("Email, số điện thoại hoặc username", { exact: true }).fill("fixture");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("fixture-password");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await page.getByLabel("Tài khoản của Device Fixture").click(); await page.getByRole("tab", { name: "Thiết bị", exact: true }).click();
  const panel = page.getByTestId("notification-devices");
  await expect(panel.getByText("Chưa tải được thiết bị", { exact: true })).toBeVisible();
  await expect(panel.getByText("Chưa có thiết bị nhận thông báo.", { exact: true })).toHaveCount(0);
  failList = false; await panel.getByRole("button", { name: "Thử lại", exact: true }).click();
  await panel.getByRole("button", { name: /^Thu hồi đăng ký Android,/ }).click();
  expect(deletes).toBe(0); await panel.getByRole("button", { name: "Hủy thu hồi thiết bị", exact: true }).click(); expect(deletes).toBe(0);
  await panel.getByRole("button", { name: /^Thu hồi đăng ký Android,/ }).click();
  await page.screenshot({ path: `.redesign-artifacts/notification-devices-20261116/${info.project.name}-confirm.png` });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await panel.getByRole("button", { name: "Xác nhận thu hồi thiết bị", exact: true }).click();
  await expect(panel.getByText("Đã thu hồi đăng ký nhận thông báo của thiết bị.", { exact: true })).toBeVisible();
  await expect(panel.getByText("Chưa tải được thiết bị", { exact: true })).toBeVisible(); expect(deletes).toBe(1);
  failList = false; await panel.getByRole("button", { name: "Thử lại", exact: true }).click();
  await expect(panel.getByText("Chưa có thiết bị nhận thông báo.", { exact: true })).toBeVisible(); expect(deletes).toBe(1);
  await panel.getByRole("button", { name: "Đăng ký thiết bị", exact: true }).click();
  const save = panel.getByRole("button", { name: "Lưu thiết bị thông báo", exact: true }); await expect(save).toBeDisabled();
  await panel.getByLabel("Mã thiết bị", { exact: true }).fill("fixture-new-device");
  const token = panel.getByLabel("Mã đăng ký", { exact: true }); await expect(token).toHaveAttribute("type", "password");
  await token.fill("Expo-invalid-token"); await save.click();
  await expect(panel.getByText("Mã đăng ký thiết bị không hợp lệ.", { exact: true })).toBeVisible(); expect(posts).toBe(0);
  await token.fill("fixture-token-write-only-123456");
  await page.evaluate(() => { Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" }); document.dispatchEvent(new Event("visibilitychange")); });
  await expect(token).toHaveValue("");
  await page.evaluate(() => { Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" }); document.dispatchEvent(new Event("visibilitychange")); });
  await token.fill("fixture-token-write-only-123456"); await save.click();
  await expect(panel.getByRole("button", { name: "Hủy thêm thiết bị", exact: true })).toBeDisabled();
  await expect(panel.getByText("Fixture registration unavailable", { exact: true })).toBeVisible();
  await expect(token).toHaveValue("fixture-token-write-only-123456"); expect(posts).toBe(1);
  await expect(panel.getByText("Đã đăng ký thiết bị nhận thông báo.", { exact: true })).toHaveCount(0);
  await page.screenshot({ path: `.redesign-artifacts/notification-devices-20261116/${info.project.name}-error.png` });
  failWrite = false; await save.click();
  await expect(panel.getByText("Đã đăng ký thiết bị nhận thông báo.", { exact: true })).toBeVisible();
  await expect(panel.getByText("Mã thiết bị: fixture-new-device", { exact: true })).toBeVisible();
  await expect(token).toHaveCount(0); expect(posts).toBe(2); expect(deletes).toBe(1); expect(errors).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
