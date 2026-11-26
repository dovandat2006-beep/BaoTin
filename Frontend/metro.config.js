const { getDefaultConfig } = require("expo/metro-config");
const { withNativeWind } = require("nativewind/metro");
const { enhanceApiProxy } = require("./scripts/dev-api-proxy.cjs");

const config = getDefaultConfig(__dirname);
// Avoid exhausting shared build hosts with one worker per reported CPU.
config.maxWorkers = 2;

const nativewind = withNativeWind(config, { input: "./global.css" });
nativewind.server = { ...nativewind.server, enhanceMiddleware: enhanceApiProxy(nativewind.server?.enhanceMiddleware) };
module.exports = nativewind;
