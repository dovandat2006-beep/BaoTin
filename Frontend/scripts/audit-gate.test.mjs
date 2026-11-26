import assert from "node:assert/strict";
import { test } from "node:test";
import { readFileSync } from "node:fs";
import { evaluateAudit } from "./audit-gate.mjs";

const exceptions = JSON.parse(readFileSync(new URL("./audit-exceptions.json", import.meta.url)));
const now = Date.parse("2026-10-20T00:00:00Z");
const fixture = () => ({
  auditReportVersion: 2,
  vulnerabilities: Object.fromEntries(exceptions.map((entry) => [entry.package, {
    severity: "high",
    via: [{ name: entry.package, severity: "high", url: entry.advisory, range: entry.range }]
  }]))
});

test("accepts exactly the two approved advisories", () => {
  const result = evaluateAudit(fixture(), exceptions, now);
  assert.equal(result.allowed.length, 2);
  assert.deepEqual(result.blocked, []);
});
test("accepts propagated findings without counting them as new advisories", () => {
  const report = fixture();
  report.vulnerabilities.expo = { severity: "high", via: ["node-forge"] };
  assert.deepEqual(evaluateAudit(report, exceptions, now).blocked, []);
});
test("clean report passes without exceptions", () => {
  assert.deepEqual(evaluateAudit({ auditReportVersion: 2, vulnerabilities: {} }, [], now).blocked, []);
});
test("moderate findings remain nonblocking", () => {
  const report = fixture();
  report.vulnerabilities.other = { severity: "moderate", via: [{ name: "other", severity: "moderate" }] };
  assert.deepEqual(evaluateAudit(report, exceptions, now).blocked, []);
});
test("exception expiration blocks at the boundary", () => {
  assert.ok(evaluateAudit(fixture(), exceptions, Date.parse(exceptions[0].expires)).blocked.length);
});
test("different advisory on the same package blocks", () => {
  const report = fixture();
  report.vulnerabilities.braces.via.push({ name: "braces", severity: "high", url: "https://github.com/advisories/NEW", range: "<=3.0.3" });
  assert.ok(evaluateAudit(report, exceptions, now).blocked.length);
});
test("new high package blocks", () => {
  const report = fixture();
  report.vulnerabilities.newPackage = { severity: "high", via: [{ name: "newPackage", severity: "high", url: "https://github.com/advisories/NEW" }] };
  assert.ok(evaluateAudit(report, exceptions, now).blocked.length);
});
test("a propagated critical finding blocks even with an accepted root", () => {
  const report = fixture();
  report.vulnerabilities.expo = { severity: "critical", via: ["node-forge"] };
  assert.ok(evaluateAudit(report, exceptions, now).blocked.length);
});
test("changed advisory range blocks", () => {
  const report = fixture();
  report.vulnerabilities.braces.via[0].range = "<=4.0.0";
  assert.ok(evaluateAudit(report, exceptions, now).blocked.length);
});
test("critical findings never receive exceptions", () => {
  const report = fixture();
  report.vulnerabilities.braces.via[0].severity = "critical";
  report.vulnerabilities.braces.severity = "critical";
  assert.ok(evaluateAudit(report, exceptions, now).blocked.length);
});
test("high cycles with no advisory root block", () => {
  const report = fixture();
  report.vulnerabilities.a = { severity: "high", via: ["b"] };
  report.vulnerabilities.b = { severity: "high", via: ["a"] };
  assert.ok(evaluateAudit(report, exceptions, now).blocked.length);
});
test("missing dependency rejects report", () => {
  const report = fixture();
  report.vulnerabilities.a = { severity: "high", via: ["missing"] };
  assert.throws(() => evaluateAudit(report, exceptions, now));
});
test("audit errors, malformed reports and invalid severity reject", () => {
  for (const report of [{}, { ...fixture(), error: { code: "NETWORK" } }, { auditReportVersion: 2, vulnerabilities: [] }]) {
    assert.throws(() => evaluateAudit(report, exceptions, now));
  }
  const report = fixture();
  report.vulnerabilities.braces.severity = "unknown";
  assert.throws(() => evaluateAudit(report, exceptions, now));
});
