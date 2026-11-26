export type AndroidDownload = {
  schemaVersion: 1; version: string; versionCode: number; bytes: number; sha256: string;
  url: string; sourceSnapshotSha256: string; gatewayOrigin: string; signingCertificateSha256: string; publishedAt: string;
};
export function validateAndroidDownload(value: unknown): AndroidDownload | null {
  if (!value || typeof value !== "object") return null;
  const m = value as Record<string, unknown>;
  const hash = (x: unknown) => typeof x === "string" && /^[a-f0-9]{64}$/.test(x);
  if (m.schemaVersion !== 1 || typeof m.version !== "string" || !/^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$/.test(m.version)) return null;
  if (typeof m.versionCode !== "number" || !Number.isSafeInteger(m.versionCode) || m.versionCode < 1 || typeof m.bytes !== "number" || !Number.isSafeInteger(m.bytes) || m.bytes < 1 || m.bytes > 1024 ** 3) return null;
  if (!hash(m.sha256) || !hash(m.sourceSnapshotSha256) || !hash(m.signingCertificateSha256) || m.url !== `/downloads/baotin-${m.sha256}.apk`) return null;
  try { if (typeof m.gatewayOrigin !== "string") return null; const url = new URL(m.gatewayOrigin); if (url.protocol !== "https:" || url.origin !== m.gatewayOrigin || url.username || url.password) return null; } catch { return null; }
  if (typeof m.publishedAt !== "string" || !m.publishedAt.endsWith("Z") || !Number.isFinite(Date.parse(m.publishedAt))) return null;
  return { schemaVersion: 1, version: m.version, versionCode: m.versionCode, bytes: m.bytes, sha256: m.sha256 as string, url: m.url as string, sourceSnapshotSha256: m.sourceSnapshotSha256 as string, gatewayOrigin: m.gatewayOrigin as string, signingCertificateSha256: m.signingCertificateSha256 as string, publishedAt: m.publishedAt };
}
