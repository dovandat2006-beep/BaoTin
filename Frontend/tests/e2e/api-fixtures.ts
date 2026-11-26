import { expect, type Page } from "@playwright/test";
import type { Report } from "../../src/api/reports";

/** HTTP fixtures prove client behavior only. Backend security has separate signed-JWT tests. */
export async function installScanFixture(page: Page, scanId = "00000000-0000-0000-0000-000000000099") {
  await page.route("**/api/v1/scans/text", route => route.fulfill({ json: {
    scanId, type: "TEXT", targetId: null, preview: "[Message content hidden]",
    risk: { score: 80, level: "HIGH" }, textModel: null, urlModel: null,
    rules: { score: 80, signals: ["SENSITIVE_CREDENTIAL_REQUEST"] }, urls: [],
    aiAvailable: false, degraded: true, warnings: ["TEXT_AI_UNAVAILABLE"], scannedAt: "2026-10-22T00:00:00Z"
  } }));
  return scanId;
}
export async function installAuthFixture(page: Page, role = "USER", identifier = "an.nguyen@example.com") {
  await page.route("**/api/v1/auth/**", async route => {
    const path = new URL(route.request().url()).pathname;
    const profile = { id: "00000000-0000-0000-0000-000000000001", displayName: identifier.split("@")[0], role };
    if (path.endsWith("/me")) await route.fulfill({ json: profile });
    else if (path.endsWith("/register")) await route.fulfill({ status: 201, json: profile });
    else if (path.endsWith("/logout")) await route.fulfill({ status: 204 });
    else await route.fulfill({ json: { accessToken: "test-http-fixture-not-a-jwt", csrfToken: "test-csrf" } });
  });
}
export async function installReportFixture(page: Page, initial: Report[] = []) {
  const reports = [...initial]; let sequence = 0;
  await page.route(/\/api\/v1\/(reports|moderation\/reports)(\/|\?|$)/, async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    expect(request.headers().authorization).toBe("Bearer test-http-fixture-not-a-jwt");
    if (request.method() === "POST" && path === "/api/v1/reports") {
      expect(request.headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/);
      const draft = request.postDataJSON(); const now = new Date().toISOString();
      const report: Report = { id: `00000000-0000-0000-0000-${String(++sequence).padStart(12, "0")}`, reporterUserId: "00000000-0000-0000-0000-000000000001", threatTargetId: "00000000-0000-0000-0000-000000000010", targetTypeSnapshot: draft.targetType, targetDisplaySnapshot: draft.targetValue, category: draft.category, title: draft.title, description: draft.description, status: "SUBMITTED", priority: "MEDIUM", moderatorNote: null, createdAt: now, updatedAt: now, version: 0, evidence: [], history: [{ fromStatus: null, toStatus: "SUBMITTED", changedByUserId: "00000000-0000-0000-0000-000000000001", note: null, createdAt: now }] };
      reports.unshift(report); await route.fulfill({ status: 201, json: report }); return;
    }
    const report = reports.find(r => path.includes(r.id));
    if (report && request.method() === "POST" && path.endsWith("/evidence")) {
      expect(request.headers()["content-type"]).toContain("multipart/form-data; boundary=");
      const evidence = { id: "00000000-0000-0000-0000-000000000020", originalFilename: "capture.png", mimeType: "image/png", sizeBytes: 68, sha256: "a".repeat(64), createdAt: new Date().toISOString() };
      report.evidence.push(evidence);report.version++;
      await route.fulfill({ status: 201, json: evidence });return;
    }
    if (report && request.method() === "POST") {
      const { version, note } = request.postDataJSON();
      expect(version).toBe(report.version);expect(note.trim()).not.toBe("");
      const next = path.endsWith("/verify") ? "VERIFIED" : path.endsWith("/reject") ? "REJECTED" : path.endsWith("/request-info") ? "NEED_MORE_INFO" : "PENDING_REVIEW";
      report.history.push({ fromStatus: report.status, toStatus: next, changedByUserId: "00000000-0000-0000-0000-000000000002", note, createdAt: new Date().toISOString() });
      report.status = next;report.version++;report.moderatorNote = note;
      await route.fulfill({ json: report });return;
    }
    if (report) { await route.fulfill({ json: report });return; }
    const items = path.includes("moderation") ? reports.filter(r => r.status === url.searchParams.get("status")) : reports;
    await route.fulfill({ json: { items, page: 0, size: 20, totalItems: items.length, totalPages: items.length ? 1 : 0 } });
  });
  return reports;
}
