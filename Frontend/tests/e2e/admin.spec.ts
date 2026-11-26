import { openNavigation } from "../navigation/helpers";
import { expect, test } from "@playwright/test";
import { signIn } from "./auth-helpers";

test("admin dashboard labels demo data and shows queue metrics", async ({ page }) => {
  await page.goto("/");
  await signIn(page, "ADMIN");
  await openNavigation(page, "Quản trị");
  await expect(page.getByText("Hàng chờ kiểm duyệt", { exact: true })).toBeVisible();
  await expect(page.getByText("Dữ liệu minh họa", { exact: true })).toBeVisible();
  await expect(page.getByLabel("5 báo cáo đang chờ kiểm duyệt")).toBeVisible();
  await expect(page.getByText("báo cáo đang chờ kiểm duyệt", { exact: true })).toBeVisible();
  await expect(page.getByText("Lượt kiểm tra hôm nay", { exact: true })).toBeVisible();
  await expect(page.getByText("248", { exact: true })).toBeVisible();
  await expect(page.getByText("Xu hướng kiểm tra & báo cáo", { exact: true })).toBeVisible();
  await expect(page.getByText("Phân bố mức rủi ro kiểm tra", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Xu hướng 7 ngày" }).click();
  await expect(page.getByText("Tổng lượt quét (7 ngày)", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Xu hướng 30 ngày" }).click();
  await expect(page.getByText("Tổng lượt quét (30 ngày)", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
});

test("admin queue filters and row expansion work", async ({ page }) => {
  await page.goto("/");
  await signIn(page, "ADMIN");
  await openNavigation(page, "Quản trị");

  await expect(page.getByText("Trang tặng quà giả, đòi phí vận chuyển", { exact: true })).toBeVisible();
  await expect(page.getByText("Ghi chú kiểm duyệt", { exact: true })).toHaveCount(1);

  await page.getByRole("tab", { name: "Đã xử lý" }).click();
  await expect(page.getByText("Trang tặng quà giả, đòi phí vận chuyển", { exact: true })).toBeVisible();
  await expect(page.getByText("Gọi tự xưng nhân viên ngân hàng, yêu cầu cung cấp OTP", { exact: true })).toHaveCount(0);

  await page.getByRole("tab", { name: "Cần bổ sung" }).click();
  await expect(page.getByText("Tài khoản nhận tiền mua hàng, nhiều khiếu nại", { exact: true })).toBeVisible();
  await expect(page.getByText("Trang tặng quà giả, đòi phí vận chuyển", { exact: true })).toHaveCount(0);

  await page.getByRole("tab", { name: "Chờ duyệt" }).click();
  await expect(page.getByText("Sàn rao bán giá rẻ, yêu cầu chuyển khoản trực tiếp", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);

  await page.getByRole("tab", { name: "Tất cả" }).click();
  await page.getByText("Ghi chú kiểm duyệt", { exact: true }).click();
  await expect(page.getByText("Ghi chú kiểm duyệt", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Sàn rao bán giá rẻ, yêu cầu chuyển khoản trực tiếp", { exact: true })).toBeVisible();
  await page.getByText("Sàn rao bán giá rẻ, yêu cầu chuyển khoản trực tiếp", { exact: true }).click();
  await expect(page.getByText("Ghi chú kiểm duyệt", { exact: true })).toHaveCount(1);
});

test("admin nav is absent for signed-out visitors", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Quản trị", { exact: true })).toHaveCount(0);
  await expect(page.getByTestId("sidebar-admin")).toHaveCount(0);
  await expect(page.getByText("Hàng chờ kiểm duyệt", { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
});

test("signed-in USER is denied admin even with an admin-looking email", async ({ page }) => {
  await page.goto("/");
  await signIn(page, "USER", "admin@example.com");
  await expect(page.getByText("Quản trị", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Hàng chờ kiểm duyệt", { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
});

test("ADMIN can open admin and loses access after logout", async ({ page }) => {
  await page.goto("/");
  await signIn(page, "ADMIN");
  await expect(page.getByTestId("nav-lock-admin")).toHaveCount(0);
  await openNavigation(page, "Quản trị");
  await expect(page.getByText("Hàng chờ kiểm duyệt", { exact: true })).toBeVisible();
  if ((page.viewportSize()?.width ?? 0) >= 720) {
    const borderColor = await page.getByTestId("sidebar-admin").evaluate((element) => getComputedStyle(element).borderLeftColor);
    expect(borderColor).not.toBe("rgba(0, 0, 0, 0)");
  }
  await page.getByLabel("Tài khoản của an.nguyen").click();
  await expect(page.getByText("Đã đăng nhập", { exact: true })).toBeVisible();
  await expect(page.getByText(/Vai trò mô phỏng: Quản trị viên/)).toBeVisible();
  await page.getByText("Đăng xuất", { exact: true }).click();
  await expect(page.getByText("Quản trị", { exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Đăng nhập hoặc đăng ký")).toBeVisible();
  await expect(page.getByText("Hàng chờ kiểm duyệt", { exact: true })).toHaveCount(0);
});
