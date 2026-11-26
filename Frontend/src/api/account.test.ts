import { createAccountApi, createRecoveryApi, emailActionProof } from "./account";
import type { ApiClient } from "./client";

function fixture() {
  const request = jest.fn().mockResolvedValue({ items: [] });
  return { request, api: createAccountApi({ request } as ApiClient) };
}

test("account requests use only the public Auth API and explicit profile fields", async () => {
  const { api, request } = fixture();
  await api.profile(); await api.updateProfile(" New name ");
  expect(request.mock.calls[0]?.[0]).toBe("/api/v1/auth/me");
  expect(request.mock.calls[1]).toEqual(["/api/v1/auth/me", { method: "PATCH", body: JSON.stringify({ displayName: "New name" }) }]);
});
test("session pagination, revocation and logout-all use server operations", async () => {
  const { api, request } = fixture();
  await api.sessions(2); await api.revokeSession("00000000-0000-4000-8000-000000000001"); await api.logoutAll();
  expect(request.mock.calls.map(c => c[0])).toEqual(["/api/v1/auth/sessions?page=2&size=20", "/api/v1/auth/sessions/00000000-0000-4000-8000-000000000001", "/api/v1/auth/logout-all"]);
  expect(request.mock.calls[1]?.[1].method).toBe("DELETE");
});
test("all existing MFA and password actions preserve the server contract", async () => {
  const { api, request } = fixture();
  await api.enrollTotp("fixture-password"); await api.confirmTotp("123456");
  await api.regenerateRecovery("fixture-password", "234567"); await api.disableMfa("fixture-password", "345678");
  await api.enrollEmailMfa("fixture-password"); await api.confirmEmailMfa("e1.fixture", "456789");
  await api.emailAction("CHANGE_PASSWORD"); await api.emailStepUp("e1.fixture", "567890");
  await api.changePassword("old-fixture-password", "new-fixture-password", "678901");
  await api.initializePassword("new-fixture-password", "789012"); await api.stepUp("890123");
  await api.resendVerification();
  expect(request.mock.calls.map(c => c[0])).toEqual([
    "/api/v1/auth/mfa/enrollments", "/api/v1/auth/mfa/enrollments/confirmation", "/api/v1/auth/mfa/recovery-codes", "/api/v1/auth/mfa/disable",
    "/api/v1/auth/mfa/email/enrollments", "/api/v1/auth/mfa/email/enrollments/confirmation", "/api/v1/auth/mfa/email/action-challenges", "/api/v1/auth/mfa/email/step-up",
    "/api/v1/auth/password", "/api/v1/auth/password-initialization", "/api/v1/auth/step-up", "/api/v1/auth/email-verifications"
  ]);
  expect(JSON.parse(request.mock.calls[8]?.[1].body)).toEqual({ currentPassword: "old-fixture-password", newPassword: "new-fixture-password", code: "678901" });
});
test("admin operations cannot serialize ADMIN grants, unknown status or invalid IDs", async () => {
  const { api, request } = fixture();
  const id = "00000000-0000-4000-8000-000000000001";
  await api.adminUsers(1); await api.setRole(id, "MODERATOR"); await api.setStatus(id, "LOCKED");
  expect(request.mock.calls[0]?.[0]).toBe("/api/v1/auth/admin/users?page=1&size=20");
  expect(JSON.parse(request.mock.calls[1]?.[1].body)).toEqual({ role: "MODERATOR" });
  expect(() => api.setRole(id, "ADMIN" as "USER")).toThrow();
  expect(() => api.setStatus(id, "UNKNOWN" as "ACTIVE")).toThrow();
  expect(() => api.revokeSession("../admin")).toThrow();
  expect(request).toHaveBeenCalledTimes(3);
});
test("recovery exposes no outbox, user enumeration response or privileged parameters", async () => {
  const request = jest.fn().mockResolvedValue(undefined);
  const api = createRecoveryApi({ request } as ApiClient);
  await api.forgot("fixture@example.test"); await api.reset("single-use-fixture", "fixture-password-new"); await api.verifyEmail("single-use-fixture");
  expect(request.mock.calls.map(c => c[0])).toEqual(["/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/verify-email"]);
  expect(JSON.parse(request.mock.calls[1]?.[1].body)).toEqual({ token: "single-use-fixture", password: "fixture-password-new" });
});
test("email action proofs are purpose-bound challenge plus six digits", () => {
  expect(emailActionProof("e1.fixture", "123456")).toBe("e1.fixture.123456");
  expect(() => emailActionProof("", "123456")).toThrow();
  expect(() => emailActionProof("e1.fixture", "bad-code")).toThrow();
});
