import { test, expect } from "@playwright/test";

const preview = "http://127.0.0.1:4194", gateway = "https://baotin.rimiprod.me";
test("linking reports pending, cancellation and service success without replacing identity", async ({ page, context }, info) => {
  let cancelled = true, starts = 0, exchanges = 0;
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await context.route(gateway + "/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith("/api/")) return route.fulfill({ response: await context.request.get(preview + (path.startsWith("/auth/") ? "/" : path)) });
    let body: unknown = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, unreadCount: 0 };
    if (path.endsWith("/web/refresh")) return route.fulfill({ status: 401, json: { status: 401 } });
    if (path.endsWith("/web/login")) body = { accessToken: "fixture-access", csrfToken: "fixture-csrf", expiresIn: 300 };
    else if (path.endsWith("/auth/me")) body = { id: "00000000-0000-4000-8000-000000000001", username: "fixture", displayName: "Link Fixture", role: "USER", email: "fixture@example.test", emailVerified: true, mfaEnabled: true, mfaMethod: "TOTP" };
    else if (path.endsWith("/oauth2/link-requests")) {
      starts++; await new Promise(resolve => setTimeout(resolve, 400));
      const input = route.request().postDataJSON();
      expect(input.codeChallenge).toHaveLength(43);
      expect(input.transport).toBe("WEB");
      body = { authorizationUrl: input.provider === "GOOGLE" ? "https://accounts.google.com/o/oauth2/v2/auth?state=fixture" : "https://www.facebook.com/v22.0/dialog/oauth?state=fixture" };
    } else if (path.endsWith("/web/oauth2/tokens")) {
      exchanges++; const input = route.request().postDataJSON();
      expect(input.codeVerifier).toHaveLength(43); expect(input.code).toBe("a".repeat(43));
      body = { linked: true };
    }
    return route.fulfill({ json: body });
  });
  for (const provider of ["https://accounts.google.com/**", "https://www.facebook.com/**"]) await context.route(provider, route => route.fulfill({ contentType: "text/html", body: `<script>location.replace(${JSON.stringify(gateway + "/auth/callback?" + (cancelled ? "error=oauth_denied" : "code=" + "a".repeat(43)))})</script>` }));
  await page.goto(gateway + "/");
  await page.getByLabel("Tùy chọn ứng dụng").click();
  const toggle = page.getByLabel(info.project.name.endsWith("dark") ? "Chuyển sang giao diện tối" : "Chuyển sang giao diện sáng");
  if (await toggle.isVisible()) await toggle.click();
  await page.getByLabel("Đóng tùy chọn", { exact: true }).click();
  expect(await page.locator("html").evaluate(el => el.classList.contains("dark"))).toBe(info.project.name.endsWith("dark"));
  await page.getByLabel("Đăng nhập hoặc đăng ký").click();
  await page.getByLabel("Email, số điện thoại hoặc username", { exact: true }).fill("fixture");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("fixture-password");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await page.getByLabel("Tài khoản của Link Fixture").click();
  await page.getByRole("tab", { name: "Bảo mật", exact: true }).click();
  const panel = page.getByTestId("provider-links");
  await expect(panel.getByRole("heading", { name: "Liên kết đăng nhập" })).toBeVisible();
  await expect(panel.getByRole("button", { name: "Hủy yêu cầu liên kết", exact: true })).toHaveCount(0);
  await panel.getByRole("button", { name: "Liên kết Google", exact: true }).click();
  await expect(panel.getByRole("button", { name: "Liên kết Facebook", exact: true })).toBeDisabled();
  await expect(panel.getByText(/Đang chờ Google/)).toBeVisible();
  await expect(panel.getByText("Chưa hoàn tất liên kết", { exact: true })).toBeVisible();
  await expect(page.getByTestId("provider-link-feedback")).toBeFocused();
  expect(exchanges).toBe(0);
  await page.screenshot({ path: `.redesign-artifacts/provider-links-20261116/${info.project.name}-cancel.png` });
  cancelled = false;
  await panel.getByRole("button", { name: "Liên kết Facebook", exact: true }).click();
  await expect(panel.getByText(/Đang chờ Facebook/)).toBeVisible();
  await expect(panel.getByText("Đã liên kết Facebook với tài khoản BaoTin này.", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Tài khoản của Link Fixture")).toBeVisible();
  expect(starts).toBe(2); expect(exchanges).toBe(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(errors).toEqual([]);
  await page.screenshot({ path: `.redesign-artifacts/provider-links-20261116/${info.project.name}-linked.png` });
});
