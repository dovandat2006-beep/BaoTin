import { compactNavigation, compactNavigationLayout } from "./navigation";

describe("compact Web/native navigation geometry", () => {
  test("uses the same bounded compact dimensions without rem conversion", () => {
    expect(compactNavigationLayout()).toEqual({ itemHeight: 64, bottomPadding: 8, height: 80, labelLines: 1, contentPaddingBottom: 88 });
    expect(compactNavigation.iconSize).toBe(20);
    expect(compactNavigation.indicatorWidth).toBe(56);
    expect(compactNavigation.indicatorHeight).toBe(32);
  });
  test.each([0, 16, 24, 34])( "adds safe area once, outside unchanged touch targets (%s)", inset => {
    const layout = compactNavigationLayout(inset);
    expect(layout.itemHeight).toBe(64);
    expect(layout.height).toBe(8 + 64 + Math.max(8, inset));
    expect(layout.contentPaddingBottom - layout.height).toBe(8);
  });
  test.each([1.5, 2, 3])("keeps text scaling instead of clipping labels (%s)", scale => {
    const layout = compactNavigationLayout(34, scale);
    expect(layout.labelLines).toBe(2);
    expect(layout.itemHeight).toBeGreaterThanOrEqual(32 + 4 + 16 * scale * 2 + 8);
    expect(layout.height).toBe(layout.itemHeight + 42);
  });
  test("invalid measurements cannot create oversized or negative navigation", () => {
    expect(compactNavigationLayout(NaN, Infinity)).toEqual(compactNavigationLayout());
    expect(compactNavigationLayout(-30, -1)).toEqual(compactNavigationLayout());
  });
});
