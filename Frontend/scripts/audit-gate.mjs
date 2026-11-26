import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export function evaluateAudit(report, exceptions, now = Date.now()) {
  if (report?.auditReportVersion !== 2 || report.error ||
      !report.vulnerabilities || typeof report.vulnerabilities !== "object" ||
      Array.isArray(report.vulnerabilities) || !Array.isArray(exceptions)) {
    throw new Error("Invalid npm audit report or exception list");
  }
  const vulnerabilities = report.vulnerabilities;
  const blocked = new Set();
  const allowed = new Set();
  const roots = new Set();
  const severe = (severity) => severity === "high" || severity === "critical";
  for (const [name, vulnerability] of Object.entries(vulnerabilities)) {
    if (!Array.isArray(vulnerability.via) || vulnerability.via.length === 0 ||
        !["info", "low", "moderate", "high", "critical"].includes(vulnerability.severity)) {
      throw new Error(`Invalid vulnerability entry: ${name}`);
    }
    if (vulnerability.severity === "critical") blocked.add(`${name}: critical`);
    for (const via of vulnerability.via) {
      if (typeof via === "string") {
        if (!vulnerabilities[via]) throw new Error(`Missing audit dependency: ${via}`);
        continue;
      }
      if (!via || !["info", "low", "moderate", "high", "critical"].includes(via.severity)) {
        throw new Error(`Invalid advisory: ${name}`);
      }
      if (!severe(via.severity)) continue;
      const exception = exceptions.find((entry) =>
        entry.package === name && via.name === name && entry.advisory === via.url &&
        entry.range === via.range && via.severity === "high" &&
        typeof entry.reason === "string" && entry.reason.length > 0 &&
        Date.parse(entry.expires) > now);
      if (!exception) blocked.add(`${name}: ${via.url ?? "unknown advisory"}`);
      else {
        allowed.add(exception.advisory);
        roots.add(name);
      }
    }
  }
  // Propagated high findings must reach a specifically accepted root, not a cycle.
  const reachesRoot = (name, seen = new Set()) => {
    if (roots.has(name)) return true;
    if (seen.has(name)) return false;
    const next = new Set([...seen, name]);
    return vulnerabilities[name].via.some((via) =>
      typeof via === "string" && reachesRoot(via, next));
  };
  for (const [name, vulnerability] of Object.entries(vulnerabilities)) {
    if (severe(vulnerability.severity) && !reachesRoot(name)) {
      blocked.add(`${name}: no accepted advisory root`);
    }
  }
  return { allowed: [...allowed].sort(), blocked: [...blocked].sort() };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const report = JSON.parse(readFileSync(process.argv[2], "utf8"));
    const exceptions = JSON.parse(readFileSync(new URL("./audit-exceptions.json", import.meta.url), "utf8"));
    const result = evaluateAudit(report, exceptions);
    for (const advisory of result.allowed) console.log(`ACCEPTED until 2026-11-19: ${advisory}`);
    for (const finding of result.blocked) console.error(`BLOCKED: ${finding}`);
    console.log(`Audit gate: ${result.allowed.length} accepted advisories, ${result.blocked.length} blocking findings`);
    process.exitCode = result.blocked.length ? 1 : 0;
  } catch (error) {
    console.error(`Audit gate error: ${error.message}`);
    process.exitCode = 1;
  }
}
