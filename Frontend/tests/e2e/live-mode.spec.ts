import { expect, test } from "@playwright/test";

test("live configuration exposes real Auth and public search without demo data", async ({ page }) => {
  test.skip(!process.env.BAOTIN_TEST_LIVE_MODE, "Live-mode export is tested in its own explicit build");
  await page.route("**/api/v1/auth/web/refresh", route => route.fulfill({ status: 401, contentType: "application/problem+json", body: JSON.stringify({ status: 401, title: "No test session" }) }));
  await page.goto("/");
  await page.getByText("Tra cứu", { exact: true }).last().click();
  await expect(page.getByLabel("Đối tượng cần tra cứu")).toBeVisible();
  await expect(page.getByText("Dữ liệu minh họa", { exact: true })).toHaveCount(0);
  await page.getByLabel("Đăng nhập hoặc đăng ký").click();
  await expect(page.getByRole("tab", { name: "Quản trị viên", exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Email, số điện thoại hoặc username")).toBeVisible();
});
