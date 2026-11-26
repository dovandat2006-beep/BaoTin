const { test } = require("node:test");
const assert = require("node:assert/strict");
const { startPlan } = require("./start-expo.cjs");
const { iosPlan } = require("./build-ios-bundle.cjs");

test("all run commands select Expo Go rather than an accidental native dev client", () => {
  for (const target of ["go", "android", "ios", "web"]) {
    const plan = startPlan(target, "win32", {});
    assert(plan.args.includes("--go"));
    assert.deepEqual(plan.args.slice(0, 4), ["start", "--go", "--max-workers", "2"]);
    assert(!plan.args.includes("--dev-client"));
    assert.equal(plan.args[plan.args.indexOf("--port") + 1], "18081");
    assert.equal(plan.env.EXPO_PUBLIC_DEMO_MODE, undefined);
    assert.equal(plan.env.EXPO_PUBLIC_WEB_API_PROXY, "1");
  }
});
test("iOS run remains usable by QR on Windows/Linux without trying a nonexistent simulator", () => {
  for (const os of ["win32", "linux"]) {
    const plan = startPlan("ios", os, {});
    assert(plan.iosQR); assert(!plan.args.includes("--ios"));
  }
  assert(startPlan("ios", "darwin", {}).args.includes("--ios"));
  assert(startPlan("android", "win32", {}).args.includes("--android"));
  assert(startPlan("web", "win32", {}).args.includes("--web"));
  assert.throws(() => startPlan("invalid", "win32", {}));
  assert.throws(() => startPlan("go", "win32", { EXPO_DEV_PORT: "80" }));
});
test("iOS build command uses the same real Gateway and Hermes guard as Android", () => {
  const p = iosPlan({ EXPO_PUBLIC_API_URL: "https://baotin.rimiprod.me" });
  assert(p.args.includes("ios")); assert(p.args.includes("dist-ios")); assert(!p.args.includes("--no-bytecode"));
  assert.throws(() => iosPlan({ EXPO_PUBLIC_API_URL: "https://baotin.rimiprod.me", EXPO_PUBLIC_DEMO_MODE: "1" }));
});
