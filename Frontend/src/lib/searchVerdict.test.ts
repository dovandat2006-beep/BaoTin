import { searchVerdict } from "./searchVerdict";

describe("public lookup verdict", () => {
  it.each([
    ["BENIGN", null, null, "Không có rủi ro"],
    ["SUSPICIOUS", null, null, "Có rủi ro"],
    ["UNKNOWN", null, null, "Chưa có đánh giá"],
    [undefined, null, null, "Chưa có đánh giá"],
    ["BENIGN", 90, "HIGH", "Có rủi ro"],
    ["SUSPICIOUS", 10, "LOW", "Có rủi ro"],
    [undefined, 15, "LOW", "Có rủi ro"],
    [undefined, 0, "LOW", "Không có rủi ro"],
    [undefined, 45, "MEDIUM", "Có rủi ro"],
  ] as const)("label=%s score=%s level=%s -> %s", (label, score, level, expected) => {
    expect(searchVerdict({ metadata: label ? { contentWarning: label } : {}, riskScore: score, riskLevel: level })).toBe(expected);
  });
});
