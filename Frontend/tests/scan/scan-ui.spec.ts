import { expect, test } from "@playwright/test";

// Explicit transport fixtures exercise the real UI/adapter, not model accuracy or backend acceptance.
const snapshot = (type = "TEXT", level = "HIGH", aiAvailable = false) => ({
  scanId: "00000000-0000-4000-8000-000000000099", type, targetId: null,
  preview: "Masked fixture", risk: { score: level === "LOW" ? 0 : 80, level },
  textModel: null, urlModel: null,
  rules: { score: 80, signals: ["SENSITIVE_CREDENTIAL_REQUEST"] }, urls: [],
  aiAvailable, degraded: !aiAvailable, warnings: ["EMBEDDED_URL_LIMIT"],
  scannedAt: "2026-11-12T00:00:00Z"
});

test.beforeEach(async ({ page }, info) => {
  await page.route("**/api/**", route => route.fulfill({ status: 401, json: { status: 401 } }));
  await page.route("**/downloads/android.json", route => route.fulfill({ status: 404 }));
  await page.goto("/");
  await expect(page.getByText("Kiểm tra lừa đảo", { exact: true })).toBeVisible();
  await page.getByLabel("Tùy chọn ứng dụng").click();
  const toggle = page.getByLabel(info.project.name.endsWith("dark") ? "Chuyển sang giao diện tối" : "Chuyển sang giao diện sáng");
  if (await toggle.isVisible()) await toggle.click();
  await page.getByLabel("Đóng tùy chọn", { exact: true }).click();
});

test("SMS/URL journey, drafts, evidence, retries and responsive states", async ({ page }, info) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const input = page.getByPlaceholder("Dán nội dung tin nhắn");
  const submit = page.getByRole("button", { name: "Kiểm tra ngay", exact: true });
  await expect(submit).toBeDisabled();
  await expect(page.getByText("Kết quả sẽ hiển thị tại đây", { exact: true })).toBeVisible();
  await page.waitForTimeout(250); // Let the existing shell opacity transition finish before visual capture.
  await page.screenshot({ path: `.redesign-artifacts/scan-workbench-20261112/${info.project.name}-empty.png` });
  await input.fill("Nội dung SMS cần kiểm tra");
  await page.getByRole("tab", { name: "Đường dẫn", exact: true }).click();
  const url = page.getByPlaceholder("https://example.com");
  await url.fill("https://example.com/check");
  await page.getByRole("tab", { name: "Tin nhắn", exact: true }).click();
  await expect(input).toHaveValue("Nội dung SMS cần kiểm tra");
  let fail = true;
  await page.route("**/api/v1/scans/text", async route => {
    expect(route.request().postDataJSON()).toEqual({ content: "Nội dung SMS cần kiểm tra" });
    await route.fulfill(fail ? { status: 503, json: { status: 503 } } : { json: snapshot() });
  });
  await submit.click();
  await expect(page.getByText("Chưa thể kiểm tra", { exact: true })).toBeVisible();
  await expect(input).toHaveValue("Nội dung SMS cần kiểm tra");
  await expect(page.getByTestId("scan-result-region")).toBeFocused();
  fail = false;
  await page.getByRole("button", { name: "Thử lại", exact: true }).click();
  await expect(page.getByText("Phát hiện tin nhắn có dấu hiệu lừa đảo", { exact: true })).toBeVisible();
  await expect(page.getByText("Nội dung nhắc đến OTP, mật khẩu hoặc mã xác thực", { exact: true })).toBeVisible();
  await expect(page.getByText("Phân tích AI chưa sẵn sàng", { exact: true })).toBeVisible();
  await expect(page.getByTestId("scan-result-region").getByRole("button", { name: "Mở theo dõi", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("scan-result-region")).toBeFocused();
  if ((page.viewportSize()?.width ?? 0) < 1080) {
    expect((await page.getByTestId("scan-result-region").boundingBox())!.y).toBeGreaterThan((await page.getByTestId("scan-composer").boundingBox())!.y);
  }
  await page.waitForTimeout(250); // Let the existing shell opacity transition finish before visual capture.
  await page.screenshot({ path: `.redesign-artifacts/scan-workbench-20261112/${info.project.name}-result.png` });
  await page.getByRole("button", { name: "Xem chi tiết phân tích", exact: true }).click();
  await expect(page.getByText("Điểm rủi ro: 80 / 100", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Ẩn chi tiết phân tích", exact: true }).click();
  await page.getByRole("tab", { name: "Đường dẫn", exact: true }).click();
  await expect(url).toHaveValue("https://example.com/check");
  await expect(page.getByText("Kết quả kiểm tra", { exact: true })).toHaveCount(0);
  await page.route("**/api/v1/scans/url", route => {
    expect(route.request().postDataJSON()).toEqual({ url: "https://example.com/check" });
    return route.fulfill({ json: snapshot("URL", "LOW", true) });
  });
  await url.press("Enter");
  await expect(page.getByText("Chưa phát hiện dấu hiệu lừa đảo", { exact: true })).toBeVisible();
  await expect(page.getByText("Mức rủi ro: thấp", { exact: true })).toBeVisible();
  await expect(page.getByTestId("scan-result-region").getByRole("button", { name: "Mở theo dõi", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole("button", { name: "Tố giác", exact: true }).click();
  await expect(page.getByText("Đăng nhập BaoTin", { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test("validation limits and obsolete requests do not produce results", async ({ page }) => {
  const input = page.getByPlaceholder("Dán nội dung tin nhắn");
  await input.fill("x".repeat(10001));
  await expect(page.getByText("Nhập tối đa 10.000 ký tự.", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Kiểm tra ngay", exact: true })).toBeDisabled();
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/v1/scans/text", async route => {
    await gate;
    await route.fulfill({ json: snapshot() }).catch(() => undefined);
  });
  await input.fill("Nội dung cũ");
  await page.getByRole("button", { name: "Kiểm tra ngay", exact: true }).click();
  await expect(page.getByText("Đang kiểm tra", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Đang phân tích", exact: true })).toBeDisabled();
  await input.fill("Nội dung mới");
  release();
  await expect(page.getByText("Kết quả sẽ hiển thị tại đây", { exact: true })).toBeVisible();
  await expect(page.getByText("Phát hiện tin nhắn có dấu hiệu lừa đảo", { exact: true })).toHaveCount(0);
  await page.getByRole("tab", { name: "Đường dẫn", exact: true }).click();
  await page.getByPlaceholder("https://example.com").fill("x".repeat(4097));
  await expect(page.getByRole("button", { name: "Kiểm tra ngay", exact: true })).toBeDisabled();
});

test("all canonical risk levels retain their labels without inventing evidence", async ({ page }) => {
  let level = "LOW";
  await page.route("**/api/v1/scans/text", route => route.fulfill({ json: {
    ...snapshot("TEXT", level, true), rules: { score: 0, signals: [] }, warnings: []
  } }));
  for (const [value, label] of [["LOW", "thấp"], ["MEDIUM", "trung bình"], ["HIGH", "cao"], ["CRITICAL", "rất cao"]]) {
    level = value!;
    await page.getByPlaceholder("Dán nội dung tin nhắn").fill(`Fixture ${value}`);
    await page.getByRole("button", { name: "Kiểm tra ngay", exact: true }).click();
    await expect(page.getByText(`Mức rủi ro: ${label}`, { exact: true })).toBeVisible();
    await expect(page.getByText("Dấu hiệu ghi nhận", { exact: true })).toHaveCount(0);
    await expect(page.getByText("Phân tích AI chưa sẵn sàng", { exact: true })).toHaveCount(0);
  }
});
