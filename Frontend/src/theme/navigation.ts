/** Logical px/dp shared by Web and native; independent of NativeWind's rem base. */
export const compactNavigation = {
  itemHeight: 64,
  iconSize: 20,
  indicatorWidth: 56,
  indicatorHeight: 32,
  labelSize: 12,
  labelLineHeight: 16,
  labelGap: 4,
  verticalPadding: 8,
  horizontalPadding: 4,
  contentClearance: 8
} as const;

export function compactNavigationLayout(bottomInset = 0, fontScale = 1) {
  const scale = Number.isFinite(fontScale) ? Math.max(1, fontScale) : 1;
  const inset = Number.isFinite(bottomInset) ? Math.max(0, bottomInset) : 0;
  const labelLines = scale > 1.3 ? 2 : 1;
  const itemHeight = Math.max(compactNavigation.itemHeight, Math.ceil(
    compactNavigation.indicatorHeight + compactNavigation.labelGap +
    compactNavigation.labelLineHeight * scale * labelLines + 8
  ));
  const bottomPadding = Math.max(inset, compactNavigation.verticalPadding);
  const height = compactNavigation.verticalPadding + itemHeight + bottomPadding;
  return { itemHeight, bottomPadding, height, labelLines, contentPaddingBottom: height + compactNavigation.contentClearance };
}
