import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/provider-quality", workers: 2, retries: 0,
  outputDir: ".redesign-artifacts/provider-links-20261116/results",
  use: { baseURL: "http://127.0.0.1:4194", reducedMotion: "reduce" },
  projects: [320, 375, 414, 768, 1024, 1440].flatMap(width => (["light", "dark"] as const).map(colorScheme => ({ name: `${width}-${colorScheme}`, use: { viewport: { width, height: 1000 }, colorScheme } })))
});
