const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const { createRequire } = require('node:module');
const root = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const local = createRequire(path.join(root, 'package.json'));

test('React Native and native build tools match the installed Expo SDK metadata', () => {
  const declared = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8')).dependencies['react-native'];
  const expected = local('expo/bundledNativeModules.json')['react-native'];
  const native = local('react-native/package.json');
  assert.equal(declared, expected, 'Review and align React Native with the installed Expo SDK before release');
  assert.equal(native.version, expected, 'Locked install must match the declared Expo-compatible runtime');
  assert.equal(local('@react-native/gradle-plugin/package.json').version, native.version, 'Native Gradle plugin must match runtime patch');
  assert.equal(local('hermes-compiler/package.json').version, native.dependencies['hermes-compiler'], 'Hermes compiler must match the runtime dependency');
});
