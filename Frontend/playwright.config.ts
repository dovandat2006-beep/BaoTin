import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  testIgnore: "**/reports.spec.ts",
  fullyParallel: true,
  retries: process.env.CI ? 2 : 0,
  // Keep local verification bounded too: this workstation also hosts live services.
  workers: 2,
  reporter: process.env.CI ? [["html", { open: "never" }], ["line"]] : "line",
  use: {
    baseURL: "http://127.0.0.1:4183",
    trace: "on-first-retry",
    screenshot: "only-on-failure"
  },
  webServer: {
    command: `node node_modules/http-server/bin/http-server ${process.env.BAOTIN_WEB_TEST_DIR || (process.env.BAOTIN_TEST_LIVE_MODE ? "dist-web-live" : "dist")} -p 4183 -c-1`,
    url: "http://127.0.0.1:4183",
    reuseExistingServer: process.env.BAOTIN_TEST_EXTERNAL_SERVER === "1" || !process.env.CI,
    timeout: 30_000
  },
  projects: [
    { name: "mobile-320", use: { ...devices["Desktop Chrome"], viewport: { width: 320, height: 844 } } },
    { name: "mobile-375", use: { ...devices["Desktop Chrome"], viewport: { width: 375, height: 844 } } },
    { name: "mobile-414", use: { ...devices["Desktop Chrome"], viewport: { width: 414, height: 896 } } },
    { name: "tablet-768", use: { ...devices["Desktop Chrome"], viewport: { width: 768, height: 1024 } } },
    { name: "desktop-1440", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } }
  ]
});
