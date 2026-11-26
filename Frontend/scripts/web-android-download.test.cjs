const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('web release retains the explicitly pinned Android source without relabelling the APK as current web source', () => {
  const script = fs.readFileSync(process.env.BAOTIN_WEB_BUILD_SCRIPT || path.join(__dirname, '../ci/build-web.sh'), 'utf8');
  assert.match(script, /android_snapshot="\$\{BAOTIN_ANDROID_DOWNLOAD_DIR##\*\/\}"/);
  assert.match(script, /--env BAOTIN_ANDROID_DOWNLOAD_ID="\$android_snapshot"/);
  assert.match(script, /stage-android-download\.cjs \/tmp\/android-download \/work\/public\/downloads "\$BAOTIN_ANDROID_DOWNLOAD_ID"/);
  assert.doesNotMatch(script, /stage-android-download[^\n]*"\$source_snapshot"/);
});

test('normal frontend releases retain a reviewed signed APK by default', () => {
  const pipeline = fs.readFileSync(process.env.BAOTIN_FRONTEND_PIPELINE || path.join(__dirname, '../Jenkinsfile'), 'utf8');
  assert.match(pipeline, /name: 'ANDROID_DOWNLOAD_ID', defaultValue: '[a-f0-9]{64}'/);
});
