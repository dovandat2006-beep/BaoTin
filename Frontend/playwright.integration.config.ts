import { defineConfig, devices } from "@playwright/test";

/** Disposable real services are started by Infra's integration harness, not mocked here. */
export default defineConfig({
  testDir: "./tests/live",
  testMatch: "api-chain.live.ts",
  workers: 1,
  retries: 0,
  reporter: "line",
  webServer: process.env.BAOTIN_LIVE_CHAIN_BASE_URL ? {
    command: "node node_modules/http-server/bin/http-server dist-integrated -a 127.0.0.1 -p 4188 -c-1",
    url: process.env.BAOTIN_LIVE_CHAIN_BASE_URL,
    reuseExistingServer: false,
    timeout: 30_000
  } : undefined,
  use: {
    baseURL: process.env.BAOTIN_LIVE_CHAIN_BASE_URL,
    trace: "off", // Auth headers and cookies must never be written to browser traces.
    screenshot: "only-on-failure",
    ...(process.env.BAOTIN_TEST_CHROMIUM ? { launchOptions: { executablePath: process.env.BAOTIN_TEST_CHROMIUM } } : {})
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } },
    { name: "mobile", use: { ...devices["Desktop Chrome"], viewport: { width: 375, height: 844 } } }
  ]
});
