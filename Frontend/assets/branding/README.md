# BaoTin supplied branding

- `originals/baotin-app-logo.png`: untouched supplied APK artwork.
- `originals/baotin-web-logo.png`: untouched supplied Web artwork.
- `app-mark.png`: APK artwork cropped to its nontransparent bounds.
- `android-icon.png`: opaque1024px legacy launcher icon on the Material3 light surface.
- `android-adaptive-foreground.png`: transparent1024px foreground, content within the Android adaptive-icon safe zone. `app.json` supplies the background color.
- `web-wordmark.png`: supplied Web artwork cropped to246×78px without transparent portrait margins; used by `BrandWordmark` in the existing Web brand slots. Original colors are retained in both themes; native header text is unchanged.

Brand artwork is not a replacement for the application's Font Awesome action icons. No authentication, navigation or API behavior is embedded in these assets. Regression: `node --test scripts/branding.test.cjs` and the shell/navigation browser checks.
