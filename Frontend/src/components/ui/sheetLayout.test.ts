import { nativeCompactSheetHeight } from "./sheetLayout";

describe("native compact sheet height", () => {
  test.each([[800, 24], [844, 47], [600, 50], [320, 24]])("gives flex form a bounded nonzero height at %s/%s", (height, top) => {
    const result = nativeCompactSheetHeight(height, top);
    expect(result).toBeGreaterThan(0);
    expect(result).toBeLessThanOrEqual(height * .92);
    expect(result + top + 8).toBeLessThanOrEqual(height);
  });
  test("keeps usable report form space below header/footer", () => {
    expect(nativeCompactSheetHeight(844, 24) - 100 - 100).toBeGreaterThan(500);
  });
  test("invalid or constrained dimensions never produce negative/infinite geometry", () => {
    expect(nativeCompactSheetHeight(0)).toBe(0);
    expect(nativeCompactSheetHeight(NaN)).toBe(0);
    expect(nativeCompactSheetHeight(100, 200)).toBe(0);
    expect(nativeCompactSheetHeight(800, -10)).toBe(nativeCompactSheetHeight(800));
  });
});
