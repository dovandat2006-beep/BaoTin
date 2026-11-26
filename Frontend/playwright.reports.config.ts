import base from "./playwright.config";
import { defineConfig } from "@playwright/test";
export default defineConfig({
  ...base,
  testIgnore: [],
  testMatch: "**/reports.spec.ts",
  outputDir: "test-results/reports",
  reporter: process.env.CI ? [["html", { open: "never", outputFolder: "playwright-report-reports" }], ["line"]] : "line",
  use: { ...base.use, baseURL: "http://127.0.0.1:4184" },
  webServer: {
    command: "node node_modules/http-server/bin/http-server dist-api -p 4184 -c-1",
    url: "http://127.0.0.1:4184",
    reuseExistingServer: process.env.BAOTIN_TEST_EXTERNAL_SERVER === "1"
  },
  projects: base.projects?.map(project => ({
    ...project,
    use: {
      ...project.use,
      ...(process.env.BAOTIN_TEST_CHROMIUM ? { launchOptions: { executablePath: process.env.BAOTIN_TEST_CHROMIUM } } : {})
    }
  }))
});
