/** A native flex ScrollView needs a bounded sheet height, not maxHeight alone. */
export function nativeCompactSheetHeight(windowHeight: number, topInset = 0) {
  const height = Number.isFinite(windowHeight) ? Math.max(0, windowHeight) : 0;
  const top = Number.isFinite(topInset) ? Math.max(0, topInset) : 0;
  return Math.max(0, Math.floor(Math.min(height * 0.92, height - top - 8)));
}
