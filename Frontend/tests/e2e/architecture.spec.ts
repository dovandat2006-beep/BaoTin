import { openNavigation } from "../navigation/helpers";
import { expect, test } from "@playwright/test";
import { signIn } from "./auth-helpers";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/scans/text", (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
    scanId: "test-scan", type: "TEXT", targetId: null, preview: "[Nội dung tin nhắn đã ẩn]", risk: { score: 80, level: "HIGH" },
    textModel: null, urlModel: null, rules: { score: 80, signals: ["SENSITIVE_CREDENTIAL_REQUEST"] }, urls: [],
    aiAvailable: false, degraded: true, warnings: ["TEXT_AI_UNAVAILABLE"], scannedAt: "2026-10-22T00:00:00Z"
  }) }));
});

const routes = [
  ["Tra cứu", "Tra cứu"],
  ["Lịch sử", "Lịch sử kiểm tra"],
  ["Báo cáo", "Tố giác của tôi"],
  ["Theo dõi", "Theo dõi"],
  ["Kiểm tra", "Kiểm tra lừa đảo"],
  ["Quản trị", "Hàng chờ kiểm duyệt"]
] as const;

test("all architecture surfaces remain responsive", async ({ page }) => {
  await page.goto("/");
  await signIn(page, "ADMIN");
  for (const [nav, heading] of routes) {
    await openNavigation(page, nav);
    await expect(page.getByText(heading, { exact: true }).last()).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  }
});

test("scan, search, history, watchlist, report, and notification flows work", async ({ page }) => {
  await page.goto("/");
  await signIn(page);
  await page.getByPlaceholder("Dán nội dung tin nhắn").fill("Khẩn cấp: xác minh ngay và nhập OTP tại bit.ly/free-gift");
  await page.getByText("Kiểm tra ngay", { exact: true }).click();
  await expect(page.getByText("Kết quả kiểm tra", { exact: true })).toBeVisible();
  await expect(page.getByText("Phát hiện tin nhắn có dấu hiệu lừa đảo", { exact: true })).toBeVisible();
  await expect(page.getByText("Loại: Tin nhắn", { exact: true })).toBeVisible();
  if ((page.viewportSize()?.width ?? 0) < 1080) {
    const result = await page.getByText("Kết quả kiểm tra", { exact: true }).boundingBox();
    const input = await page.getByPlaceholder("Dán nội dung tin nhắn").boundingBox();
    expect(result!.y).toBeGreaterThan(input!.y);
  }
  await page.getByPlaceholder("Dán nội dung tin nhắn").fill("Nội dung tin nhắn mới");
  await expect(page.getByText("Kết quả kiểm tra", { exact: true })).toHaveCount(0);

  await page.getByText("Tra cứu", { exact: true }).last().click();
  await expect(page.getByText("Dữ liệu minh họa", { exact: true })).toBeVisible();
  await page.getByPlaceholder("Tên miền, số điện thoại, email...").fill("shop-deal");
  await expect(page.getByText("shop-deal-vn.live", { exact: true })).toBeVisible();

  await page.getByText("Lịch sử", { exact: true }).last().click();
  await expect(page.getByText("Cần tài khoản đã xác thực", { exact: true })).toBeVisible();
  await expect(page.getByText("quatang-vip-free-gift.site", { exact: true })).toHaveCount(0);

  await openNavigation(page, "Theo dõi");
  await page.getByLabel("Thay đổi điểm rủi ro").click();
  await page.getByRole("button", { name: /^Bỏ theo dõi / }).first().click();
  await page.getByRole("button", { name: "Xác nhận bỏ theo dõi", exact: true }).click();
  await expect(page.getByText("shop-deal-vn.live", { exact: true })).toHaveCount(0);

  await page.getByText("Báo cáo", { exact: true }).last().click();
  await page.getByText("Tố giác lừa đảo", { exact: true }).click();
  await page.getByLabel("Đối tượng bị báo cáo").fill("https://gia-mao.example");
  await page.getByText("Tiếp tục", { exact: true }).click();
  await page.getByLabel("Tiêu đề báo cáo").fill("Trang giả mạo mới");
  await page.getByLabel("Mô tả báo cáo").fill("Trang yêu cầu đăng nhập và mã xác minh tài khoản ngân hàng.");
  await page.getByText("Thêm file giả lập", { exact: true }).click();
  await page.getByText("Lưu bản nháp minh họa", { exact: true }).click();
  await expect(page.getByText("Đã tạo tố giác minh họa", { exact: true })).toBeVisible();
  await expect(page.getByText("Bản nháp minh họa", { exact: true })).toBeVisible();
  await expect(page.getByText("Trang giả mạo mới", { exact: true })).toBeVisible();

  await page.getByLabel("Thông báo, 2 chưa đọc").click();
  await page.getByLabel("Đánh dấu tất cả đã đọc").click();
  await page.getByLabel("Đóng bảng thông báo", { exact: true }).click();
  await expect(page.getByLabel("Thông báo, 0 chưa đọc")).toBeVisible();
});

