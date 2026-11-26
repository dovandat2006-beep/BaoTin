const fs = require("node:fs");
const path = require("node:path");

function validateGatewayOrigin(value) {
  if (typeof value !== "string" || !value || value !== value.trim()) throw new Error("EXPO_PUBLIC_API_URL must be an exact HTTPS Gateway origin");
  const url = new URL(value);
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash || value !== url.origin)
    throw new Error("EXPO_PUBLIC_API_URL must be an exact HTTPS Gateway origin without path, credentials or query");
  return url.origin;
}
function verifyExport(origin, directory = "dist") {
  const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? walk(path.join(dir, entry.name)) : [path.join(dir, entry.name)]);
  const files = walk(path.join(directory, "_expo", "static", "js"));
  if (!files.some(file => file.endsWith(".js") && fs.readFileSync(file, "utf8").includes(origin)))
    throw new Error("Release web export does not contain the configured live Gateway origin");
}
if (require.main === module) {
  if (process.env.EXPO_PUBLIC_DEMO_MODE && process.env.EXPO_PUBLIC_DEMO_MODE !== "0") throw new Error("Release builds must not enable EXPO_PUBLIC_DEMO_MODE");
  const origin = validateGatewayOrigin(process.env.EXPO_PUBLIC_API_URL);
  if (process.argv.includes("--verify-export")) verifyExport(origin);
  console.log("RELEASE_API_CONFIG PASS mode=live origin=" + origin);
}
module.exports = { validateGatewayOrigin, verifyExport };
