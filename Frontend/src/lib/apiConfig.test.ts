import { resolveApiConfig } from "./apiConfig";

describe("real Gateway is the default on every platform", () => {
  it("does not select demo Auth when environment configuration is absent", () => {
    expect(resolveApiConfig(undefined, undefined)).toEqual({ baseUrl: "https://baotin.rimiprod.me", enabled: true });
    expect(resolveApiConfig("", "0").enabled).toBe(true);
  });
  it("requires an explicit demo switch and refuses a conflicting real origin", () => {
    expect(resolveApiConfig(undefined, "1")).toEqual({ baseUrl: "", enabled: false });
    expect(() => resolveApiConfig("https://baotin.rimiprod.me", "1")).toThrow();
    expect(() => resolveApiConfig(undefined, "true")).toThrow();
  });
  it("accepts exact HTTPS origins and loopback-only HTTP test gateways", () => {
    expect(resolveApiConfig("https://gateway.example/", "0").baseUrl).toBe("https://gateway.example");
    expect(resolveApiConfig("http://127.0.0.1:4184", undefined).enabled).toBe(true);
    for (const value of ["http://192.168.1.2:8090", "https://user:pass@gateway.example", "https://gateway.example/api", "https://gateway.example?token=fixture", "https://gateway.example#fragment"])
      expect(() => resolveApiConfig(value, undefined)).toThrow();
  });
});
