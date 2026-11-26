const assert = require("node:assert/strict");
const { test } = require("node:test");
const { createRequire } = require("node:module");
const path = require("node:path");
const fs = require("node:fs");
const rootRequire = createRequire(path.join(process.cwd(), "package.json"));
const expoRequire = createRequire(rootRequire.resolve("expo/package.json"));
// Expo owns its CLI version; a redundant top-level CLI can introduce incompatible peers.
const packages = new Set([expoRequire.resolve("@expo/cli/package.json")]);
try { packages.add(rootRequire.resolve("@expo/cli/package.json")); } catch (error) { if (error.code !== "MODULE_NOT_FOUND") throw error; }
for (const pkg of packages) {
  test(`Expo devtools schema compatibility: ${path.relative(process.cwd(), pkg)}`, () => {
    const base = path.dirname(pkg);
    const legacy = path.join(base, "build/src/start/server/DevToolsPlugin.schema.js");
    const current = path.join(base, "build/src/start/server/devtools/DevToolsPlugin.schema.js");
    assert.ok(fs.existsSync(legacy) || fs.existsSync(current), "Known Expo devtools schema layout is required");
    const modern = !fs.existsSync(legacy);
    const { PluginSchema, DevToolsPluginOutputSchema } = require(modern ? current : legacy);
    const cliRequire = createRequire(pkg);
    const validate = modern ? cliRequire("@expo/schema-utils").validate : null;
    const parsePlugin = value => modern ? (validate(PluginSchema, value), value) : PluginSchema.parse(value);
    const validOutput = value => {
      if (!modern) return DevToolsPluginOutputSchema.safeParse(value).success;
      try { validate(DevToolsPluginOutputSchema, value); return true; } catch (error) {
        if (!(error instanceof cliRequire("@expo/schema-utils").ValidationError)) throw error;
        return false;
      }
    };
    const factoryPath = modern ? "build/src/start/server/mcp/createMCPDevToolsExtensionSchema.js" : "build/src/start/server/createMCPDevToolsExtensionSchema.js";
    const { createMCPDevToolsExtensionSchema } = require(path.join(base, factoryPath));
    const plugin = parsePlugin({ packageName: "merge-test-plugin", packageRoot: "/fixture", cliExtensions: {
      description: "fixture", entryPoint: "fixture.js", commands: [{ name: "inspect", title: "Inspect", environments: ["cli", "mcp"], parameters: [
        { name: "limit", type: "number" }, { name: "confirm", type: "confirm" }, { name: "query", type: "text" }
      ] }]
    } });
    // Current CLI accepts the MCP host's Zod explicitly; use the pinned test dependency.
    const schema = createMCPDevToolsExtensionSchema(plugin, modern ? rootRequire("zod/v4") : undefined);
    assert.equal(schema.parse({ command: "inspect", limit: 2, confirm: true, query: "fixture" }).limit, 2);
    for (const input of [{ command: "missing" }, { command: "inspect", limit: "two" }, { command: "inspect", confirm: "yes" }, { command: "inspect", unexpected: true }])
      assert.equal(schema.safeParse(input).success, false);
    assert.equal(validOutput([{ type: "text", text: "fixture", level: "info" }]), true);
    assert.equal(validOutput([{ type: "audio", url: "not a url" }]), false);
  });
}
