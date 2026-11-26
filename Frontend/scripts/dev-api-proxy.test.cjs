const { test } = require("node:test");
const assert = require("node:assert/strict");
const { proxyTarget, requestOptions } = require("./dev-api-proxy.cjs");
test("development proxy only forwards public API paths to the fixed HTTPS Gateway", () => {
  assert.equal(proxyTarget(undefined, "/api/v1/auth/web/login"), "https://baotin.rimiprod.me/api/v1/auth/web/login");
  assert.equal(proxyTarget("https://gateway.example", "/api/v1/reports?page=1"), "https://gateway.example/api/v1/reports?page=1");
  for (const p of ["/", "/internal/v1/inference", "//evil.example/api", "/assets/app.js", "/api/\\evil", "/api/../internal/v1/inference", "/api/%2finternal"]) assert.equal(proxyTarget(undefined, p), null);
  for (const origin of ["http://localhost:8181", "https://gateway.example/api", "https://user:secret@gateway.example"]) assert.throws(() => proxyTarget(origin, "/api/v1/auth/me"));
});
test("Windows Gateway connections use IPv4 without disabling TLS verification or changing other platforms", () => {
  const headers = { accept: "application/json" };
  assert.deepEqual(requestOptions("POST", headers, "win32"), { method: "POST", headers, family: 4 });
  assert.deepEqual(requestOptions("POST", headers, "linux"), { method: "POST", headers });
  assert.equal(requestOptions("GET", headers, "win32").rejectUnauthorized, undefined);
});
