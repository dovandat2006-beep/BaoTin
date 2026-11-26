import { openNavigation } from "../navigation/helpers";
import { expect, test } from "@playwright/test";
import { installAuthFixture, installReportFixture, installScanFixture } from "./api-fixtures";
import type { Report } from "../../src/api/reports";

async function login(page: import("@playwright/test").Page, role = "USER") {
  await installAuthFixture(page, role);await page.goto("/");
  await page.getByLabel("Đăng nhập hoặc đăng ký").click();
  await page.getByLabel("Email, số điện thoại hoặc username").fill("an.nguyen@example.com");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("password123");
  await page.getByText("Đăng nhập", { exact: true }).last().click();
  await expect(page.getByLabel("Tài khoản của an.nguyen")).toBeVisible();
  await page.getByText("Báo cáo", { exact: true }).last().click();
}
test("create report and upload real file through authenticated HTTP adapter", async ({ page }) => {
  await installReportFixture(page);await login(page);
  await expect(page.getByText("Chưa có báo cáo", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Gửi báo cáo", exact: true }).click();
  await page.getByLabel("Đối tượng bị báo cáo").fill("https://gia-mao.example");
  await page.getByLabel("Tiêu đề báo cáo").fill("Trang giả mạo mới");
  await page.getByLabel("Mô tả báo cáo").fill("Trang yêu cầu cung cấp mật khẩu và mã OTP ngân hàng.");
  await page.getByRole("button", { name: "Gửi báo cáo", exact: true }).last().click();
  await expect(page.getByText("Đã gửi báo cáo. Bạn có thể thêm bằng chứng bên dưới.")).toBeVisible();
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "Thêm bằng chứng", exact: true }).click();
  await (await chooser).setFiles({ name: "capture.png", mimeType: "image/png", buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jTz0AAAAASUVORK5CYII=", "base64") });
  await expect(page.getByText("capture.png", { exact: true })).toBeVisible();
  await expect(page.getByText("Bằng chứng (1/5)", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  await page.screenshot({ path: `test-results/reports-${page.viewportSize()?.width}.png`, fullPage: true });
});
test("moderator records review and verification with current version", async ({ page }) => {
  const report: Report = { id: "00000000-0000-0000-0000-000000000030", reporterUserId: "00000000-0000-0000-0000-000000000002", threatTargetId: "00000000-0000-0000-0000-000000000010", targetTypeSnapshot: "URL", targetDisplaySnapshot: "https://gia-mao.example/", category: "PHISHING_URL", title: "Báo cáo cần xác minh", description: "Trang yêu cầu mật khẩu và mã xác nhận của người dùng.", status: "SUBMITTED", priority: "MEDIUM", moderatorNote: null, version: 0, createdAt: "2026-10-22T01:00:00Z", updatedAt: "2026-10-22T01:00:00Z", evidence: [], history: [] };
  await installReportFixture(page, [report]);await login(page, "MODERATOR");
  await page.getByRole("tab", { name: "Kiểm duyệt", exact: true }).click();
  await page.getByRole("button", { name: "Xem báo cáo Báo cáo cần xác minh" }).click();
  await page.getByRole("button", { name: "Bắt đầu kiểm duyệt" }).click();
  await expect(page.getByLabel("Lý do quyết định")).toBeVisible();
  await expect(page.getByRole("button", { name: "Xác minh", exact: true })).toBeDisabled();
  await page.getByLabel("Lý do quyết định").fill("Đã kiểm tra thông tin và đối chiếu bằng chứng.");
  await page.getByRole("button", { name: "Xác minh", exact: true }).click();
  await expect(page.getByText("Đã xác minh", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Xác minh", exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
});

test("report action carries message input into the real report form", async ({ page }) => {
  const scanId = await installScanFixture(page);
  await installReportFixture(page);
  await login(page);
  await page.getByText("Kiểm tra", { exact: true }).last().click();
  const message = "Khẩn cấp: xác minh ngay và nhập OTP tại https://gia-mao.example";
  await page.getByPlaceholder("Dán nội dung tin nhắn").fill(message);
  await page.getByText("Kiểm tra ngay", { exact: true }).click();
  await page.getByRole("button", { name: "Tố giác", exact: true }).click();
  await expect(page.getByLabel("Đối tượng bị báo cáo")).toHaveValue(message);
  await page.getByLabel("Tiêu đề báo cáo").fill("Tin nhắn yêu cầu mã OTP");
  await page.getByLabel("Mô tả báo cáo").fill("Người gửi yêu cầu cung cấp mã OTP cho một trang giả mạo.");
  const created = page.waitForRequest(request => request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/reports");
  await page.getByRole("button", { name: "Gửi báo cáo", exact: true }).last().click();
  expect((await created).postDataJSON().scanId).toBe(scanId);
  await expect(page.getByText("Tin nhắn yêu cầu mã OTP", { exact: true }).first()).toBeVisible();
});

test("guest scan can become a report after login without claiming an owned source", async ({ page }) => {
  await installAuthFixture(page);
  await installReportFixture(page);
  await installScanFixture(page);
  await page.goto("/");
  const message = "Guest message asks for bank OTP at https://phishing.example/";
  await page.getByPlaceholder("Dán nội dung tin nhắn").fill(message);
  await page.getByText("Kiểm tra ngay", { exact: true }).click();
  await page.getByRole("button", { name: "Tố giác", exact: true }).click();
  await page.getByLabel("Email, số điện thoại hoặc username").fill("an.nguyen@example.com");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("password123");
  await page.getByText("Đăng nhập", { exact: true }).last().click();
  await expect(page.getByLabel("Đối tượng bị báo cáo")).toHaveValue(message);
  await page.getByLabel("Tiêu đề báo cáo").fill("Guest scan report");
  await page.getByLabel("Mô tả báo cáo").fill("The message requests banking credentials and an OTP.");
  const created = page.waitForRequest(request => request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/reports");
  await page.getByRole("button", { name: "Gửi báo cáo", exact: true }).last().click();
  expect((await created).postDataJSON()).not.toHaveProperty("scanId");
});


test("admin statistics reads live report totals and opens actionable reject workflow", async ({ page }) => {
  const report: Report = { id:"00000000-0000-4000-8000-000000000090",reporterUserId:"00000000-0000-4000-8000-000000000002",threatTargetId:"00000000-0000-4000-8000-000000000010",targetTypeSnapshot:"URL",targetDisplaySnapshot:"https://fixture.example/",category:"OTHER",title:"Tố giác mới cần xử lý",description:"Synthetic moderation workflow, no real private data.",status:"SUBMITTED",priority:"MEDIUM",moderatorNote:null,version:0,createdAt:"2026-11-12T00:00:00Z",updatedAt:"2026-11-12T00:00:00Z",evidence:[],history:[] };
  await installReportFixture(page,[report]);await login(page,"ADMIN");
  await openNavigation(page, "Quản trị");await page.getByRole("tab",{name:"Tố giác",exact:true}).click();
  await page.getByRole("button",{name:"Thống kê tố giác",exact:true}).click();await expect(page.getByText("Theo trạng thái",{exact:true})).toBeVisible();await expect(page.getByLabel("Đã gửi: 1",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Xem báo cáo Tố giác mới cần xử lý",exact:true}).click();await expect(page.getByText("Duyệt báo cáo",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Bắt đầu kiểm duyệt",exact:true}).click();await page.getByLabel("Lý do quyết định",{exact:true}).fill("Synthetic test rejected after reviewing the report.");
  await page.getByRole("button",{name:"Từ chối",exact:true}).click();await expect(page.getByText("Không chấp nhận",{exact:true}).first()).toBeVisible();
  await page.getByRole("button",{name:"Đóng Duyệt báo cáo",exact:true}).click();await expect(page.getByLabel("Không chấp nhận: 1",{exact:true})).toBeVisible();
});
