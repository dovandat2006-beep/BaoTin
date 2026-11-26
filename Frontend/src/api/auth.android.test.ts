import * as SecureStore from "expo-secure-store";
import { Platform } from "react-native";
import { createAuthApi } from "./auth";
jest.mock("expo-secure-store", () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

const response = (data: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => data }) as Response;
const tokens = { accessToken: "fixture-access", refreshToken: "fixture-refresh", tokenType: "Bearer", expiresIn: 600 };
describe("Android native Auth transport", () => {
  const fetchBefore = globalThis.fetch;
  const osBefore = Platform.OS;
  beforeEach(() => { jest.clearAllMocks(); Object.defineProperty(Platform, "OS", { configurable: true, value: "android" }); });
  afterEach(() => { globalThis.fetch = fetchBefore; Object.defineProperty(Platform, "OS", { configurable: true, value: osBefore }); });

  it("completes native MFA without a Web cookie or exposing refresh credentials to UI", async () => {
    const fetch = jest.fn().mockResolvedValueOnce(response({ mfaRequired: true, challenge: "fixture-challenge", mfaMethod: "TOTP" }))
      .mockResolvedValueOnce(response(tokens));
    globalThis.fetch = fetch;
    const api = createAuthApi("https://gateway.example");
    expect(await api.login("fixture-user", "fixture-password")).toEqual({ kind: "mfa", challenge: "fixture-challenge", method: "TOTP" });
    expect(SecureStore.setItemAsync).not.toHaveBeenCalled();
    const result = await api.completeMfa("fixture-challenge", "123456");
    expect(fetch.mock.calls[1][0]).toBe("https://gateway.example/api/v1/auth/mfa/challenges");
    expect(result).toEqual({ kind: "tokens", tokens: { accessToken: tokens.accessToken, csrfToken: "", tokenType: "Bearer", expiresIn: 600 } });
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith("baotin-refresh", tokens.refreshToken);
  });
  it("restores a native session after process restart from SecureStore and saves rotation", async () => {
    (SecureStore.getItemAsync as jest.Mock).mockResolvedValue("stored-refresh");
    globalThis.fetch = jest.fn().mockResolvedValue(response({ ...tokens, refreshToken: "rotated-refresh" }));
    const restored = await createAuthApi("https://gateway.example").refresh();
    expect(restored?.accessToken).toBe(tokens.accessToken);
    expect(globalThis.fetch).toHaveBeenCalledWith("https://gateway.example/api/v1/auth/refresh", expect.objectContaining({ body: JSON.stringify({ refreshToken: "stored-refresh" }) }));
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith("baotin-refresh", "rotated-refresh");
  });
  it("clears an expired or revoked native refresh credential", async () => {
    (SecureStore.getItemAsync as jest.Mock).mockResolvedValue("revoked-refresh");
    globalThis.fetch = jest.fn().mockResolvedValue(response({ status: 401, title: "Expired", detail: "Expired" }, 401));
    expect(await createAuthApi("https://gateway.example").refresh()).toBeNull();
    expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith("baotin-refresh");
  });
  it("retains a refresh credential after temporary network loss", async () => {
    (SecureStore.getItemAsync as jest.Mock).mockResolvedValue("stored-refresh");
    globalThis.fetch = jest.fn().mockRejectedValue(new TypeError("Network request failed"));
    await expect(createAuthApi("https://gateway.example").refresh()).rejects.toThrow("Network request failed");
    expect(SecureStore.deleteItemAsync).not.toHaveBeenCalled();
  });
  it("always clears the native credential on logout even if the network fails", async () => {
    globalThis.fetch = jest.fn().mockRejectedValue(new TypeError("Network request failed"));
    await expect(createAuthApi("https://gateway.example").logout(tokens.accessToken)).rejects.toThrow();
    expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith("baotin-refresh");
    expect(globalThis.fetch).toHaveBeenCalledWith("https://gateway.example/api/v1/auth/logout", expect.objectContaining({ method: "POST" }));
  });
});
