import type { ApiProfile } from "./auth";
import type { ApiClient } from "./client";
import type { PageResponse } from "./contracts";

export type SessionResource = { id: string; createdAt: string; expiresAt: string; mfaAuthenticated: boolean };
export type Enrollment = { secret: string; otpauthUri: string };
export type RecoveryCodes = { recoveryCodes: string[] };
export type EmailChallenge = { challenge: string; expiresIn: number; mfaMethod: string };
export type EmailAction = "CHANGE_PASSWORD" | "DISABLE_MFA" | "STEP_UP";
export type ManagedRole = "USER" | "MODERATOR";
export type ManagedStatus = "ACTIVE" | "LOCKED" | "DISABLED";
export type ManagedUser = { id: string; username: string; displayName: string; role: "USER" | "MODERATOR" | "ADMIN"; status: ManagedStatus; mfaEnabled: boolean };
const authRoot = "/api/v1/auth";
function idPath(id: string) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) throw new Error("Định danh không hợp lệ.");
  return id;
}
function paging(page: number) {
  if (!Number.isInteger(page) || page < 0 || page > 10000) throw new Error("Trang không hợp lệ.");
  return `?page=${page}&size=20`;
}
export function emailActionProof(challenge: string, code: string) {
  if (!/^e1\.[A-Za-z0-9_-]+$/.test(challenge) || !/^\d{6}$/.test(code)) throw new Error("Cần yêu cầu mã email mới và nhập đủ 6 chữ số.");
  return `${challenge}.${code}`;
}
export function createAccountApi(client: ApiClient) {
  function post<T = void>(path: string, body?: object) {
    return client.request<T>(authRoot + path, { method: "POST", credentialAction: true, ...(body ? { body: JSON.stringify(body) } : {}) });
  }
  return {
    profile: () => client.request<ApiProfile>(authRoot + "/me"),
    updateProfile: (displayName: string) => client.request<ApiProfile>(authRoot + "/me", { method: "PATCH", body: JSON.stringify({ displayName: displayName.trim() }) }),
    sessions: (page = 0) => client.request<PageResponse<SessionResource>>(authRoot + "/sessions" + paging(page)),
    revokeSession: (id: string) => client.request<void>(authRoot + "/sessions/" + idPath(id), { method: "DELETE" }),
    logoutAll: () => post("/logout-all"),
    resendVerification: () => client.request<void>(authRoot + "/email-verifications", { method: "POST", credentialAction: true }, "empty"),
    enrollTotp: (password: string) => post<Enrollment>("/mfa/enrollments", { password }),
    confirmTotp: (code: string) => post<RecoveryCodes>("/mfa/enrollments/confirmation", { code }),
    regenerateRecovery: (password: string, code: string) => post<RecoveryCodes>("/mfa/recovery-codes", { password, code }),
    disableMfa: (password: string, code: string) => post("/mfa/disable", { password, code }),
    enrollEmailMfa: (password: string) => post<EmailChallenge>("/mfa/email/enrollments", { password }),
    confirmEmailMfa: (challenge: string, code: string) => post("/mfa/email/enrollments/confirmation", { challenge, code }),
    emailAction: (purpose: EmailAction) => post<EmailChallenge>("/mfa/email/action-challenges", { purpose }),
    emailStepUp: (challenge: string, code: string) => post("/mfa/email/step-up", { challenge, code }),
    stepUp: (code: string) => post("/step-up", { code }),
    changePassword: (currentPassword: string, newPassword: string, code?: string) => post("/password", { currentPassword, newPassword, ...(code ? { code } : {}) }),
    initializePassword: (newPassword: string, code?: string) => post("/password-initialization", { newPassword, ...(code ? { code } : {}) }),
    adminUsers: (page = 0) => client.request<PageResponse<ManagedUser>>(authRoot + "/admin/users" + paging(page)),
    setRole: (id: string, role: ManagedRole) => {
      if (role !== "USER" && role !== "MODERATOR") throw new Error("Chỉ quản lý vai trò USER hoặc MODERATOR.");
      return client.request<void>(authRoot + "/admin/users/" + idPath(id) + "/role", { method: "PATCH", body: JSON.stringify({ role }) });
    },
    setStatus: (id: string, status: ManagedStatus) => {
      if (!["ACTIVE", "LOCKED", "DISABLED"].includes(status)) throw new Error("Trạng thái không hợp lệ.");
      return client.request<void>(authRoot + "/admin/users/" + idPath(id) + "/status", { method: "PATCH", body: JSON.stringify({ status }) });
    }
  };
}
export type AccountApi = ReturnType<typeof createAccountApi>;
export function createRecoveryApi(client: ApiClient) {
  const post = (path: string, body: object) => client.request<void>(authRoot + path, { method: "POST", body: JSON.stringify(body) }, "empty");
  return {
    forgot: (identifier: string) => post("/forgot-password", { identifier: identifier.trim() }),
    reset: (token: string, password: string) => post("/reset-password", { token, password }),
    verifyEmail: (token: string) => post("/verify-email", { token })
  };
}
export type RecoveryApi = ReturnType<typeof createRecoveryApi>;
