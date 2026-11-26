import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  test.skip(!process.env.BAOTIN_TEST_LIVE_MODE, "Requires explicit live-mode export");
  await page.route("**/api/v1/auth/web/refresh", route => route.fulfill({ status: 401, contentType: "application/problem+json", body: JSON.stringify({ status: 401, title: "No session" }) }));
  await page.goto("/");
  await page.getByText("Tra cứu", { exact: true }).last().click();
  await expect(page.getByLabel("Đối tượng cần tra cứu")).toBeVisible();
});

test("guest search filters, pages, unknown score and narrow layouts", async ({ page }, info) => {
  const requests: URL[] = [];
  await page.route("**/api/v1/search?**", async route => {
    const url = new URL(route.request().url()); requests.push(url);
    expect(route.request().headers()["authorization"]).toBeUndefined();
    const second = url.searchParams.get("page") === "1";
    await route.fulfill({ contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: JSON.stringify({
      page: second ? 1 : 0, size: 20, totalItems: 21, totalPages: 2,
      items: [{ id: "00000000-0000-4000-8000-000000000001", referenceId: "00000000-0000-4000-8000-000000000001", documentType: "URL", title: "example.com", displayValue: second ? "second.example.com" : "example.com", riskScore: second ? null : 84, riskLevel: second ? null : "CRITICAL", status: "ACTIVE", metadata: {}, updatedAt: "2026-11-11T00:00:00Z" }]
    }) });
  });
  await page.getByRole("tab", { name: "Đường dẫn", exact: true }).click();
  await page.getByLabel("Đối tượng cần tra cứu").fill("example.com");
  await expect(page.getByText("84/100", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Trang sau", exact: true }).click();
  await expect(page.getByText("Chưa có đánh giá", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "Cao", exact: true }).click();
  await expect(page.getByText("84/100", { exact: true })).toBeVisible();
  expect(requests.at(-1)?.searchParams.get("riskLevel")).toBe("HIGH");
  expect(requests.at(-1)?.searchParams.get("page")).toBe("0");
  expect(requests.at(-1)?.searchParams.get("type")).toBe("URL");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: `../.changes/search-20261111/search-${info.project.name}.png`, fullPage: true });
});

test("empty and failed searches never become demo results; retry works", async ({ page }) => {
  let fail = true;
  await page.route("**/api/v1/search?**", route => route.fulfill({ status: fail ? 503 : 200,
    contentType: fail ? "application/problem+json" : "application/json", headers: { "access-control-allow-origin": "*" },
    body: JSON.stringify(fail ? { status: 503, title: "Unavailable" } : { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }) }));
  await page.getByLabel("Đối tượng cần tra cứu").fill("example.com");
  await expect(page.getByText("Tra cứu đang gián đoạn", { exact: true })).toBeVisible();
  fail = false;
  await page.getByRole("button", { name: "Thử lại", exact: true }).click();
  await expect(page.getByText("Chưa tìm thấy kết quả", { exact: true })).toBeVisible();
  await expect(page.getByText("Dữ liệu minh họa", { exact: true })).toHaveCount(0);
  await page.getByLabel("Đối tượng cần tra cứu").fill("");
  await expect(page.getByText("Bạn muốn tra cứu gì?", { exact: true })).toBeVisible();
});

test("campaign and masked contact filters use the live contract without exposing private values", async ({ page }) => {
  const requests: URL[] = [];
  await page.route("**/api/v1/search?**", route => {
    const url = new URL(route.request().url()); requests.push(url);
    const campaign = url.searchParams.get("type") === "CAMPAIGN";
    return route.fulfill({ contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: JSON.stringify({
      page: 0, size: 20, totalItems: 1, totalPages: 1, items: [{
        id: "00000000-0000-4000-8000-000000000001", referenceId: "00000000-0000-4000-8000-000000000002",
        documentType: campaign ? "CAMPAIGN" : "PHONE", title: campaign ? "Chiến dịch mạo danh tuyển dụng" : "Điện thoại",
        displayValue: campaign ? "Cảnh báo tuyển dụng giả mạo" : "***1234", riskScore: null, riskLevel: null,
        status: "ACTIVE", metadata: {}, updatedAt: "2026-11-12T00:00:00Z"
      }]
    }) });
  });
  await page.getByRole("tab", { name: "Chiến dịch", exact: true }).click();
  await page.getByLabel("Đối tượng cần tra cứu").fill("tuyển dụng");
  await expect(page.getByText("Chiến dịch mạo danh tuyển dụng", { exact: true })).toBeVisible();
  expect(requests.at(-1)?.searchParams.get("type")).toBe("CAMPAIGN");
  await page.getByRole("tab", { name: "Điện thoại", exact: true }).click();
  await page.getByLabel("Đối tượng cần tra cứu").fill("0901231234");
  await expect(page.getByText("***1234", { exact: true })).toBeVisible();
  await expect(page.getByText("0901231234", { exact: true })).toHaveCount(0);
  expect(requests.at(-1)?.searchParams.get("type")).toBe("PHONE");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});


test("guest reads full masked warning wording without source metadata", async ({ page }) => {
  const content = "Cảnh báo khóa tài khoản! Nhập OTP ***; liên hệ ***. " + "Nội dung cảnh báo tương tự. ".repeat(20) + "Dấu cuối nội dung.";
  await page.route("**/api/v1/search?**", route => route.fulfill({ contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: JSON.stringify({
    page: 0, size: 20, totalItems: 1, totalPages: 1, items: [{
      id: "00000000-0000-4000-8000-000000000001", referenceId: "00000000-0000-4000-8000-000000000001",
      documentType: "OTHER", title: content.slice(0,200), displayValue: content.slice(0,253), content,
      riskScore: null, riskLevel: null, status: "ACTIVE", metadata: { contentWarning: "SUSPICIOUS", referenceType: "MESSAGE_TEMPLATE" }, updatedAt: "2026-11-12T00:00:00Z"
    }]
  }) }));
  await page.getByLabel("Đối tượng cần tra cứu").fill("khóa tài khoản");
  await expect(page.getByText(content, { exact: true })).toBeVisible();
  await expect(page.getByText("Có rủi ro", { exact: true })).toBeVisible();
  await expect(page.getByText("Chưa có điểm rủi ro tổng hợp", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Chưa có đánh giá", { exact: true })).toHaveCount(0);
});

test("benign dataset label is visible and distinct from an absent aggregate score", async ({ page }) => {
  await page.route("**/api/v1/search?**", route => route.fulfill({ contentType: "application/json", body: JSON.stringify({page:0,size:20,totalItems:1,totalPages:1,items:[{id:"00000000-0000-4000-8000-000000000001",referenceId:"00000000-0000-4000-8000-000000000001",documentType:"URL",title:"normal.example.com",displayValue:"normal.example.com",status:"ACTIVE",metadata:{contentWarning:"BENIGN"},riskScore:null,riskLevel:null,updatedAt:"2026-11-12T00:00:00Z"}]}) }));
  await page.getByLabel("Đối tượng cần tra cứu").fill("normal");
  await expect(page.getByText("Không có rủi ro",{exact:true})).toBeVisible();
  await expect(page.getByText("Chưa có điểm rủi ro tổng hợp",{exact:true})).toHaveCount(0);
  await expect(page.getByText("Chưa có đánh giá",{exact:true})).toHaveCount(0);
});
