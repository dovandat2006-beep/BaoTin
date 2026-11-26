import type { Page } from "@playwright/test";

/** Secondary mobile destinations remain behind the real More menu. */
export async function openNavigation(page: Page, label: string) {
  const more = page.getByRole("button", { name: "Thêm", exact: true });
  if (["Quản trị", "Theo dõi"].includes(label) && await more.isVisible()) await more.click();
  await page.getByText(label, { exact: true }).last().click();
}
