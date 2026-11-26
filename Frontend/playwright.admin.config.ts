import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/security",
  testMatch: "admin-boundary.spec.ts",
  workers: 1,
  retries: 0,
  reporter: "line",
  use: { baseURL: "http://127.0.0.1:4189", trace: "off", reducedMotion: "reduce" },
  webServer: {
    command: `node node_modules/http-server/bin/http-server ${process.env.BAOTIN_ADMIN_WEB_DIR || "dist-web-live"} -a 127.0.0.1 -p 4189 -c-1`,
    url: "http://127.0.0.1:4189",
    reuseExistingServer: process.env.BAOTIN_TEST_EXTERNAL_SERVER === "1",
    timeout: 30_000
  },
  projects: [
    { name: "mobile-375", use: { ...devices["Desktop Chrome"], viewport: { width: 375, height: 844 } } },
    { name: "desktop-1440", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } }
  ]
});
