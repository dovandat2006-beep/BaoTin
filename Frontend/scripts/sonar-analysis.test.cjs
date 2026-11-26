const { test } = require("node:test");
const assert = require("node:assert/strict");
const { verifyJavaScriptAnalysis } = require("../ci/verify-sonar-analysis.cjs");

test("rejects the observed green scanner run that skipped TypeScript", () => {
  const log = [
    "INFO Sensor TypeScript analysis [javascript]",
    "ERROR Error when running: 'node -v'. Is Node.js available during analysis?",
    "INFO Sensor TypeScript analysis [javascript] (done)",
    "INFO Sensor TextAndSecretsSensor [text]",
    "INFO 58/58 source files have been analyzed",
    "INFO ANALYSIS SUCCESSFUL"
  ].join("\n");
  assert.throws(() => verifyJavaScriptAnalysis(log));
});

test("requires a complete nonempty JavaScript/TypeScript sensor", () => {
  const sensor = (progress) => `INFO Sensor TypeScript analysis [javascript]\n${progress}\nINFO Sensor TypeScript analysis [javascript] (done)`;
  assert.throws(() => verifyJavaScriptAnalysis("INFO ANALYSIS SUCCESSFUL"));
  assert.throws(() => verifyJavaScriptAnalysis(sensor("INFO 0/0 source files have been analyzed")));
  assert.throws(() => verifyJavaScriptAnalysis(sensor("INFO 12/48 source files have been analyzed")));
  assert.throws(() => verifyJavaScriptAnalysis(sensor("INFO Skipped 48 file(s) because they were not part of any tsconfig.json\nINFO 48/48 source files have been analyzed")));
  assert.throws(() => verifyJavaScriptAnalysis(sensor("ERROR Failed to analyze file App.tsx\nINFO 48/48 source files have been analyzed")));
  assert.deepEqual(verifyJavaScriptAnalysis(sensor("INFO 48 source files to be analyzed\nINFO 48/48 source files have been analyzed")), { analyzed: 48, total: 48 });
});
