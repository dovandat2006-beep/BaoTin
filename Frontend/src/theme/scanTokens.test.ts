import palette from "../../color-tokens.json";

function luminance(hex: string) {
  const channels = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map(v => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  return channels[0]! * .2126 + channels[1]! * .7152 + channels[2]! * .0722;
}

describe.each(["light", "dark"] as const)("Scan M3 %s", mode => {
  const colors = palette[mode];
  it.each([
    ["mdOnPrimary", "mdPrimary"],
    ["mdOnSecondaryContainer", "mdSecondaryContainer"],
    ["mdOnSurface", "mdSurfaceLow"],
    ["mdOnSurfaceVariant", "mdSurfaceLowest"],
    ["mdOnSurfaceVariant", "mdSurfaceLow"],
    ["mdOnError", "mdError"],
    ["mdOnErrorContainer", "mdErrorContainer"],
    ["mdRiskLow", "mdRiskLowContainer"],
    ["mdRiskMedium", "mdRiskMediumContainer"]] as const)("%s on %s meets normal-text AA", (foreground, background) => {
    const [low, high] = [luminance(colors[foreground]), luminance(colors[background])].sort((a, b) => a - b);
    expect((high! + .05) / (low! + .05)).toBeGreaterThanOrEqual(4.5);
  });
});
