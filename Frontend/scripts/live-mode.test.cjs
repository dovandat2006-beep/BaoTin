const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const root = path.resolve(__dirname, "..");

test("release gate rejects demo mode even when a real public origin is set", () => {
  const p = spawnSync(process.execPath, ["ci/release-api-config.cjs"], { cwd: root, env: { ...process.env, EXPO_PUBLIC_API_URL: "https://baotin.rimiprod.me", EXPO_PUBLIC_DEMO_MODE: "1" }, encoding: "utf8" });
  assert.notEqual(p.status, 0);
  assert.match(p.stderr, /must not enable EXPO_PUBLIC_DEMO_MODE/);
});
test("unavailable scaffold views do not render demo screens in live API mode", () => {
  const app = fs.readFileSync(path.join(root, "App.tsx"), "utf8");
  for (const service of ["Reports"])
    assert(app.includes(`if (apiEnabled) return <ServiceUnavailableScreen service="${service}"`));
  assert(app.includes('if (apiEnabled) return <LiveSearchScreen api={guestScanApi} />'));
  const search = fs.readFileSync(path.join(root, "src/screens/LiveSearchScreen.tsx"), "utf8");
  assert.doesNotMatch(search, /data\/mock|filterMockSearch|createMock/);
  const workspace = fs.readFileSync(path.join(root, "src/screens/AdminWorkspace.tsx"), "utf8");
  assert.match(workspace, /<AdminDashboardScreen api=\{analyticsApi\}/);
  assert.match(app, /if \(accountApi && session && analyticsApi\) return <AdminWorkspace/);
  const admin = fs.readFileSync(path.join(root, "src/screens/AdminUsersScreen.tsx"), "utf8");
  assert.doesNotMatch(admin, /data\/mock|adminDemo|setTimeout\([^]*fake/i);
  assert.match(app, /useState\(\(\) => apiEnabled \? \[\] : \[\.\.\.initialReports\]\)/);
  const screen = fs.readFileSync(path.join(root, "src/screens/ServiceUnavailableScreen.tsx"), "utf8");
  assert.doesNotMatch(screen, /data\/mock|adminDemo|setTimeout/);
});
