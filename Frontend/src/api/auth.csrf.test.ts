import { Platform } from "react-native";
jest.mock("expo-secure-store", () => ({ setItemAsync: jest.fn(), getItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));
import { createAuthApi } from "./auth";

const origin = "https://baotin.rimiprod.me";
const tokens = { accessToken: "fixture-access", csrfToken: "fixture-old", tokenType: "Bearer", expiresIn: 600 };
const response = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response;
const originalFetch = globalThis.fetch;
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, "document");
const originalOS = Platform.OS;
let cookie = "";

beforeEach(() => {
  cookie = "__Host-baotin-csrf=fixture-old";
  Object.defineProperty(Platform, "OS", { configurable: true, value: "web" });
  Object.defineProperty(globalThis, "window", { configurable: true, value: { location: { href: origin + "/", origin } } });
  Object.defineProperty(globalThis, "document", { configurable: true, value: { get cookie() { return cookie; } } });
});
afterEach(() => {
  globalThis.fetch = originalFetch;
  Object.defineProperty(Platform, "OS", { configurable: true, value: originalOS });
  for (const [name, descriptor] of [["window", originalWindow], ["document", originalDocument]] as const) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else Reflect.deleteProperty(globalThis, name);
  }
});

for (const action of ["refresh", "logout"] as const) {
  test(`${action} uses the current same-origin cookie after another tab rotates it`, async () => {
    const fetcher = jest.fn().mockResolvedValueOnce(response(tokens)).mockResolvedValueOnce(response(tokens, action === "logout" ? 204 : 200));
    globalThis.fetch = fetcher;
    const api = createAuthApi(origin);
    await api.login("fixture-user", "fixture-password");
    cookie = "__Host-baotin-csrf=fixture-rotated-in-other-tab";
    if (action === "refresh") await api.refresh(); else await api.logout(tokens.accessToken);
    expect(fetcher.mock.calls[1][1].headers["X-CSRF-TOKEN"]).toBe("fixture-rotated-in-other-tab");
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
}

test("relative same-origin Gateway refresh also follows the rotated cookie", async () => {
  const fetcher = jest.fn().mockResolvedValueOnce(response(tokens)).mockResolvedValueOnce(response(tokens));
  globalThis.fetch = fetcher;
  const api = createAuthApi(""); await api.login("fixture-user", "fixture-password");
  cookie = "__Host-baotin-csrf=fixture-new"; await api.refresh();
  expect(fetcher.mock.calls[1][1].headers["X-CSRF-TOKEN"]).toBe("fixture-new");
});

test("a different Gateway origin keeps its server nonce instead of this page's cookie", async () => {
  const fetcher = jest.fn().mockResolvedValueOnce(response(tokens)).mockResolvedValueOnce(response(tokens));
  globalThis.fetch = fetcher;
  const api = createAuthApi("https://other-gateway.example"); await api.login("fixture-user", "fixture-password");
  cookie = "__Host-baotin-csrf=unrelated-origin-cookie"; await api.refresh();
  expect(fetcher.mock.calls[1][1].headers["X-CSRF-TOKEN"]).toBe("fixture-old");
});

test("an unrelated page cookie cannot bootstrap refresh at another Gateway origin", async () => {
  cookie = "__Host-baotin-csrf=unrelated-origin-cookie";
  const fetcher = jest.fn(); globalThis.fetch = fetcher;
  expect(await createAuthApi("https://other-gateway.example").refresh()).toBeNull();
  expect(fetcher).not.toHaveBeenCalled();
});

test("absent readable cookie preserves the in-memory nonce compatibility path", async () => {
  const fetcher = jest.fn().mockResolvedValueOnce(response(tokens)).mockResolvedValueOnce(response(tokens));
  globalThis.fetch = fetcher;
  const api = createAuthApi(origin); await api.login("fixture-user", "fixture-password");
  cookie = ""; await api.refresh();
  expect(fetcher.mock.calls[1][1].headers["X-CSRF-TOKEN"]).toBe("fixture-old");
});

test("a guest without a CSRF nonce makes no refresh request", async () => {
  cookie = ""; const fetcher = jest.fn(); globalThis.fetch = fetcher;
  expect(await createAuthApi(origin).refresh()).toBeNull(); expect(fetcher).not.toHaveBeenCalled();
});

test("a rejected refresh is not silently replayed with another nonce", async () => {
  const fetcher = jest.fn().mockResolvedValueOnce(response(tokens)).mockResolvedValueOnce(response({ code: "CSRF_INVALID" }, 403));
  globalThis.fetch = fetcher;
  const api = createAuthApi(origin); await api.login("fixture-user", "fixture-password");
  cookie = "__Host-baotin-csrf=fixture-rotated";
  expect(await api.refresh()).toBeNull(); expect(fetcher).toHaveBeenCalledTimes(2);
});
