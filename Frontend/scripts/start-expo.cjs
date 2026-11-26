const path = require("node:path");
const { spawn } = require("node:child_process");

function startPlan(target, platform, env) {
  if (!["go", "android", "ios", "web"].includes(target)) throw new Error("Expected go, android, ios or web");
  const args = ["start", "--go", "--max-workers", "2"];
  const port = env.EXPO_DEV_PORT || "18081";
  if (!/^\d+$/.test(port) || Number(port) < 1024 || Number(port) > 65535) throw new Error("EXPO_DEV_PORT must be a non-privileged TCP port");
  args.push("--port", port);
  if (target === "web") args.push("--web");
  if (target === "android") args.push("--android");
  // iOS Simulator requires macOS; on Windows/Linux leave the same real app available by QR.
  if (target === "ios" && platform === "darwin") args.push("--ios");
  return { args, env: { ...env, EXPO_NO_TELEMETRY: "1", EXPO_PUBLIC_WEB_API_PROXY: "1" }, iosQR: target === "ios" && platform !== "darwin" };
}
if (require.main === module) {
  try {
    const plan = startPlan(process.argv[2] || "go", process.platform, process.env);
    if (plan.iosQR) console.log("iOS: dùng Expo Go tương thích SDK 58 trên iPhone để quét QR; Simulator chỉ mở trên macOS.");
    const extra = process.argv.slice(3);
    if (extra.includes("--port")) plan.args.splice(plan.args.indexOf("--port"), 2);
    const child = spawn(process.execPath, [require.resolve("expo/bin/cli"), ...plan.args, ...extra], { cwd: path.resolve(__dirname, ".."), env: plan.env, stdio: "inherit" });
    child.on("error", () => { console.error("Expo development server did not start"); process.exitCode = 1; });
    child.on("exit", code => { process.exitCode = code ?? 1; });
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { startPlan };
