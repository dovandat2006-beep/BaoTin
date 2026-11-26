const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { bundlePlan, verifyBundle } = require("./build-android-bundle.cjs");

function iosPlan(env, noBytecode = false) {
  const plan = bundlePlan(env, noBytecode);
  plan.args[plan.args.indexOf("android")] = "ios";
  plan.args[plan.args.indexOf("dist-android")] = "dist-ios";
  return plan;
}
if (require.main === module) {
  try {
    const noBytecode = process.argv.includes("--no-bytecode");
    const plan = iosPlan(process.env, noBytecode);
    const result = spawnSync(process.execPath, [require.resolve("expo/bin/cli"), ...plan.args], { cwd: path.resolve(__dirname, ".."), env: plan.env, stdio: "inherit" });
    if (result.error || result.status !== 0) throw new Error("iOS export failed, exit=" + result.status);
    const dir = path.resolve(__dirname, "../dist-ios/_expo/static/js/ios");
    const files = fs.readdirSync(dir).filter(f => f.endsWith(noBytecode ? ".js" : ".hbc"));
    if (files.length !== 1) throw new Error("Expected exactly one iOS entry bundle");
    verifyBundle(fs.readFileSync(path.join(dir, files[0])), process.env.EXPO_PUBLIC_API_URL, !noBytecode);
    console.log("IOS_BUNDLE PASS mode=live bytecode=" + !noBytecode + " installable=false");
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { iosPlan };
