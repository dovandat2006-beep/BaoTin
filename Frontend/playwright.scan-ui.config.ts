import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/scan",
  outputDir: ".redesign-artifacts/scan-workbench-20261112/test-results",
  workers: 2,
  timeout: 60_000,
  use: { baseURL: process.env.BAOTIN_SCAN_UI_URL || "http://localhost:18081", trace: "off" },
  projects: [320, 375, 414, 768, 1440].flatMap(width => (["light", "dark"] as const).map(colorScheme => ({
    name: `${width}-${colorScheme}`,
    use: { viewport: { width, height: 1000 }, colorScheme }
  })))
});
