const path = require("node:path");
const fs = require("node:fs");
const { spawnSync } = require("node:child_process");
const { validateGatewayOrigin } = require("../ci/release-api-config.cjs");

function bundlePlan(env, noBytecode = false) {
  if (env.EXPO_PUBLIC_DEMO_MODE && env.EXPO_PUBLIC_DEMO_MODE !== "0") throw new Error("Android release exports must not enable demo data");
  validateGatewayOrigin(env.EXPO_PUBLIC_API_URL);
  return {
    args: ["export", "--platform", "android", "--max-workers", "2", "--output-dir", "dist-android", ...(noBytecode ? ["--no-bytecode"] : [])],
    env: { ...env, EXPO_NO_DOTENV: "1", EXPO_NO_TELEMETRY: "1", CI: "1" }
  };
}
function verifyBundle(bytes, origin, bytecode = true) {
  validateGatewayOrigin(origin);
  if (!bytes.includes(Buffer.from(origin))) throw new Error("Android bundle does not contain the selected Gateway origin");
  if (bytecode && !bytes.subarray(0, 8).equals(Buffer.from("c61fbc03c103191f", "hex"))) throw new Error("Android release export must contain Hermes bytecode, not plain JS");
}
if (require.main === module) {
  try {
    const plan = bundlePlan(process.env, process.argv.includes("--no-bytecode"));
    const result = spawnSync(process.execPath, [require.resolve("expo/bin/cli"), ...plan.args], { cwd: path.resolve(__dirname, ".."), env: plan.env, stdio: "inherit" });
    if (result.error || result.status !== 0) throw new Error("Android export failed, exit=" + result.status);
    const bytecode = !process.argv.includes("--no-bytecode");
    const directory = path.resolve(__dirname, "../dist-android/_expo/static/js/android");
    const files = fs.readdirSync(directory).filter(file => file.endsWith(bytecode ? ".hbc" : ".js"));
    if (files.length !== 1) throw new Error("Expected exactly one Android entry bundle");
    verifyBundle(fs.readFileSync(path.join(directory, files[0])), process.env.EXPO_PUBLIC_API_URL, bytecode);
    console.log("ANDROID_BUNDLE PASS mode=live bytecode=" + !process.argv.includes("--no-bytecode") + " installable=false");
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { bundlePlan, verifyBundle };
