import { expect, type Page } from "@playwright/test";

export type DemoRole = "USER" | "ADMIN";

/** Đăng nhập bằng form mock hiện tại; vai trò ADMIN được chọn rõ ràng ở khối demo. */
export async function signIn(page: Page, role: DemoRole = "USER", identifier = "an.nguyen@example.com") {
  await page.getByLabel("Đăng nhập hoặc đăng ký").click();
  await expect(page.getByText("Đăng nhập BaoTin", { exact: true })).toBeVisible();
  await page.getByLabel("Email, số điện thoại hoặc username").fill(identifier);
  await page.getByLabel("Mật khẩu", { exact: true }).fill("password123");
  if (role === "ADMIN") await page.getByRole("tab", { name: "Quản trị viên" }).click();
  await page.getByText("Đăng nhập", { exact: true }).last().click();
  await expect(page.getByLabel(`Tài khoản của ${identifier.split("@")[0]}`)).toBeVisible();
}
