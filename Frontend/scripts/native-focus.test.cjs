const { test } = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");
const root = path.resolve(__dirname, "..");

for (const platform of ["android", "ios"]) test(`${platform} focus-visible rings compile to conditional focus, not permanent outlines`, () => {
  const script = `
    process.env.NATIVEWIND_OS=${JSON.stringify(platform)};
    const postcss=require('postcss'),tailwind=require('tailwindcss');
    const {cssToReactNativeRuntime}=require('react-native-css-interop/css-to-rn');
    const config=require('./tailwind.config');
    config.content=[{raw:'<View className="focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary"/>',extension:'tsx'}];
    (async()=>{const css=(await postcss([tailwind(config)]).process('@tailwind base;@tailwind utilities;',{from:undefined})).css;console.log(JSON.stringify(cssToReactNativeRuntime(css,{inlineRem:14})))})().catch(e=>{console.error(e);process.exitCode=1});
  `;
  const result = spawnSync(process.execPath, ["-e", script], { cwd: root, encoding: "utf8", windowsHide: true });
  assert.equal(result.status, 0, result.stderr);
  const rules = JSON.parse(result.stdout).rules;
  for (const name of ["focus-visible:ring-2", "focus-visible:ring-inset", "focus-visible:ring-md-primary", "dark:focus-visible:ring-dark-md-primary"]) {
    assert.ok(rules[name]?.n?.length, name);
    for (const rule of rules[name].n) assert.equal(rule.pseudoClasses?.focus, true, `${name} must not apply without focus`);
  }
});

test("Web focus-visible keeps its keyboard-only pseudo selector", () => {
  const script = `process.env.NATIVEWIND_OS='web';const p=require('postcss'),t=require('tailwindcss'),c=require('./tailwind.config');c.content=[{raw:'<button className="focus-visible:ring-2"/>',extension:'tsx'}];p([t(c)]).process('@tailwind utilities;',{from:undefined}).then(r=>console.log(r.css));`;
  const result = spawnSync(process.execPath, ["-e", script], { cwd: root, encoding: "utf8", windowsHide: true });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /:focus-visible\s*\{/);
  assert.doesNotMatch(result.stdout, /:focus\s*\{/);
});
