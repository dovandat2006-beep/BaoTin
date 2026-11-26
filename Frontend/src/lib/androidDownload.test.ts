import { validateAndroidDownload } from "./androidDownload";
const sha = "a".repeat(64);
const manifest = { schemaVersion: 1, version: "0.1.0", versionCode: 1, bytes: 120000000, sha256: sha, url: `/downloads/baotin-${sha}.apk`, sourceSnapshotSha256: "b".repeat(64), gatewayOrigin: "https://baotin.rimiprod.me", signingCertificateSha256: "c".repeat(64), publishedAt: "2026-10-29T00:00:00.000Z" };
test("accepts an exact same-origin content-addressed verified APK manifest", () => {
  expect(validateAndroidDownload(manifest)).toEqual(manifest);
});
test("rejects remote links, path traversal, AAB/Hermes substitutes and malformed metadata", () => {
  for (const patch of [{ url: "https://other.example/app.apk" }, { url: "/downloads/../private.apk" }, { url: `/downloads/baotin-${sha}.aab` }, { url: `/downloads/baotin-${sha}.apk?token=bad` }, { sha256: "bad" }, { bytes: 0 }, { versionCode: -1 }, { sourceSnapshotSha256: "bad" }, { signingCertificateSha256: "bad" }]) expect(validateAndroidDownload({ ...manifest, ...patch })).toBeNull();
  expect(validateAndroidDownload(null)).toBeNull();
  expect(validateAndroidDownload({})).toBeNull();
});
