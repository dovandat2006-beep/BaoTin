import palette from "../../color-tokens.json";

function luminance(hex: string) {
  const values = [1, 3, 5].map((index) => Number.parseInt(hex.slice(index, index + 2), 16) / 255).map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return values[0]! * 0.2126 + values[1]! * 0.7152 + values[2]! * 0.0722;
}
function contrast(a: string, b: string) {
  const values = [luminance(a), luminance(b)].sort((left, right) => right - left);
  return (values[0]! + 0.05) / (values[1]! + 0.05);
}

describe("BaoTin Pine semantic palette", () => {
  it.each([
    ["light ink", palette.light.ink, palette.light.canvas, 4.5],
    ["light muted", palette.light.muted, palette.light.panel, 4.5],
    ["light placeholder", palette.light.placeholder, palette.light.panel, 4.5],
    ["light primary", palette.light.onPrimary, palette.light.accent, 4.5],
    ["light safe", palette.light.safe, palette.light.safeSoft, 4.5],
    ["light warning", palette.light.warning, palette.light.warningSoft, 4.5],
    ["light danger", palette.light.danger, palette.light.dangerSoft, 4.5],
    ["dark ink", palette.dark.ink, palette.dark.canvas, 4.5],
    ["dark muted", palette.dark.muted, palette.dark.panel, 4.5],
    ["dark placeholder", palette.dark.placeholder, palette.dark.panel, 4.5],
    ["dark primary", palette.dark.onPrimary, palette.dark.accent, 4.5],
    ["dark safe", palette.dark.safe, palette.dark.safeSoft, 4.5],
    ["dark warning", palette.dark.warning, palette.dark.warningSoft, 4.5],
    ["dark danger", palette.dark.danger, palette.dark.dangerSoft, 4.5],
    ["light control boundary", palette.light.control, palette.light.panel, 3],
    ["dark control boundary", palette.dark.control, palette.dark.panel, 3]
  ])("%s meets required contrast", (_name, foreground, background, minimum) => {
    expect(contrast(foreground, background)).toBeGreaterThanOrEqual(minimum);
  });
});
