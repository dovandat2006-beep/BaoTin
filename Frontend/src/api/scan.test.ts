import { createBaoTinApi } from "./baotin";
import { ApiProblem, type ApiClient } from "./client";
import type { ScanApiResponse } from "./contracts";
import { presentScan, scanErrorMessage } from "./scan";

export const fallbackSnapshot: ScanApiResponse = {
  scanId: "00000000-0000-4000-8000-000000000001", type: "TEXT", targetId: null,
  preview: "[Nội dung tin nhắn đã ẩn]", risk: { score: 17, level: "LOW" }, textModel: null, urlModel: null,
  rules: { score: 45, signals: ["SENSITIVE_CREDENTIAL_REQUEST"] }, urls: [], aiAvailable: false, degraded: true,
  warnings: ["TEXT_AI_UNAVAILABLE"], scannedAt: "2026-10-22T00:00:00Z"
};

describe("Live scan integration", () => {
  it("uses the explicit gateway text/url contracts and preserves raw model input", async () => {
    const request = jest.fn().mockResolvedValue(fallbackSnapshot);
    const api = createBaoTinApi({ request } as ApiClient);
    const controller = new AbortController();
    await api.scan({ type: "SMS", content: "  Xin chào\n  " }, controller.signal);
    expect(request).toHaveBeenLastCalledWith("/api/v1/scans/text", { method: "POST", body: JSON.stringify({ content: "  Xin chào\n  " }), signal: controller.signal });
    await api.scan({ type: "URL", content: "https://example.test" });
    expect(request).toHaveBeenLastCalledWith("/api/v1/scans/url", expect.objectContaining({ body: JSON.stringify({ url: "https://example.test" }) }));
    await api.scanHistory(2, 10, { type: "TEXT", riskLevel: "HIGH" });
    expect(request).toHaveBeenLastCalledWith("/api/v1/scans/history?page=2&size=10&sort=createdAt%2Cdesc&type=TEXT&riskLevel=HIGH");
  });
  it("displays server score/level verbatim and never presents unavailable AI as safe", () => {
    const result = presentScan(fallbackSnapshot);
    expect(result.score).toBe(17); // Deliberately different from the rule score: UI must not recompute.
    expect(result.level).toBe("LOW");
    expect(result.title).toContain("Chưa đủ thông tin");
    expect(result.model).toBeNull(); expect(result.signals.ai).toBeNull();
    expect(result.indicators).toContain("Nội dung nhắc đến OTP, mật khẩu hoặc mã xác thực");
  });
  it("explains rate limits separately from transport failures", () => {
    const limited = new ApiProblem({ type: "about:blank", title: "Limited", status: 429, code: "SCAN_RATE_LIMITED", correlationId: "test" });
    expect(scanErrorMessage(limited)).toContain("thử lại sau ít phút");
    expect(scanErrorMessage(new TypeError("Failed to fetch"))).toContain("Kiểm tra kết nối");
  });
});
