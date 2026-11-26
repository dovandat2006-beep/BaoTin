const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const { spawnSync } = require("node:child_process");

test("release refuses an absent Gateway origin before running build containers", () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "baotin-release-api-"));
  try {
    const root = process.cwd();
    const docker = path.join(tmp, "docker");
    fs.writeFileSync(docker, '#!/bin/sh\nif [ "$1" = cp ] && [ "$3" = build/web.tar.gz ]; then mkdir -p build; printf fixture > build/web.tar.gz; fi\nexit 0\n');
    fs.chmodSync(docker, 0o755);
    const env = { ...process.env, PATH: tmp + path.delimiter + process.env.PATH };
    delete env.EXPO_PUBLIC_API_URL;
    const defaultWindowsBash = "C:/Program Files/Git/bin/bash.exe";
    const bash = process.env.BAOTIN_TEST_BASH || (process.platform === "win32" && fs.existsSync(defaultWindowsBash) ? defaultWindowsBash : "bash");
    const result = spawnSync(bash, ["ci/build-web.sh"], { cwd: root, env, encoding: "utf8" });
    assert.ifError(result.error);
    assert.notEqual(result.status, 0, "A release with demo auth must not be packaged");
    assert.match(result.stderr, /EXPO_PUBLIC_API_URL/);
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
});

const { validateGatewayOrigin, verifyExport } = require("../ci/release-api-config.cjs");
test("release accepts only exact HTTPS Gateway origins", () => {
  assert.equal(validateGatewayOrigin("https://baotin.rimiprod.me"), "https://baotin.rimiprod.me");
  assert.equal(validateGatewayOrigin("https://gateway.example.test:9443"), "https://gateway.example.test:9443");
  for (const value of [undefined, "", " http://example.test", "http://example.test", "https://example.test/", "https://user:pass@example.test", "https://example.test/api", "https://example.test?token=fixture", "https://example.test#fixture"])
    assert.throws(() => validateGatewayOrigin(value));
});
test("release export gate rejects a demo bundle or stale different Gateway origin", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "baotin-api-export-"));
  try {
    const js = path.join(directory, "_expo/static/js/web");fs.mkdirSync(js, { recursive: true });
    const file = path.join(js, "AppEntry.js");fs.writeFileSync(file, 'const apiBaseUrl="";');
    assert.throws(() => verifyExport("https://baotin.rimiprod.me", directory));
    fs.writeFileSync(file, 'const apiBaseUrl="https://old.example.test";');
    assert.throws(() => verifyExport("https://baotin.rimiprod.me", directory));
    fs.writeFileSync(file, 'const apiBaseUrl="https://baotin.rimiprod.me";');
    assert.doesNotThrow(() => verifyExport("https://baotin.rimiprod.me", directory));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
