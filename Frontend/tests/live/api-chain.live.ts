import { openNavigation } from "../navigation/helpers";
import { expect, test } from "@playwright/test";

test.skip(!process.env.BAOTIN_LIVE_CHAIN_BASE_URL, "Start the disposable real API-chain harness first");

test("real Web Auth, watchlist, scan/history and report evidence traverse Gateway", async ({ page }, info) => {
  const requests: string[] = [];
  page.on("request", request => requests.push(new URL(request.url()).pathname));
  await page.goto("/");
  await page.getByLabel("Đăng nhập hoặc đăng ký").click();
  await expect(page.getByRole("tab", { name: "Quản trị viên" })).toHaveCount(0);
  await page.getByLabel("Email, số điện thoại hoặc username").fill("fixture_owner");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("TEST-FIXTURE-ONLY-database-password-32-characters");
  const login = page.waitForResponse(r => r.url().endsWith("/api/v1/auth/web/login") && r.request().method() === "POST");
  await page.getByText("Đăng nhập", { exact: true }).last().click();
  expect((await login).status()).toBe(200);
  await expect(page.getByLabel("Tài khoản của Fixture owner")).toBeVisible();

  await openNavigation(page, "Theo dõi");
  await page.getByRole("button", { name: "Thêm theo dõi", exact: true }).click();
  const url = `https://browser.example/?view=${info.project.name}`;
  await page.getByLabel("URL cần theo dõi").fill(url);
  const added = page.waitForResponse(r => new URL(r.url()).pathname === "/api/v1/watchlist" && r.request().method() === "POST");
  await page.getByRole("button", { name: "Lưu theo dõi", exact: true }).click();
  expect((await added).status()).toBe(201);
  await expect(page.getByText(url, { exact: true }).first()).toBeVisible();

  await page.getByText("Kiểm tra", { exact: true }).last().click();
  await page.getByRole("tab", { name: "Đường dẫn", exact: true }).click();
  await page.getByPlaceholder("https://example.com").fill(url);
  const scanned = page.waitForResponse(r => r.url().endsWith("/api/v1/scans/url") && r.request().method() === "POST");
  await page.getByRole("button", { name: "Kiểm tra ngay", exact: true }).click();
  expect((await scanned).status()).toBe(200);
  await expect(page.getByText("Kết quả kiểm tra", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Tố giác", exact: true }).click();
  await expect(page.getByLabel("Đối tượng bị báo cáo")).toHaveValue(url);
  await page.getByLabel("Tiêu đề báo cáo").fill("Real browser integration " + info.project.name);
  await page.getByLabel("Mô tả báo cáo").fill("Controlled integration fixture; no customer content or credentials.");
  const reported = page.waitForResponse(r => new URL(r.url()).pathname === "/api/v1/reports" && r.request().method() === "POST");
  await page.getByRole("button", { name: "Gửi báo cáo", exact: true }).last().click();
  expect((await reported).status()).toBe(201);
  await expect(page.getByText("Đã gửi báo cáo. Bạn có thể thêm bằng chứng bên dưới.")).toBeVisible();

  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "Thêm bằng chứng", exact: true }).click();
  const uploaded = page.waitForResponse(r => r.url().endsWith("/evidence") && r.request().method() === "POST");
  await (await chooser).setFiles({ name: "fixture.png", mimeType: "image/png", buffer: Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 1]) });
  expect((await uploaded).status()).toBe(201);
  await expect(page.getByText("Bằng chứng (1/5)", { exact: true })).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Tải bằng chứng", exact: true }).click();
  expect((await download).suggestedFilename()).toBe("fixture.png");
  await page.getByRole("button", { name: "Xóa", exact: true }).click();
  const deleted = page.waitForResponse(r => r.url().includes("/evidence/") && r.request().method() === "DELETE");
  await page.getByRole("button", { name: "Xác nhận xóa", exact: true }).click();
  expect((await deleted).status()).toBe(204);

  const history = page.waitForResponse(r => new URL(r.url()).pathname === "/api/v1/scans/history" && r.request().method() === "GET");
  await page.getByText("Lịch sử", { exact: true }).last().click();
  expect((await history).status()).toBe(200);
  await expect(page.getByRole("button", { name: "Xem kết quả lúc quét", exact: true }).first()).toBeVisible();
  expect(requests.some(path => path.startsWith("/internal/"))).toBe(false);
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);

  await page.getByLabel("Tài khoản của Fixture owner").click();
  const logout = page.waitForResponse(r => r.url().endsWith("/api/v1/auth/web/logout") && r.request().method() === "POST");
  await page.getByRole("button", { name: "Đăng xuất", exact: true }).click();
  expect((await logout).status()).toBe(204);
  await expect(page.getByLabel("Đăng nhập hoặc đăng ký")).toBeVisible();
});
