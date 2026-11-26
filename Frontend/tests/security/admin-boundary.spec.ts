import { openNavigation } from "../navigation/helpers";
import { expect, test, type Page } from "@playwright/test";

// Explicit transport fixtures test UI state transitions, not production API acceptance.
test.describe("live-mode admin visibility and session invalidation", () => {

  async function fixture(page: Page, role: "USER" | "MODERATOR" | "ADMIN") {
    const state = { role, revoked: false, adminCalls: 0, expireToken: false, refreshValid: false };
    await page.route("**/api/v1/**", async route => {
      const path = new URL(route.request().url()).pathname;
      if (path.includes("/auth/admin/")) state.adminCalls++;
      const status = (path.endsWith("/web/refresh") && !state.refreshValid) || (path.endsWith("/auth/me") && (state.revoked || state.expireToken)) ? 401 : 200;
      if (path.endsWith("/web/refresh") && state.refreshValid) state.expireToken = false;
      const body = path.endsWith("/web/login") || path.endsWith("/web/refresh")
        ? { accessToken: "transport-fixture", csrfToken: "transport-fixture", tokenType: "Bearer", expiresIn: 300 }
        : path.endsWith("/auth/me")
          ? { id: "00000000-0000-4000-8000-000000000001", username: "admin", displayName: "admin", role: state.role, emailVerified: true, mfaEnabled: true }
          : path.endsWith("/preferences")
            ? { riskChangeEnabled: true, reportStatusEnabled: true, pushEnabled: false, inAppEnabled: true }
            : path.endsWith("/unread-count") ? { unreadCount: 0 }
                        : path.endsWith("/analytics/admin/summary")
              ? { pendingReview: 0, needMoreInfo: 0, resolvedToday: 0, highRisk: 0, scansToday: 0 }
              : path.endsWith("/analytics/admin/counts")
                ? { byStatus: { SUBMITTED: 0, PENDING_REVIEW: 0, NEED_MORE_INFO: 0, VERIFIED: 0, REJECTED: 0 }, byPriority: { LOW: 0, MEDIUM: 0, HIGH: 0 } }
                : { items: [], totalItems: 0, totalPages: 0, page: 0, size: 100 };
      await route.fulfill({ status, headers: { "Access-Control-Allow-Origin": "http://127.0.0.1:4189", "Access-Control-Allow-Credentials": "true" }, contentType: status === 401 ? "application/problem+json" : "application/json", body: JSON.stringify(status === 401 ? { status: 401, title: "Fixture revoked" } : body) });
    });
    return state;
  }

  async function login(page: Page) {
    await page.getByLabel("Đăng nhập hoặc đăng ký").click();
    await expect(page.getByRole("tab", { name: "Quản trị viên", exact: true })).toHaveCount(0);
    await page.getByLabel("Email, số điện thoại hoặc username").fill("admin@example.test");
    await page.getByLabel("Mật khẩu", { exact: true }).fill("fixture-password-only");
    await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
    await expect(page.getByLabel("Tài khoản của admin")).toBeVisible();
  }

  for (const role of ["USER", "MODERATOR"] as const) {
    test(`${role} and guests have no admin navigation or admin fetch`, async ({ page }) => {
      const state = await fixture(page, role);
      await page.goto("/#admin");
      await expect(page.getByText("Quản trị", { exact: true })).toHaveCount(0);
      await login(page);
      await expect(page.getByText("Quản trị", { exact: true })).toHaveCount(0);
      expect(state.adminCalls).toBe(0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
    });
  }

  for (const revoked of [false, true]) {
    test(`server ${revoked ? "revocation" : "demotion"} removes an already open admin screen`, async ({ page }) => {
      const state = await fixture(page, "ADMIN");
      await page.goto("/");
      await login(page);
      await openNavigation(page, "Quản trị");
      await expect(page.getByText("Quản lý người dùng", { exact: true })).toBeVisible();
      state.role = "USER";
      state.revoked = revoked;
      await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
      await expect(page.getByText("Quản trị", { exact: true })).toHaveCount(0);
      await expect(page.getByText("Quản lý người dùng", { exact: true })).toHaveCount(0);
      if (revoked) await expect(page.getByText("Đăng nhập BaoTin", { exact: true })).toBeVisible();
      expect(state.adminCalls).toBe(0);
    });
  }

  test("normal token expiry rotates and verifies the profile instead of logging out", async ({ page }) => {
    const state = await fixture(page, "ADMIN");
    await page.goto("/");
    await login(page);
    state.expireToken = true;
    state.refreshValid = true;
    const rotated = page.waitForResponse(response => response.url().endsWith("/auth/web/refresh") && response.status() === 200);
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
    await rotated;
    if (await page.getByRole("button", { name: "Thêm", exact: true }).isVisible()) await page.getByRole("button", { name: "Thêm", exact: true }).click();
    await expect(page.getByText("Quản trị", { exact: true })).toBeVisible();
    await expect(page.getByLabel("Tài khoản của admin")).toBeVisible();
    expect(state.adminCalls).toBe(0);
  });
});
