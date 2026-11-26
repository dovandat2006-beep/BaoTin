import { test, expect } from "@playwright/test";
import { openNavigation } from "../navigation/helpers";

test("review-first statistics distinguish unknown, stale and refreshed counts without writes", async ({ page }, info) => {
  let fail = true, pending = false, count = 17, writes = 0, reads = 0;
  await page.route("**/api/v1/**", async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    let body: unknown = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, unreadCount: 0 };
    if (path.endsWith("/web/refresh")) return route.fulfill({ status: 401, json: { status: 401 } });
    if (path.endsWith("/web/login")) body = { accessToken: "fixture", csrfToken: "fixture" };
    else if (path.endsWith("/auth/me")) body = { id: "00000000-0000-4000-8000-000000000001", username: "fixture", displayName: "Admin Fixture", role: "ADMIN", emailVerified: true, mfaEnabled: true, mfaMethod: "TOTP" };
    else if (path.endsWith("/moderation/reports")) {
      reads++; if (req.method() !== "GET") writes++;
      if (pending) await new Promise(resolve => setTimeout(resolve, 350));
      if (fail) return route.fulfill({ status: 503, json: { status: 503, detail: "Fixture counts unavailable" } });
      body = { items: [], page: 0, size: 20, totalItems: count, totalPages: 1 };
    }
    return route.fulfill({ json: body });
  });
  await page.goto("/"); await page.getByLabel("Tùy chọn ứng dụng").click(); const toggle = page.getByLabel(info.project.name.endsWith("dark") ? "Chuyển sang giao diện tối" : "Chuyển sang giao diện sáng"); if (await toggle.isVisible()) await toggle.click(); await page.getByLabel("Đóng tùy chọn", { exact: true }).click(); expect(await page.locator("html").evaluate(el => el.classList.contains("dark"))).toBe(info.project.name.endsWith("dark"));
  await page.getByLabel("Đăng nhập hoặc đăng ký").click(); await page.getByLabel("Email, số điện thoại hoặc username", { exact: true }).fill("fixture"); await page.getByLabel("Mật khẩu", { exact: true }).fill("fixture-password"); await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await expect(page.getByLabel("Tài khoản của Admin Fixture")).toBeVisible(); await openNavigation(page, "Quản trị"); await page.getByRole("tab", { name: "Tố giác", exact: true }).click();
  const screen = page.getByTestId("admin-moderation"), toggleStats = screen.getByRole("button", { name: /^(Ẩn thống kê tố giác|Thống kê tố giác)$/ });
  await expect(toggleStats).toHaveAttribute("aria-expanded", "false"); await expect(screen.getByText("Kiểm duyệt báo cáo", { exact: true })).toBeVisible(); await toggleStats.press("Enter"); await expect(toggleStats).toHaveAttribute("aria-expanded", "true");
  const counts = page.locator("#moderation-counts");
  await expect(counts.getByText("Chưa cập nhật được thống kê", { exact: true })).toBeVisible(); await expect(counts.getByLabel("Đã gửi: 0", { exact: true })).toHaveCount(0);
  fail = false; pending = true;
  const refresh = screen.getByRole("button", { name: "Làm mới thống kê tố giác", exact: true }); await refresh.click(); await expect(refresh).toBeDisabled(); await expect(counts.getByLabel("Đang tải thống kê tố giác", { exact: true })).toBeVisible(); await expect(counts.getByLabel("Đã gửi: 17", { exact: true })).toBeVisible();
  for (const label of ["Đã gửi", "Đang duyệt", "Cần bổ sung", "Đã xác minh", "Không chấp nhận"]) await expect(counts.getByLabel(`${label}: 17`, { exact: true })).toBeVisible();
  fail = true; await refresh.click(); await expect(counts.getByText("Chưa cập nhật được thống kê", { exact: true })).toBeVisible(); await expect(counts.getByText("Số liệu dưới đây là lần tải thành công trước đó, chưa phải bản cập nhật mới nhất.", { exact: true })).toBeVisible(); await expect(counts.getByLabel("Đã gửi: 17", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: `.redesign-artifacts/admin-moderation-20261116/${info.project.name}-stale.png` });
  fail = false; count = 9; await refresh.click(); await expect(counts.getByLabel("Đã gửi: 9", { exact: true })).toBeVisible(); await expect(counts.getByText("Chưa cập nhật được thống kê", { exact: true })).toHaveCount(0);
  await toggleStats.press("Enter"); await expect(toggleStats).toHaveAttribute("aria-expanded", "false"); await expect(counts).toHaveCount(0); expect(writes).toBe(0); expect(reads).toBeGreaterThanOrEqual(20);
  await page.screenshot({ path: `.redesign-artifacts/admin-moderation-20261116/${info.project.name}-queue.png` });
});
