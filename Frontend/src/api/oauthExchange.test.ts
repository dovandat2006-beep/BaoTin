import { Platform } from "react-native";
import { createAuthApi } from "./auth";

jest.mock("./session", () => ({ readRefreshToken: jest.fn(), saveRefreshToken: jest.fn(), clearRefreshToken: jest.fn() }));
const response = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as Response;
const originalFetch = globalThis.fetch, originalOS = Platform.OS;
afterEach(() => { globalThis.fetch = originalFetch; Object.defineProperty(Platform, "OS", { configurable: true, value: originalOS }); });
test("web OAuth exchanges only a ticket plus PKCE and retains HttpOnly cookie transport", async () => {
  Object.defineProperty(Platform, "OS", { configurable: true, value: "web" });
  const fetch = jest.fn().mockResolvedValue(response({ accessToken: "fixture", csrfToken: "csrf", tokenType: "Bearer", expiresIn: 600 })); globalThis.fetch = fetch;
  expect((await createAuthApi("https://gateway.example").oauthExchange("ticket-fixture", "verifier-fixture")).kind).toBe("tokens");
  expect(fetch.mock.calls[0]?.[0]).toBe("https://gateway.example/api/v1/auth/web/oauth2/tokens");
  expect(fetch.mock.calls[0]?.[1].credentials).toBe("include");
  expect(JSON.parse(fetch.mock.calls[0]?.[1].body)).toEqual({ code: "ticket-fixture", codeVerifier: "verifier-fixture" });
});
test("native OAuth honors MFA and contact onboarding before storing any tokens", async () => {
  Object.defineProperty(Platform, "OS", { configurable: true, value: "android" });
  const fetch = jest.fn().mockResolvedValueOnce(response({ onboardingRequired: true })).mockResolvedValueOnce(response({ mfaRequired: true, challenge: "challenge-fixture", mfaMethod: "TOTP" }));globalThis.fetch = fetch;
  const api = createAuthApi("https://gateway.example");
  expect(await api.oauthExchange("ticket-fixture", "verifier-fixture")).toEqual({ kind: "onboarding" });
  expect(await api.oauthExchange("ticket-fixture", "verifier-fixture", "fixture@example.test")).toEqual({ kind: "mfa", challenge: "challenge-fixture", method: "TOTP" });
  expect(fetch.mock.calls[1]?.[0]).toBe("https://gateway.example/api/v1/auth/oauth2/tokens");
});
test("explicit OAuth linking sends only reviewed request fields and caller bearer", async () => {
  const fetch = jest.fn().mockResolvedValue(response({ authorizationUrl: "https://accounts.google.com" })); globalThis.fetch = fetch;
  await createAuthApi("https://gateway.example").oauthRequest({ provider: "GOOGLE", transport: "WEB", returnUri: "https://baotin.example/auth/callback", codeChallenge: "a".repeat(43) }, "owner-token-fixture");
  expect(fetch.mock.calls[0]?.[0]).toBe("https://gateway.example/api/v1/auth/oauth2/link-requests");
  expect((fetch.mock.calls[0]?.[1].headers as Headers).get("Authorization")).toBe("Bearer owner-token-fixture");
});
