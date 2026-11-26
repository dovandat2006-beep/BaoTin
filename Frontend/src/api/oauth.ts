import * as Crypto from "expo-crypto";

export type OAuthProvider = "GOOGLE" | "FACEBOOK";
export type OAuthRequest = { provider: OAuthProvider; transport: "WEB" | "NATIVE"; returnUri: string; codeChallenge: string };
export type OAuthProof = { code: string; codeVerifier: string };
export function base64url(bytes: Uint8Array) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  let value = "", bits = 0, count = 0;
  for (const byte of bytes) {
    bits = (bits << 8) | byte; count += 8;
    while (count >= 6) { count -= 6; value += alphabet[(bits >>> count) & 63]; }
  }
  if (count) value += alphabet[(bits << (6 - count)) & 63];
  return value;
}
export async function createPkce() {
  const verifier = base64url(await Crypto.getRandomBytesAsync(32));
  const digest = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, verifier, { encoding: Crypto.CryptoEncoding.HEX });
  const challenge = base64url(Uint8Array.from(digest.match(/.{2}/g)!, pair => parseInt(pair, 16)));
  return { verifier, challenge };
}
export function validateAuthorizationUrl(value: string, provider: OAuthProvider) {
  const uri = new URL(value);
  const hosts = provider === "GOOGLE" ? ["accounts.google.com"] : ["www.facebook.com", "facebook.com"];
  if (uri.protocol !== "https:" || uri.username || uri.password || uri.port || !hosts.includes(uri.hostname)) throw new Error("Địa chỉ đăng nhập nhà cung cấp không hợp lệ.");
  return uri.href;
}
export function callbackTicket(value: string, expectedReturnUri: string) {
  const uri = new URL(value), expected = new URL(expectedReturnUri);
  // Meta may append this empty marker; browsers inherit it across a 303 redirect.
  // It carries no credentials. All other fragments remain invalid.
  if (uri.protocol !== expected.protocol || uri.host !== expected.host || uri.pathname !== expected.pathname || uri.username || uri.password || (uri.hash && uri.hash !== "#_=_")) throw new Error("Địa chỉ quay về không khớp yêu cầu đăng nhập.");
  if (uri.searchParams.has("error")) throw new Error("Bạn đã hủy hoặc nhà cung cấp chưa hoàn tất đăng nhập.");
  const code = uri.searchParams.get("code");
  if (!code || !/^[A-Za-z0-9_-]{20,128}$/.test(code)) throw new Error("Thiếu mã đăng nhập một lần.");
  return code;
}
export function validateReturnUri(value: string, web: boolean) {
  const uri = new URL(value);
  if ((web ? uri.protocol !== "https:" : !["https:", "baotin:"].includes(uri.protocol)) || uri.hostname.includes("*") || uri.username || uri.password || uri.search || uri.hash) throw new Error("Cần địa chỉ OAuth quay về cố định, đã đăng ký với Auth-Service.");
  return uri.href;
}
