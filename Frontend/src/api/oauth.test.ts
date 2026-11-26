import { base64url, callbackTicket, validateAuthorizationUrl, validateReturnUri } from "./oauth";

test("PKCE encoding is unpadded base64url with 43 characters for 32 bytes", () => {
  expect(base64url(new Uint8Array(32))).toBe("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
  expect(base64url(new Uint8Array([255, 255, 255]))).toBe("____");
});
test("provider URL cannot redirect to a client-supplied origin", () => {
  expect(validateAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth?state=fixture", "GOOGLE")).toContain("accounts.google.com");
  for (const uri of ["http://accounts.google.com/auth", "https://accounts.google.com.evil.test/auth", "https://user@accounts.google.com/auth", "https://accounts.google.com:444/auth"]) expect(() => validateAuthorizationUrl(uri, "GOOGLE")).toThrow();
});
test("callback tickets require the exact return origin/path and reject cancellation", () => {
  const origin = "https://baotin.example/auth/callback", code = "a".repeat(43);
  expect(callbackTicket(origin + "?code=" + code, origin)).toBe(code);
  expect(() => callbackTicket("https://evil.test/auth/callback?code=" + code, origin)).toThrow();
  expect(() => callbackTicket(origin + "?error=oauth_denied", origin)).toThrow();
  expect(() => callbackTicket(origin + "?code=bad", origin)).toThrow();
});
test("Facebook's empty redirect fragment preserves the one-use query ticket, never fragment credentials", () => {
  const origin = "https://baotin.example/auth/callback", code = "a".repeat(43);
  expect(callbackTicket(origin + "?code=" + code + "#_=_", origin)).toBe(code);
  for (const fragment of ["#code=" + code, "#access_token=secret", "#_=_extra", "#state=fixture"]) {
    expect(() => callbackTicket(origin + "?code=" + code + fragment, origin)).toThrow();
  }
  expect(() => callbackTicket(origin + "?error=oauth_denied#_=_", origin)).toThrow();
  expect(() => callbackTicket("https://evil.test/auth/callback?code=" + code + "#_=_", origin)).toThrow();
});
test("fixed web/native returns reject wildcards, query strings and insecure transports", () => {
  expect(validateReturnUri("baotin://auth/callback", false)).toBe("baotin://auth/callback");
  expect(validateReturnUri("https://baotin.example/auth/native-callback", false)).toBe("https://baotin.example/auth/native-callback");
  for (const uri of ["http://localhost/callback", "exp://host/callback", "https://baotin.example/callback?next=admin"]) expect(() => validateReturnUri(uri, true)).toThrow();
});
