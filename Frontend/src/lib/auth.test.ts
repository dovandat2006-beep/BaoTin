import { canAccessAdmin, resolveVisibleSection } from "./auth";

describe("canAccessAdmin", () => {
  it("allows ADMIN only, rejects MODERATOR and USER", () => {
    expect(canAccessAdmin({ role: "ADMIN" })).toBe(true);
    expect(canAccessAdmin({ role: "MODERATOR" })).toBe(false);
    expect(canAccessAdmin({ role: "USER" })).toBe(false);
    expect(canAccessAdmin(null)).toBe(false);
    expect(canAccessAdmin(undefined)).toBe(false);
  });

  it("không suy quyền từ tên hiển thị hoặc email giống admin", () => {
    const namedAdmin = { role: "USER" as const, displayName: "admin" };
    const emailAdmin = { role: "USER" as const, displayName: "admin@example.com" };
    expect(canAccessAdmin(namedAdmin)).toBe(false);
    expect(canAccessAdmin(emailAdmin)).toBe(false);
  });
});

describe("resolveVisibleSection", () => {
  it("chỉ render Quản trị khi phiên có vai trò ADMIN", () => {
    expect(resolveVisibleSection("admin", { role: "ADMIN" })).toBe("admin");
    expect(resolveVisibleSection("admin", { role: "MODERATOR" })).toBe("scan");
    expect(resolveVisibleSection("admin", { role: "USER" })).toBe("scan");
    expect(resolveVisibleSection("admin", undefined)).toBe("auth");
    expect(resolveVisibleSection("admin", null)).toBe("auth");
    expect(resolveVisibleSection("history", { role: "USER" })).toBe("history");
  });
});
