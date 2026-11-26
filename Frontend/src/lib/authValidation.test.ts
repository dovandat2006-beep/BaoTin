import { displayNameFromIdentifier, validateLogin, validateRegistration } from "./authValidation";

describe("auth UI validation", () => {
  it("requires both login fields", () => {
    expect(validateLogin("", "")).toEqual({ identifier: "Nhập email, số điện thoại hoặc username.", password: "Nhập mật khẩu." });
    expect(validateLogin("an.nguyen", "password123")).toEqual({});
  });

  it("validates registration identity, password, confirmation, and terms", () => {
    expect(Object.keys(validateRegistration({ username: "a", contact: "bad", password: "short", confirmPassword: "other", terms: false }))).toEqual([
      "username", "contact", "password", "confirmPassword", "terms"
    ]);
    expect(validateRegistration({ username: "an.nguyen", contact: "an@example.com", password: "password123", confirmPassword: "password123", terms: true })).toEqual({});
  });

  it("derives a compact display name", () => {
    expect(displayNameFromIdentifier("an.nguyen@example.com")).toBe("an.nguyen");
    expect(displayNameFromIdentifier("0912345678")).toBe("0912345678");
  });
});
