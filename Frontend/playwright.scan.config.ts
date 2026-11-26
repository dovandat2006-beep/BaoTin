import { defineConfig, devices } from "@playwright/test";

/** Requires the real local scan stack; no route interception or synthetic API responses. */
export default defineConfig({
  testDir: "./tests/live",
  testMatch: "**/*.live.ts",
  workers: 1,
  retries: 0,
  use: { baseURL: process.env.SCAN_E2E_BASE_URL ?? "http://localhost:8096", trace: "retain-on-failure" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } },
    { name: "mobile", use: { ...devices["Desktop Chrome"], viewport: { width: 375, height: 844 } } }
  ]
});
