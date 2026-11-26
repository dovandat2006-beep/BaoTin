const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { validateGatewayOrigin } = require("../ci/release-api-config.cjs");

function buildPlan(format, env, root) {
  if (env.EXPO_PUBLIC_DEMO_MODE && env.EXPO_PUBLIC_DEMO_MODE !== "0") throw new Error("Android releases must not enable demo data");
  if (!["apk", "aab", "both"].includes(format)) throw new Error("Android format must be apk, aab or both");
  validateGatewayOrigin(env.EXPO_PUBLIC_API_URL);
  const signing = env.BAOTIN_ANDROID_SIGNING_PROPERTIES;
  if (!signing || !path.isAbsolute(signing)) throw new Error("BAOTIN_ANDROID_SIGNING_PROPERTIES must name an absolute external file");
  const relative = path.relative(path.resolve(root), path.resolve(signing));
  if (!relative || (!relative.startsWith(".." + path.sep) && relative !== ".." && !path.isAbsolute(relative)))
    throw new Error("Android signing credentials must stay outside the source directory");
  return {
    tasks: format === "both" ? ["bundleRelease", "assembleRelease"] : [format === "aab" ? "bundleRelease" : "assembleRelease"],
    signing,
    env: { ...env, EXPO_NO_DOTENV: "1", EXPO_NO_TELEMETRY: "1", CI: "1", CMAKE_BUILD_PARALLEL_LEVEL: "2" }
  };
}

function main() {
  const root = path.resolve(__dirname, "..");
  const format = process.argv[2] || "both";
  const plan = buildPlan(format, process.env, root);
  if (!fs.statSync(plan.signing).isFile() || fs.lstatSync(plan.signing).isSymbolicLink()) throw new Error("Signing properties must be a regular external file");
  const run = (command, args, cwd = root, windowsVerbatimArguments = false) => {
    const result = spawnSync(command, args, { cwd, env: plan.env, stdio: "inherit", windowsVerbatimArguments });
    if (result.error) throw new Error("Android build tool could not start: " + result.error.code);
    if (result.status !== 0) throw new Error("Android build failed, exit=" + result.status);
  };
  // CNG only; never --clean, never replace a signing key or invoke a device/emulator.
  run(process.execPath, [require.resolve("expo/bin/cli"), "prebuild", "--platform", "android", "--no-install"]);
  const android = path.join(root, "android");
  const args = [...plan.tasks, "--no-daemon", "--no-parallel", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2", "-Pkotlin.compiler.execution.strategy=in-process", "--console=plain", "--init-script", path.join(root, "ci/android-signing.gradle")];
  if (process.platform === "win32") {
    // Fixed batch filename and shell-safe format enum; no credentials in argv.
    const quoted = args.map(value => '"' + value + '"').join(" ");
    run(process.env.ComSpec || "cmd.exe", ["/d", "/s", "/c", '"gradlew.bat ' + quoted + '"'], android, true);
  } else run(path.join(android, "gradlew"), args, android);
  console.log("ANDROID_NATIVE_BUILD PASS format=" + format + " mode=release signing=external deviceTests=not-run");
}
if (require.main === module) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { buildPlan };
