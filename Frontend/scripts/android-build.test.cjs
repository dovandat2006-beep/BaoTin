const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const fs = require("node:fs");
const { buildPlan } = require("./build-android.cjs");
const { bundlePlan, verifyBundle } = require("./build-android-bundle.cjs");
const root = path.resolve(__dirname, "..");
const env = { EXPO_PUBLIC_API_URL: "https://baotin.rimiprod.me", BAOTIN_ANDROID_SIGNING_PROPERTIES: path.resolve(root, "../private-fixture/signing.properties") };

test("Android bundle requires live API and uses Hermes by default, not the JS-only workaround", () => {
  assert.throws(() => bundlePlan({}));
  assert.throws(() => bundlePlan({ EXPO_PUBLIC_API_URL: "http://localhost:8090" }));
  assert(!bundlePlan(env).args.includes("--no-bytecode"));
  assert(bundlePlan(env, true).args.includes("--no-bytecode"));
  assert.equal(bundlePlan(env).env.EXPO_NO_DOTENV, "1");
  assert.throws(() => bundlePlan({ ...env, EXPO_PUBLIC_DEMO_MODE: "1" }));
  assert.throws(() => buildPlan("aab", { ...env, EXPO_PUBLIC_DEMO_MODE: "1" }, root));
});

test("export verification rejects stale/demo exports and disguised plain JS", () => {
  const origin = env.EXPO_PUBLIC_API_URL;
  const header = Buffer.from("c61fbc03c103191f", "hex");
  assert.doesNotThrow(() => verifyBundle(Buffer.concat([header, Buffer.from(origin)]), origin));
  assert.throws(() => verifyBundle(Buffer.concat([header, Buffer.from("https://stale.example")] ), origin));
  assert.throws(() => verifyBundle(Buffer.from(origin), origin));
  assert.doesNotThrow(() => verifyBundle(Buffer.from(origin), origin, false));
});

test("native release builds real APK/AAB tasks without a device or emulator", () => {
  assert.deepEqual(buildPlan("both", env, root).tasks, ["bundleRelease", "assembleRelease"]);
  assert.deepEqual(buildPlan("apk", env, root).tasks, ["assembleRelease"]);
  assert.deepEqual(buildPlan("aab", env, root).tasks, ["bundleRelease"]);
  const source = fs.readFileSync(path.join(__dirname, "build-android.cjs"), "utf8");
  assert.doesNotMatch(source, /["'](?:installRelease|installDebug|adb|emulator)["']/);
});
test("release rejects missing/demo/insecure/wrong-shaped Gateway origins", () => {
  for (const origin of [undefined, "", "http://localhost:8090", "https://baotin.rimiprod.me/api", "https://user:pass@baotin.rimiprod.me"])
    assert.throws(() => buildPlan("aab", { ...env, EXPO_PUBLIC_API_URL: origin }, root));
});
test("release refuses implicit debug signing or source-tree credentials", () => {
  assert.throws(() => buildPlan("aab", { ...env, BAOTIN_ANDROID_SIGNING_PROPERTIES: undefined }, root));
  assert.throws(() => buildPlan("aab", { ...env, BAOTIN_ANDROID_SIGNING_PROPERTIES: "relative.properties" }, root));
  assert.throws(() => buildPlan("aab", { ...env, BAOTIN_ANDROID_SIGNING_PROPERTIES: path.join(root, "secrets/signing.properties") }, root));
  assert.throws(() => buildPlan("aab && installDebug", env, root));
});
test("release disables dotenv overrides and keeps credentials out of command arguments", () => {
  const plan = buildPlan("aab", env, root);
  assert.equal(plan.env.EXPO_NO_DOTENV, "1");
  assert.equal(plan.env.EXPO_PUBLIC_API_URL, env.EXPO_PUBLIC_API_URL);
  assert(!plan.tasks.join(" ").includes("password"));
  const gradle = fs.readFileSync(path.join(root, "ci/android-signing.gradle"), "utf8");
  assert.match(gradle, /finalizeDsl/);
  assert.match(gradle, /signingConfig = signing/);
  assert.doesNotMatch(gradle, /println|signingConfigs\.debug/);
  assert.match(gradle, /CMAKE_OBJECT_PATH_MAX=240/);
});

test("shared-host verification does not fan out one worker per CPU", () => {
  const metro = fs.readFileSync(path.join(root, "metro.config.js"), "utf8");
  const browser = fs.readFileSync(path.join(root, "playwright.config.ts"), "utf8");
  assert.match(metro, /config\.maxWorkers = 2/);
  assert.match(browser, /workers: 2/);
  assert.equal(buildPlan("aab", env, root).env.CMAKE_BUILD_PARALLEL_LEVEL, "2");
});
