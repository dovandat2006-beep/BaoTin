const https = require("node:https");
const { validateGatewayOrigin } = require("../ci/release-api-config.cjs");

function proxyTarget(origin, requestUrl) {
  const base = validateGatewayOrigin(origin || "https://baotin.rimiprod.me");
  if (!requestUrl.startsWith("/api/") && requestUrl !== "/.well-known/jwks.json") return null;
  if (requestUrl.includes("\\") || requestUrl.startsWith("//")) return null;
  const parsed = new URL(requestUrl, base);
  if (parsed.pathname.includes("%") || (!parsed.pathname.startsWith("/api/") && parsed.pathname !== "/.well-known/jwks.json")) return null;
  return base + requestUrl;
}
function requestOptions(method, headers, platform = process.platform) {
  return { method, headers, ...(platform === "win32" ? { family: 4 } : {}) };
}
function enhanceApiProxy(previous) {
  return (middleware, server) => {
    const next = previous ? previous(middleware, server) : middleware;
    return (req, res, done) => {
      const target = proxyTarget(process.env.EXPO_PUBLIC_API_URL, req.url || "/");
      if (!target) return next(req, res, done);
      const headers = { ...req.headers };
      for (const name of Object.keys(headers)) {
        if (["host", "connection", "proxy-authorization", "proxy-connection"].includes(name) || /^(x-forwarded-|x-user-|x-roles$|cf-)/i.test(name)) delete headers[name];
      }
      const upstream = https.request(target, requestOptions(req.method, headers), response => {
        res.writeHead(response.statusCode || 502, response.headers);
        response.pipe(res);
      });
      upstream.setTimeout(15000, () => { const error = new Error("Gateway connection timed out"); error.code = "ETIMEDOUT"; upstream.destroy(error); });
      upstream.on("error", error => {
        console.warn("[BAOTIN_DEV_PROXY] connection failed code=" + (error.code || "UNKNOWN"));
        if (res.headersSent) return res.destroy();
        res.writeHead(502, { "Content-Type": "application/problem+json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ type: "about:blank", title: "Gateway unavailable", status: 502 }));
      });
      req.on("aborted", () => upstream.destroy());
      req.pipe(upstream);
    };
  };
}
module.exports = { proxyTarget, enhanceApiProxy, requestOptions };