test("scan states explain validation, loading, failure, retry, and result", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Kết quả sẽ hiển thị tại đây", { exact: true })).toBeVisible();
  const input = page.getByPlaceholder("Dán nội dung tin nhắn");
  await input.fill(" ");
  await expect(input).toBeFocused();
  await expect(page.getByRole("button", { name: "Kiểm tra ngay" })).toBeDisabled();

  await page.route("**/api/v1/scans/text", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 200));
    await route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ status: 503, title: "Unavailable", code: "UPSTREAM_UNAVAILABLE" }) });
  }, { times: 1 });
  await input.fill("Nội dung quét khi mất kết nối");
  await page.getByRole("button", { name: "Kiểm tra ngay" }).click();
  await expect(page.getByText("Đang kiểm tra", { exact: true })).toBeVisible();
  await expect(page.getByText("Chưa kết nối được máy chủ. Kiểm tra kết nối và thử lại.", { exact: true })).toBeVisible();
  await input.fill("https://example.com/an-toan");
  await page.getByRole("button", { name: "Kiểm tra ngay" }).click();
  await expect(page.getByText("Kết quả kiểm tra", { exact: true })).toBeVisible();
});

test("login, registration, SSO, and protected navigation are understandable", async ({ page }) => {
  await page.goto("/");
  await page.getByText("Lịch sử", { exact: true }).last().click();
  await expect(page.getByText("Đăng nhập BaoTin", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Tiếp tục với Google")).toBeVisible();
  await expect(page.getByLabel("Tiếp tục với Facebook")).toBeVisible();
  await page.getByText("Đăng nhập", { exact: true }).last().click();
  await expect(page.getByText("Nhập email, số điện thoại hoặc username.", { exact: true })).toBeVisible();
  await expect(page.getByText("Nhập mật khẩu.", { exact: true })).toBeVisible();

  await page.getByText("Đăng ký", { exact: true }).first().click();
  await page.getByLabel("Username đăng ký").fill("an.nguyen");
  await page.getByLabel("Email hoặc số điện thoại đăng ký").fill("an@example.com");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("password123");
  await page.getByLabel("Xác nhận mật khẩu").fill("password123");
  await page.getByRole("checkbox").click();
  await page.getByText("Tạo tài khoản", { exact: true }).last().click();
  await expect(page.getByText("Lịch sử kiểm tra", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Tài khoản của an.nguyen")).toBeVisible();
});

test("mobile navigation is attached to the viewport edge", async ({ page }) => {
  test.skip((page.viewportSize()?.width ?? 0) >= 600, "Mobile navigation only");
  await page.goto("/");
  const tab = page.getByRole("tab", { name: "Kiểm tra" });
  await expect(tab).toBeVisible();
  const box = await tab.boundingBox();
  const viewport = page.viewportSize();
  expect(box && viewport ? Math.abs(viewport.height - (box.y + box.height)) : 999).toBeLessThan(12);
});

test("desktop sidebar collapses and expands", async ({ page }) => {
  test.skip((page.viewportSize()?.width ?? 0) < 1024, "Desktop navigation only");
  await page.goto("/");
  await expect(page.getByLabel("Mở tra cứu")).toHaveCount(0);
  const sidebar = page.getByTestId("sidebar");
  const selected = page.getByTestId("sidebar-scan");
  await expect(selected).toHaveAttribute("aria-current", "page");
  const railBox = await sidebar.boundingBox();
  const logoBox = await sidebar.getByRole("img", { name: "BaoTin", exact: true }).boundingBox();
  expect(logoBox!.x - railBox!.x).toBeGreaterThanOrEqual(20);
  const selectedBox = await selected.boundingBox();
  expect(selectedBox!.x - railBox!.x).toBeGreaterThanOrEqual(8);
  const headerBox = await page.getByTestId("sidebar-header").boundingBox();
  expect(headerBox!.x - railBox!.x).toBeLessThan(1);
  expect(Math.abs(headerBox!.width - railBox!.width)).toBeLessThanOrEqual(1);
  await page.getByLabel("Thu gọn thanh bên").click();
  await expect(page.getByLabel("Mở rộng thanh bên")).toBeVisible();
  await expect(sidebar).toHaveCSS("width", "80px");
  await page.getByLabel("Mở rộng thanh bên").click();
  await expect(page.getByText("Tra cứu", { exact: true })).toBeVisible();
});

test("dark mode toggles, persists, and keeps semantic surfaces", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Tùy chọn ứng dụng").click();
  const enableDark = page.getByLabel("Chuyển sang giao diện tối");
  if (!(await enableDark.isVisible())) await page.getByLabel("Chuyển sang giao diện sáng").click();
  await enableDark.click();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await expect(page.getByLabel("Chuyển sang giao diện sáng")).toBeVisible();
  await page.waitForTimeout(300);
  const colors = {
    body: await page.evaluate(() => getComputedStyle(document.body).backgroundColor),
    heading: await page.getByText("Kiểm tra lừa đảo", { exact: true }).evaluate((element) => getComputedStyle(element).color),
    primaryLabel: await page.getByText("Kiểm tra ngay", { exact: true }).evaluate((element) => getComputedStyle(element).color)
  };
  expect(colors.body).toBe("rgb(11, 18, 16)");
  expect(colors.heading).toBe("rgb(222, 228, 223)");
  expect(colors.primaryLabel).toBe("rgb(0, 55, 47)");
  await page.reload();
  await page.getByLabel("Tùy chọn ứng dụng").click();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await page.getByLabel("Chuyển sang giao diện sáng").click();
  await expect(page.locator("html")).not.toHaveClass(/dark/);
});
