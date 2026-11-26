import { expect, test } from "@playwright/test";

test("website scans text and URL through the real Gateway and protects history", async ({ page }, info) => {
  const outbound: string[] = [];
  page.on("request", (request) => outbound.push(request.url()));
  await page.goto("/");
  const input = page.getByPlaceholder("Dán nội dung tin nhắn");
  const content = "Tài khoản sắp bị khóa, nhập OTP tại https://example.test/login?token=demo-secret và https://example.test/login#fragment";
  await input.fill(content);
  const textResponse = page.waitForResponse((response) => response.url().endsWith("/api/v1/scans/text") && response.request().method() === "POST");
  await page.getByRole("button", { name: "Kiểm tra ngay", exact: true }).click();
  const response = await textResponse;
  expect(response.status()).toBe(200);
  expect(response.request().postDataJSON()).toEqual({ content });
  const text = await response.json();
  expect(text.scanId).toMatch(/^[0-9a-f-]{36}$/);
  expect(text.type).toBe("TEXT"); expect(text.targetId).toBeNull(); expect(text.urls).toHaveLength(1);
  expect(text.urls[0].url).toBe("https://example.test/login");
  expect(JSON.stringify(text)).not.toContain("demo-secret");
  await expect(page.getByText("Kết quả kiểm tra", { exact: true })).toBeVisible();
  if (!text.aiAvailable) await expect(page.getByText("Phân tích AI chưa sẵn sàng", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Xem chi tiết phân tích" }).click();
  await expect(page.getByText("Nội dung nhắc đến OTP, mật khẩu hoặc mã xác thực", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  await page.screenshot({ path: info.outputPath("text-light.png"), fullPage: true });

  await page.getByRole("tab", { name: "Đường dẫn", exact: true }).click();
  await page.getByPlaceholder("https://example.com").fill("https://EXAMPLE.test:443/login?utm_source=demo&token=demo-secret#fragment");
  const urlResponse = page.waitForResponse((response) => response.url().endsWith("/api/v1/scans/url") && response.request().method() === "POST");
  await page.getByRole("button", { name: "Kiểm tra ngay", exact: true }).click();
  const url = await urlResponse; expect(url.status()).toBe(200);
  const result = await url.json(); expect(result.type).toBe("URL"); expect(result.urls[0].url).toBe("https://example.test/login");
  await expect(page.getByText("Kết quả kiểm tra", { exact: true })).toBeVisible();
  const history = await page.request.get("/api/v1/scans/history"); expect(history.status()).toBe(401);
  expect(outbound.some((address) => address.includes("/internal/"))).toBe(false);
  await page.getByLabel("Tùy chọn ứng dụng").click();
  await page.getByLabel("Chuyển sang giao diện tối").click();
  await page.getByLabel("Đóng tùy chọn",{exact:true}).click();
  await expect(page.locator("html")).toHaveClass(/dark/);
  // Capture the settled theme, not an intermediate color during its 150 ms transition.
  await expect(page.getByRole("button", { name: "Tố giác", exact: true })).toHaveCSS("background-color", "rgb(17, 27, 23)");
  if (result.risk.level === "LOW") await expect(page.getByText("Thấp", { exact: true })).toHaveCSS("color", "rgb(111, 196, 143)");
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  await page.screenshot({ path: info.outputPath("url-dark.png"), fullPage: true });
});
