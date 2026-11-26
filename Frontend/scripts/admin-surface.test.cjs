const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
test("admin navigation has no guest/USER entry in either desktop or mobile layout", () => {
  const s = fs.readFileSync(path.join(__dirname, "../src/components/AppNav.tsx"), "utf8");
  assert.match(s, /const visibleItems = items\.filter\(item => item\.id !== "admin" \|\| \(authenticated && adminAllowed\)\)/);
  assert.equal((s.match(/visibleItems\.map/g) || []).length, 1);
  assert.match(s, /const primaryItems = visibleItems\.filter/);
  assert.match(s, /const secondaryItems = visibleItems\.filter/);
  assert.match(s, /primaryItems\.map/);
  assert.match(s, /secondaryItems\.map/);
  assert.equal((s.match(/items\.map/g) || []).length, 0);
});
