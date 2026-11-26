const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const root = path.resolve(__dirname, "..");
const brand = path.join(root, "assets/branding");

function png(name) {
  const data = fs.readFileSync(path.join(brand, name));
  assert.equal(data.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
  return { width: data.readUInt32BE(16), height: data.readUInt32BE(20), colorType: data[25] };
}

test("supplied branding originals are preserved byte-for-byte", () => {
  const sources = {
    "baotin-app-logo.png": "d44eedec8d2b905ba4716f38936be6b9749f1d626ddbc0af76723ad281866add",
    "baotin-web-logo.png": "cabebbdbfc5db4079ccf968474196c0731a6dffa782b1b6f51a26b9fc9d8ce25"
  };
  for (const [name, hash] of Object.entries(sources)) assert.equal(crypto.createHash("sha256").update(fs.readFileSync(path.join(brand, "originals", name))).digest("hex"), hash);
});

test("Android launcher configuration uses square legacy and transparent adaptive assets", () => {
  const expo = JSON.parse(fs.readFileSync(path.join(root, "app.json"), "utf8")).expo;
  assert.equal(expo.android.icon, "./assets/branding/android-icon.png");
  assert.equal(expo.android.adaptiveIcon.foregroundImage, "./assets/branding/android-adaptive-foreground.png");
  assert.equal(expo.android.adaptiveIcon.backgroundColor, JSON.parse(fs.readFileSync(path.join(root, "color-tokens.json"), "utf8")).light.mdSurface);
  assert.deepEqual(png("android-icon.png"), { width: 1024, height: 1024, colorType: 2 });
  assert.deepEqual(png("android-adaptive-foreground.png"), { width: 1024, height: 1024, colorType: 6 });
});

test("web wordmark has its transparent margins cropped and both brand slots share one component", () => {
  assert.deepEqual(png("web-wordmark.png"), { width: 246, height: 78, colorType: 6 });
  const component = fs.readFileSync(path.join(root, "src/components/BrandWordmark.tsx"), "utf8");
  assert.match(component, /Platform\.OS !== "web"/);
  assert.match(component, /accessibilityLabel="BaoTin"/);
  assert.match(component, /resizeMode="contain"/);
  for (const name of ["TopBar", "AppNav"]) assert.match(fs.readFileSync(path.join(root, "src/components/" + name + ".tsx"), "utf8"), /<BrandWordmark \/>/);
});
