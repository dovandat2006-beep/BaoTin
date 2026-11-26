import { test, expect } from "@playwright/test";
import { openNavigation } from "../navigation/helpers";

test("admin tabs are named, keyboard-linked and discard inactive private state", async ({ page }, info) => {
  let users = 0, stepUps = 0, analytics = 0;
  await page.route("**/api/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, unreadCount: 0 };
    if (path.endsWith("/web/refresh")) return route.fulfill({ status: 401, json: { status: 401 } });
    if (path.endsWith("/web/login")) body = { accessToken: "fixture", csrfToken: "fixture" };
    else if (path.endsWith("/auth/me")) body = { id: "00000000-0000-4000-8000-000000000001", username: "fixture", displayName: "Admin Fixture", role: "ADMIN", emailVerified: true, mfaEnabled: true, mfaMethod: "TOTP" };
    else if (path.endsWith("/auth/admin/users")) users++;
    else if (path.endsWith("/auth/step-up")) stepUps++;
    else if (path.includes("/analytics/admin/")) analytics++;
    return route.fulfill({ json: body });
  });
  await page.goto("/"); await page.getByLabel("Tùy chọn ứng dụng").click();
  const toggle = page.getByLabel(info.project.name.endsWith("dark") ? "Chuyển sang giao diện tối" : "Chuyển sang giao diện sáng"); if (await toggle.isVisible()) await toggle.click();
  await page.getByLabel("Đóng tùy chọn", { exact: true }).click(); expect(await page.locator("html").evaluate(el => el.classList.contains("dark"))).toBe(info.project.name.endsWith("dark"));
  await page.getByLabel("Đăng nhập hoặc đăng ký").click(); await page.getByLabel("Email, số điện thoại hoặc username", { exact: true }).fill("fixture"); await page.getByLabel("Mật khẩu", { exact: true }).fill("fixture-password"); await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await expect(page.getByLabel("Tài khoản của Admin Fixture")).toBeVisible(); await openNavigation(page, "Quản trị");
  const tabs = page.getByRole("tablist", { name: "Quản trị", exact: true }), people = tabs.getByRole("tab", { name: "Người dùng", exact: true }), stats = tabs.getByRole("tab", { name: "Tố giác", exact: true });
  await expect(people).toHaveAttribute("aria-selected", "true"); await expect(page.getByRole("tabpanel", { name: "Người dùng", exact: true })).toBeVisible(); expect(users).toBe(0);
  await page.getByLabel("Mã xác thực quản trị", { exact: true }).fill("123456"); await page.getByRole("button", { name: "Xác thực và tải người dùng", exact: true }).click(); await expect(page.getByRole("button", { name: "Khóa màn hình quản trị", exact: true })).toBeVisible(); expect(users).toBe(1);
  await people.focus(); await people.press("ArrowRight"); await expect(stats).toBeFocused(); await expect(stats).toHaveAttribute("aria-selected", "true"); await expect(page.getByRole("tabpanel", { name: "Tố giác", exact: true })).toBeVisible(); await expect(page.getByLabel("Mã xác thực quản trị", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Thống kê tố giác", { exact: true })).toBeVisible(); expect(analytics).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: `.redesign-artifacts/admin-workspace-20261116/${info.project.name}.png` });
  await stats.press("Home"); await expect(people).toBeFocused(); await expect(page.getByLabel("Mã xác thực quản trị", { exact: true })).toHaveValue(""); await expect(page.getByRole("button", { name: "Khóa màn hình quản trị", exact: true })).toHaveCount(0); expect(users).toBe(1); expect(stepUps).toBe(1);
});
