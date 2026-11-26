import { ApiProblem, createApiClient, problemFromResponse } from "./client";

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: "",
    json: async () => body
  } as Response;
}

describe("API client", () => {
  const originalFetch = globalThis.fetch;

  it("accepts empty 202 responses from queued Auth actions without parsing JSON", async () => {
    const json = jest.fn().mockRejectedValue(new SyntaxError("Unexpected end of JSON input"));
    globalThis.fetch = jest.fn().mockResolvedValue({ ok: true, status: 202, json });
    await expect(createApiClient("https://gateway.example").request("/api/v1/auth/forgot-password", { method: "POST", body: JSON.stringify({ identifier: "fixture@example.test" }) }, "empty")).resolves.toBeUndefined();
    expect(json).not.toHaveBeenCalled();
  });

  it("preserves JSON representations on 202 for other contracts", async () => {
    globalThis.fetch = jest.fn().mockResolvedValue(jsonResponse({ state: "accepted-fixture" }, 202));
    await expect(createApiClient("https://gateway.example").request("/api/v1/fixture-task")).resolves.toEqual({ state: "accepted-fixture" });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    jest.restoreAllMocks();
  });

  it("refreshes once on 401 and retries with the new access token", async () => {
    let token = "old-token";
    const fetchMock = jest.fn()
      .mockResolvedValueOnce(jsonResponse({ code: "UNAUTHORIZED" }, 401))
      .mockResolvedValueOnce(jsonResponse({ items: [] }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    const refresh = jest.fn().mockImplementation(async () => {
      token = "new-token";
      return token;
    });
    const client = createApiClient("https://gateway.example/", async () => token, refresh);

    await expect(client.request("/api/v1/notifications")).resolves.toEqual({ items: [] });
    expect(refresh).toHaveBeenCalledTimes(1);
    const firstHeaders = (fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Headers;
    const secondHeaders = (fetchMock.mock.calls[1]?.[1] as RequestInit).headers as Headers;
    expect(firstHeaders.get("Authorization")).toBe("Bearer old-token");
    expect(secondHeaders.get("Authorization")).toBe("Bearer new-token");
  });

  it("throws a typed ApiProblem when refresh cannot recover", async () => {
    globalThis.fetch = jest.fn().mockResolvedValueOnce(jsonResponse({
      type: "about:blank",
      title: "Unauthorized",
      status: 401,
      detail: "Missing token",
      instance: "/api/v1/notifications",
      code: "UNAUTHORIZED",
      correlationId: "corr-1"
    }, 401)) as unknown as typeof fetch;
    const client = createApiClient("https://gateway.example", async () => null, async () => null);

    await expect(client.request("/api/v1/notifications")).rejects.toMatchObject({
      name: "ApiProblem",
      problem: { status: 401, code: "UNAUTHORIZED", correlationId: "corr-1" }
    });
  });

  it("falls back to a problem when an error body is not JSON", async () => {
    const response = {
      status: 503,
      statusText: "Service Unavailable",
      json: async () => { throw new SyntaxError("not json"); }
    } as unknown as Response;
    expect((await problemFromResponse(response)).code).toBe("REQUEST_FAILED");
    expect(new ApiProblem(await problemFromResponse(response)).message).toBe("Service Unavailable");
  });

  it("clears authority when refresh fails instead of retaining privileged UI", async () => {
    globalThis.fetch = jest.fn().mockResolvedValue(jsonResponse({ status: 401 }, 401));
    const expired = jest.fn();
    const client = createApiClient("https://gateway.example", async () => "old", async () => { throw new Error("offline"); }, expired);
    await expect(client.request("/api/v1/auth/admin/users")).rejects.toThrow("offline");
    expect(expired).toHaveBeenCalledTimes(1);
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });

  it("revalidates server authority on 403 without retrying a forbidden operation", async () => {
    globalThis.fetch = jest.fn().mockResolvedValue(jsonResponse({ status: 403, code: "ADMIN_REQUIRED" }, 403));
    const forbidden = jest.fn().mockResolvedValue(undefined);
    const refresh = jest.fn();
    const client = createApiClient("https://gateway.example", async () => "old", refresh, undefined, forbidden);
    await expect(client.request("/api/v1/auth/admin/users")).rejects.toMatchObject({ problem: { status: 403 } });
    expect(forbidden).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });

  it("discards a private response that finishes after logout/account change", async () => {
    let generation = 1;
    globalThis.fetch = jest.fn().mockImplementation(async () => {
      generation++;
      return jsonResponse({ items: [{ title: "previous account private data" }] });
    });
    const client = createApiClient("https://gateway.example", async () => "token", undefined, undefined, undefined, () => generation);
    await expect(client.request("/api/v1/notifications")).rejects.toThrow("Phiên đăng nhập đã thay đổi.");
  });

  it("never retries an old operation under a newly signed-in account", async () => {
    let generation = 1;
    globalThis.fetch = jest.fn().mockResolvedValue(jsonResponse({ status: 401 }, 401));
    const refresh = jest.fn().mockImplementation(async () => { generation++; return "other-account-token"; });
    const client = createApiClient("https://gateway.example", async () => "token", refresh, undefined, undefined, () => generation);
    await expect(client.request("/api/v1/notifications/read-all", { method: "POST" })).rejects.toThrow("Phiên đăng nhập đã thay đổi.");
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });

  it("does not replay one-time-factor actions or expire a healthy session for a wrong factor", async () => {
    globalThis.fetch = jest.fn().mockResolvedValue(jsonResponse({ status: 401, code: "INVALID_CREDENTIALS" }, 401));
    const refresh = jest.fn().mockResolvedValue("rotated");
    const expired = jest.fn(); const revalidate = jest.fn().mockResolvedValue(undefined);
    const client = createApiClient("https://gateway.example", async () => "token", refresh, expired, revalidate);
    await expect(client.request("/api/v1/auth/mfa/enrollments/confirmation", { method: "POST", credentialAction: true })).rejects.toMatchObject({ problem: { status: 401 } });
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
    expect(expired).not.toHaveBeenCalled();
    expect(revalidate).toHaveBeenCalledTimes(1);
  });
});

test("multipart leaves boundary to runtime and blob downloads reuse authentication", async () => {
 const previous = globalThis.fetch;
 const blob = new Blob(["evidence"]);
 const fetchMock = jest.fn().mockResolvedValue({ ok: true, status: 200, blob: async () => blob, json: async () => ({}) });
 globalThis.fetch = fetchMock;
 try {
  const client = createApiClient("https://gateway.example", async () => "token");
  await client.request("/upload", { method: "POST", body: new FormData() });
  expect((fetchMock.mock.calls[0][1].headers as Headers).has("Content-Type")).toBe(false);
  expect(await client.request("/download", {}, "blob")).toBe(blob);
  expect((fetchMock.mock.calls[1][1].headers as Headers).get("Authorization")).toBe("Bearer token");
 } finally { globalThis.fetch = previous; }
});
test("persistent unauthorized response expires shared session", async () => {
 const previous = globalThis.fetch;
 globalThis.fetch = jest.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({ status: 401, detail: "expired" }) });
 const expired = jest.fn(); const refresh = jest.fn().mockResolvedValue("new-token");
 try { await expect(createApiClient("", async () => "token", refresh, expired).request("/report")).rejects.toBeInstanceOf(ApiProblem); expect(expired).toHaveBeenCalledTimes(1); expect(refresh).toHaveBeenCalledTimes(1); }
 finally { globalThis.fetch = previous; }
});


describe("bounded request deadlines", () => {
  const previous = globalThis.fetch;
  afterEach(() => { globalThis.fetch = previous; jest.useRealTimers(); });
  it("allows an upload past10 seconds and does not leak timeoutMs into fetch", async () => {
    jest.useFakeTimers();
    globalThis.fetch = jest.fn().mockImplementation((_url, init) => new Promise((resolve, reject) => {
      init.signal.addEventListener("abort", () => reject(new Error("signal is aborted without reason")));
      setTimeout(() => resolve(jsonResponse({ uploaded: true })), 15000);
    }));
    const pending = createApiClient("https://gateway.example").request("/evidence", { method: "POST", body: new FormData(), timeoutMs: 120000 });
    await jest.advanceTimersByTimeAsync(15000);
    await expect(pending).resolves.toEqual({ uploaded: true });
    expect((globalThis.fetch as jest.Mock).mock.calls[0][1]).not.toHaveProperty("timeoutMs");
  });
  it("translates the default deadline without logging out or retrying a write", async () => {
    jest.useFakeTimers();
    globalThis.fetch = jest.fn().mockImplementation((_url, init) => new Promise((_resolve, reject) => init.signal.addEventListener("abort", () => reject(new Error("signal is aborted without reason")))));
    const logout = jest.fn();
    const pending = createApiClient("https://gateway.example", undefined, undefined, logout).request("/report", { method: "POST" });
    const check = expect(pending).rejects.toThrow("Kết nối quá thời gian chờ");
    await jest.advanceTimersByTimeAsync(10000);await check;
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);expect(logout).not.toHaveBeenCalled();
  });
});
