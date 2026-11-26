import * as SecureStore from "expo-secure-store";
jest.mock("expo-secure-store", () => ({ setItemAsync: jest.fn(), getItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));
import { Platform } from "react-native";
import { createAuthApi, toAuthSession } from "./auth";

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: "",
    json: async () => body
  } as Response;
}

describe("Auth API adapter", () => {
  const originalFetch = globalThis.fetch;
  const originalOS = Platform.OS;
  it("email MFA resend accepts the contract's empty 202 response", async () => {
    const json = jest.fn().mockRejectedValue(new SyntaxError("Unexpected end of JSON input"));
    globalThis.fetch = jest.fn().mockResolvedValue({ ok: true, status: 202, json });
    await expect(createAuthApi("https://gateway.example").resendMfa("e1.fixture")).resolves.toBeUndefined();
    expect(json).not.toHaveBeenCalled();
  });
  beforeEach(() => Object.defineProperty(Platform, "OS", { configurable: true, value: "web" }));

  afterEach(() => {
    Object.defineProperty(Platform, "OS", { configurable: true, value: originalOS });
    globalThis.fetch = originalFetch;
    jest.restoreAllMocks();
  });

  it("uses the web login contract and keeps the CSRF token in memory", async () => {
    const fetchMock = jest.fn()
      .mockResolvedValueOnce(jsonResponse({ accessToken: "access-1", csrfToken: "csrf-1", tokenType: "Bearer", expiresIn: 600 }))
      .mockResolvedValueOnce(jsonResponse({ accessToken: "access-2", csrfToken: "csrf-2", tokenType: "Bearer", expiresIn: 600 }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const api = createAuthApi("https://gateway.example/");
    const result = await api.login("an.nguyen", "mat-khau-12-ky-tu");
    expect(result.kind).toBe("tokens");
    const loginInit = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(loginInit.credentials).toBe("include");
    expect(JSON.parse(String(loginInit.body))).toEqual({ identifier: "an.nguyen", password: "mat-khau-12-ky-tu" });

    const refreshed = await api.refresh();
    expect(refreshed?.accessToken).toBe("access-2");
    expect(fetchMock.mock.calls[1]?.[0]).toBe("https://gateway.example/api/v1/auth/web/refresh");
    expect((fetchMock.mock.calls[1]?.[1] as RequestInit & { headers: Record<string, string> }).headers["X-CSRF-TOKEN"]).toBe("csrf-1");
  });

  it("maps a local phone number to E.164 when registering", async () => {
    const fetchMock = jest.fn().mockResolvedValue(jsonResponse({
      id: "user-1",
      username: "an.nguyen",
      email: null,
      phone: "+84909123456",
      displayName: "an.nguyen",
      role: "USER",
      emailVerified: false,
      mfaEnabled: false,
      mfaMethod: "NONE"
    }, 201));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const api = createAuthApi("https://gateway.example");
    const profile = await api.register({ username: "an.nguyen", contact: "0909 123 456", password: "mat-khau-12-ky-tu" });
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(JSON.parse(String(init.body))).toEqual({
      username: "an.nguyen",
      displayName: "an.nguyen",
      password: "mat-khau-12-ky-tu",
      phone: "+84909123456"
    });
    expect(profile.role).toBe("USER");
  });

  it("maps an MFA challenge without treating it as a session", async () => {
    const fetchMock = jest.fn().mockResolvedValue(jsonResponse({
      mfaRequired: true,
      challenge: "challenge-1",
      mfaMethod: "EMAIL"
    }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const api = createAuthApi("https://gateway.example");
    await expect(api.login("an.nguyen", "mat-khau-12-ky-tu")).resolves.toEqual({
      kind: "mfa",
      challenge: "challenge-1",
      method: "EMAIL"
    });
  });

  it("sends the bearer token and cookie credentials when reading the profile", async () => {
    const fetchMock = jest.fn().mockResolvedValue(jsonResponse({
      id: "user-1",
      username: "an.nguyen",
      email: null,
      displayName: "An Nguyễn",
      role: "USER",
      emailVerified: true,
      mfaEnabled: false
    }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const api = createAuthApi("https://gateway.example");
    await expect(api.profile("access-1")).resolves.toMatchObject({ displayName: "An Nguyễn" });

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(init.credentials).toBe("include");
    expect((init.headers as Headers).get("Authorization")).toBe("Bearer access-1");
  });

  it("returns null when the refresh cookie is rejected", async () => {
    const fetchMock = jest.fn()
      .mockResolvedValueOnce(jsonResponse({ accessToken: "access-1", csrfToken: "csrf-1", tokenType: "Bearer", expiresIn: 600 }))
      .mockResolvedValueOnce(jsonResponse({ status: 401 }, 401));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const api = createAuthApi("https://gateway.example");
    await api.login("an.nguyen", "mat-khau-12-ky-tu");
    await expect(api.refresh()).resolves.toBeNull();
  });

  it("returns the server role instead of trusting display labels", () => {
    expect(toAuthSession({
      id: "user-1",
      username: "admin",
      email: null,
      displayName: "admin",
      role: "MODERATOR",
      emailVerified: true,
      mfaEnabled: true
    }).role).toBe("MODERATOR");
  });
});

test("native login and rotation use SecureStore and native endpoints", async () => {
 const previous = globalThis.fetch; const os = Platform.OS;
 Object.defineProperty(Platform, "OS", { configurable: true, value: "android" });
 const tokens = { accessToken: "access", refreshToken: "refresh", tokenType: "Bearer", expiresIn: 600 };
 const fetchMock = jest.fn().mockResolvedValueOnce(jsonResponse({ tokens })).mockResolvedValueOnce(jsonResponse({ ...tokens, refreshToken: "rotated" }));
 globalThis.fetch = fetchMock;
 (SecureStore.getItemAsync as jest.Mock).mockResolvedValue("refresh");
 try {
 const api = createAuthApi("https://gateway.example"); await api.login("user", "password"); await api.refresh();
 expect(fetchMock.mock.calls[0][0]).toBe("https://gateway.example/api/v1/auth/login");
 expect(fetchMock.mock.calls[1][0]).toBe("https://gateway.example/api/v1/auth/refresh");
 expect(SecureStore.setItemAsync).toHaveBeenLastCalledWith("baotin-refresh", "rotated");
 } finally { globalThis.fetch = previous; Object.defineProperty(Platform, "OS", { configurable: true, value: os }); }
});
