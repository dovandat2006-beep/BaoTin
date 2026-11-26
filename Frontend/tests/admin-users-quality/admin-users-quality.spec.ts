import { test, expect } from "@playwright/test";
import { openNavigation } from "../navigation/helpers";

test("managed users disclose purposeful actions and preserve confirmation, error recovery and locking", async ({ page }, info) => {
  let reads = 0, writes = 0, fail = true;
  const id = "00000000-0000-4000-8000-000000000002";
  let role = "USER";
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, unreadCount: 0 };
    if (path.endsWith("/web/refresh")) return route.fulfill({ status: 401, json: { status: 401 } });
    if (path.endsWith("/web/login")) body = { accessToken: "fixture", csrfToken: "fixture" };
    else if (path.endsWith("/auth/me")) body = { id: "00000000-0000-4000-8000-000000000001", username: "fixture", displayName: "Admin Fixture", role: "ADMIN", emailVerified: true, mfaEnabled: true, mfaMethod: "TOTP" };
    else if (path.endsWith("/auth/admin/users")) {
      reads++; body = { items: [{ id, username: "target-fixture", displayName: "Target Fixture", role, status: "ACTIVE", mfaEnabled: true }], page: 0, size: 20, totalItems: 1, totalPages: 1 };
    } else if (path.endsWith("/auth/admin/users/" + id + "/role")) {
      writes++; await new Promise(resolve => setTimeout(resolve, 350));
      if (fail) return route.fulfill({ status: 503, json: { status: 503, detail: "Fixture change unavailable" } });
      expect(route.request().postDataJSON()).toEqual({ role: "MODERATOR" }); role = "MODERATOR"; return route.fulfill({ status: 204 });
    }
    return route.fulfill({ json: body });
  });
  await page.goto("/"); await page.getByLabel("Tùy chọn ứng dụng").click(); const toggle = page.getByLabel(info.project.name.endsWith("dark") ? "Chuyển sang giao diện tối" : "Chuyển sang giao diện sáng"); if (await toggle.isVisible()) await toggle.click(); await page.getByLabel("Đóng tùy chọn", { exact: true }).click(); expect(await page.locator("html").evaluate(el => el.classList.contains("dark"))).toBe(info.project.name.endsWith("dark"));
  await page.getByLabel("Đăng nhập hoặc đăng ký").click(); await page.getByLabel("Email, số điện thoại hoặc username", { exact: true }).fill("fixture"); await page.getByLabel("Mật khẩu", { exact: true }).fill("fixture-password"); await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await expect(page.getByLabel("Tài khoản của Admin Fixture")).toBeVisible(); await openNavigation(page, "Quản trị");
  expect(reads).toBe(0); const unlock = page.getByRole("button", { name: "Xác thực và tải người dùng", exact: true }); await expect(unlock).toBeDisabled(); await page.getByLabel("Mã xác thực quản trị", { exact: true }).fill("123456"); await unlock.click();
  await expect(page.getByText("Target Fixture · target-fixture", { exact: true })).toBeVisible();
  const choose = page.getByRole("button", { name: "Đặt quyền Kiểm duyệt viên cho target-fixture", exact: true }); await expect(choose).toHaveCount(0);
  const manage = page.getByRole("button", { name: /^(Quản lý tài khoản|Ẩn thao tác cho) target-fixture$/ }); await manage.click(); await expect(manage).toHaveAttribute("aria-expanded", "true"); await choose.click(); expect(writes).toBe(0);
  await expect(page.getByRole("dialog", { name: "Xác nhận thay đổi người dùng", exact: true })).toBeVisible(); await expect(page.getByText("Đổi quyền từ Người dùng thành Kiểm duyệt viên?", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Hủy thay đổi", exact: true }).click(); expect(writes).toBe(0); await choose.click();
  const confirm = page.getByRole("button", { name: "Xác nhận đổi quyền", exact: true }); await confirm.click(); await expect(page.getByRole("button", { name: "Đóng Xác nhận thay đổi người dùng", exact: true })).toBeDisabled();
  await expect(page.getByText("Fixture change unavailable", { exact: true })).toBeVisible(); await expect(page.getByRole("dialog", { name: "Xác nhận thay đổi người dùng", exact: true })).toBeVisible(); expect(writes).toBe(1);
  await page.screenshot({ path: `.redesign-artifacts/admin-users-20261116/${info.project.name}-error.png` });
  fail = false; await confirm.click(); await expect(page.getByText(/Đã cập nhật/)).toBeVisible(); await expect(choose).toBeDisabled(); expect(writes).toBe(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: `.redesign-artifacts/admin-users-20261116/${info.project.name}-users.png` });
  await page.evaluate(() => { Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" }); document.dispatchEvent(new Event("visibilitychange")); });
  await expect(page.getByLabel("Mã xác thực quản trị", { exact: true })).toHaveValue(""); await expect(manage).toHaveCount(0); expect(writes).toBe(2); expect(errors).toEqual([]);
});
