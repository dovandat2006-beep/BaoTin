import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/account", workers: 1, retries: 0, reporter: "line",
  use: { baseURL: "http://127.0.0.1:4190", trace: "off", reducedMotion: "reduce" },
  webServer: { command: `node node_modules/http-server/bin/http-server ${process.env.BAOTIN_ACCOUNT_WEB_DIR || ".verification/account-integration/live"} -a 127.0.0.1 -p 4190 -c-1`, url: "http://127.0.0.1:4190", reuseExistingServer: process.env.BAOTIN_TEST_EXTERNAL_SERVER === "1", timeout: 30_000 },
  projects: [
    { name: "mobile", use: { ...devices["Desktop Chrome"], viewport: { width: 375, height: 844 } } },
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } }
  ]
});
