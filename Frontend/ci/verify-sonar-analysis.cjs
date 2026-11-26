const fs = require("node:fs");

function verifyJavaScriptAnalysis(log) {
  if (/\bERROR\b/.test(log)) throw new Error("Sonar scanner reported an analysis error");
  const sensor = log.match(/Sensor (?:TypeScript|JavaScript\/TypeScript) analysis \[javascript\]([^]*?)Sensor (?:TypeScript|JavaScript\/TypeScript) analysis \[javascript\] \(done\)/);
  if (!sensor) throw new Error("Missing completed JavaScript/TypeScript analysis sensor");
  if (/Skipped [1-9]\d* file\(s\)/i.test(sensor[1])) throw new Error("JavaScript/TypeScript source files were skipped");
  const counts = [...sensor[1].matchAll(/(\d+)\/(\d+) source files have been analyzed/g)].at(-1);
  if (!counts || Number(counts[2]) === 0 || counts[1] !== counts[2])
    throw new Error("JavaScript/TypeScript source analysis was empty or incomplete");
  return { analyzed: Number(counts[1]), total: Number(counts[2]) };
}
if (require.main === module) {
  const result = verifyJavaScriptAnalysis(fs.readFileSync(process.argv[2], "utf8"));
  console.log(`SONAR_ANALYSIS_COVERAGE PASS javascript_typescript=${result.analyzed}/${result.total}`);
}
module.exports = { verifyJavaScriptAnalysis };
