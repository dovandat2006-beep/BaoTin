import { createMockScanResult, riskLevelForScore, scanTypeLabel, scanTypeLabels } from "./risk";

describe("mock risk scoring", () => {
  it("flags credential-pressure content", () => {
    const result = createMockScanResult("SMS", "Xác minh ngay OTP và chuyển khoản tại https://free-gift.site");
    expect(["HIGH", "CRITICAL"]).toContain(result.level);
    expect(result.ephemeral).toBe(true);
    expect(result.threatTargetId).toBeNull();
  });

  it("keeps known domains low risk", () => {
    const result = createMockScanResult("URL", "https://microsoft.com/security");
    expect(result.level).toBe("LOW");
    expect(result.threatTargetId).toBeTruthy();
  });

  it("maps backend score thresholds exactly", () => {
    expect(riskLevelForScore(24)).toBe("LOW");
    expect(riskLevelForScore(25)).toBe("MEDIUM");
    expect(riskLevelForScore(50)).toBe("HIGH");
    expect(riskLevelForScore(75)).toBe("CRITICAL");
  });

  it("does not require AI for phone reputation lookup", () => {
    const result = createMockScanResult("PHONE", "0909123456");
    expect(result.signals.ai).toBeNull();
    expect(result.model).toBeNull();
    expect(result.ephemeral).toBe(false);
    expect(result.threatTargetId).toBeTruthy();
  });

  it("keeps unknown target types visible instead of mapping them away", () => {
    expect(scanTypeLabel("URL")).toBe(scanTypeLabels.URL);
    expect(scanTypeLabel("NEW_TYPE")).toBe("NEW_TYPE");
  });

  it("does not treat prototype keys as known target types", () => {
    for (const value of ["__proto__", "constructor", "toString", "hasOwnProperty"]) {
      expect(scanTypeLabel(value)).toBe(value);
    }
  });
});






