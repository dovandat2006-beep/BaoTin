import { Platform } from "react-native";
import { readRefreshToken, saveRefreshToken, clearRefreshToken } from "./session";
import type { AuthRole, AuthSession } from "../lib/auth";
import { ApiProblem, problemFromResponse } from "./client";
import type { OAuthRequest } from "./oauth";

export type ApiProfile = {
  id: string;
  username: string;
  email: string | null;
  displayName: string;
  role: AuthRole;
  emailVerified: boolean;
  mfaEnabled: boolean;
  phone?: string | null;
  phoneVerified?: boolean;
  mfaMethod?: "NONE" | "TOTP" | "EMAIL";
};

export type WebTokens = {
  accessToken: string;
  csrfToken: string;
  tokenType: "Bearer";
  expiresIn: number;
};

export type WebLoginResult =
  | { kind: "tokens"; tokens: WebTokens }
  | { kind: "mfa"; challenge: string; method: string };
export type OAuthExchangeResult = WebLoginResult | { kind: "onboarding" } | { kind: "linked" };

export type RegistrationInput = {
  username: string;
  contact: string;
  password: string;
};

/** UI-facing login outcome after the token/profile exchange has completed. */
export type ApiAuthOutcome =
  | { kind: "session"; session: AuthSession }
  | { kind: "mfa"; challenge: string; method: string };

const CSRF_COOKIE = "__Host-baotin-csrf";

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const prefix = `${name}=`;
  for (const part of document.cookie.split(";")) {
    const value = part.trim();
    if (value.startsWith(prefix)) return decodeURIComponent(value.slice(prefix.length));
  }
  return null;
}

function normalizeContact(contact: string): { email?: string; phone?: string } {
  const value = contact.trim();
  if (value.includes("@")) return { email: value };
  const compact = value.replace(/[\s()-]/g, "");
  if (compact.startsWith("+")) return { phone: compact };
  if (compact.startsWith("0")) return { phone: `+84${compact.slice(1)}` };
  return { phone: compact };
}

export function createAuthApi(baseUrl: string) {
  const normalizedBase = baseUrl.replace(/\/$/, "");
  let csrfToken: string | null = null;
  const native = Platform.OS !== "web";
  function currentWebCsrfToken(): string | null {
    // Same-origin tabs share rotated cookies, not this adapter's memory.
    const sameOrigin = typeof window !== "undefined" && window.location?.origin
      && new URL(normalizedBase || window.location.origin, window.location.origin).origin === window.location.origin;
    return sameOrigin ? readCookie(CSRF_COOKIE) ?? csrfToken : csrfToken;
  }
  async function acceptNative(tokens: WebTokens & { refreshToken: string }): Promise<WebTokens> {
    await saveRefreshToken(tokens.refreshToken);
    return { accessToken: tokens.accessToken, csrfToken: "", tokenType: tokens.tokenType, expiresIn: tokens.expiresIn };
  }

  async function request<T>(path: string, init: RequestInit = {}, token?: string, empty = false): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    if (init.body) headers.set("Content-Type", "application/json");
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const response = await fetch(`${normalizedBase}${path}`, {
      ...init,
      headers,
      credentials: "include"
    });
    if (!response.ok) throw new ApiProblem(await problemFromResponse(response));
    if (empty || response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }

  async function webTokens(path: string, body: Record<string, unknown>): Promise<WebLoginResult> {
    if (native) {
      const result = await request<(WebTokens & { refreshToken: string }) | { tokens?: WebTokens & { refreshToken: string }; mfaRequired: boolean; challenge: string; mfaMethod?: string }>(path.replace("/auth/web/", "/auth/"), { method: "POST", body: JSON.stringify(body) });
      if ("accessToken" in result) return { kind: "tokens", tokens: await acceptNative(result) };
      if (result.tokens) return { kind: "tokens", tokens: await acceptNative(result.tokens) };
      return { kind: "mfa", challenge: result.challenge, method: result.mfaMethod ?? "MFA" };
    }
    const result = await request<WebTokens | { mfaRequired: true; challenge: string; mfaMethod?: string }>(path, {
      method: "POST",
      body: JSON.stringify(body)
    });
    if ("accessToken" in result) {
      csrfToken = result.csrfToken;
      return { kind: "tokens", tokens: result };
    }
    return { kind: "mfa", challenge: result.challenge, method: result.mfaMethod ?? "MFA" };
  }

  return {
    async login(identifier: string, password: string): Promise<WebLoginResult> {
      return webTokens("/api/v1/auth/web/login", { identifier, password });
    },

    async completeMfa(challenge: string, code: string): Promise<WebLoginResult> {
      return webTokens("/api/v1/auth/web/mfa/challenges", { challenge, code });
    },

    async resendMfa(challenge: string): Promise<void> {
      return request<void>(native ? "/api/v1/auth/mfa/email/challenges" : "/api/v1/auth/web/mfa/email/challenges", { method: "POST", body: JSON.stringify({ challenge }) }, undefined, true);
    },

    async oauthRequest(input: OAuthRequest, linkAccessToken?: string): Promise<{ authorizationUrl: string }> {
      return request<{ authorizationUrl: string }>(linkAccessToken ? "/api/v1/auth/oauth2/link-requests" : "/api/v1/auth/oauth2/requests", { method: "POST", body: JSON.stringify({ provider: input.provider, transport: input.transport, returnUri: input.returnUri, codeChallenge: input.codeChallenge }) }, linkAccessToken);
    },

    async oauthExchange(code: string, codeVerifier: string, contactEmail?: string): Promise<OAuthExchangeResult> {
      type OAuthResponse = Partial<WebTokens & { refreshToken: string }> & { tokens?: WebTokens & { refreshToken: string }; mfaRequired?: boolean; challenge?: string; mfaMethod?: string; linked?: boolean; onboardingRequired?: boolean };
      const result = await request<OAuthResponse>(native ? "/api/v1/auth/oauth2/tokens" : "/api/v1/auth/web/oauth2/tokens", { method: "POST", body: JSON.stringify({ code, codeVerifier, ...(contactEmail ? { contactEmail } : {}) }) });
      if (result.onboardingRequired) return { kind: "onboarding" };
      if (result.linked) return { kind: "linked" };
      if (result.mfaRequired && result.challenge) return { kind: "mfa", challenge: result.challenge, method: result.mfaMethod ?? "TOTP" };
      if (native && result.tokens) return { kind: "tokens", tokens: await acceptNative(result.tokens) };
      if (!native && result.accessToken && result.csrfToken) { csrfToken = result.csrfToken; return { kind: "tokens", tokens: result as WebTokens }; }
      throw new Error("Nhà cung cấp chưa trả về kết quả đăng nhập hợp lệ.");
    },

    async register(input: RegistrationInput): Promise<ApiProfile> {
      const contact = normalizeContact(input.contact);
      return request<ApiProfile>("/api/v1/auth/register", {
        method: "POST",
        body: JSON.stringify({
          username: input.username.trim(),
          displayName: input.username.trim(),
          password: input.password,
          ...contact
        })
      });
    },

    async profile(accessToken: string): Promise<ApiProfile> {
      return request<ApiProfile>("/api/v1/auth/me", {}, accessToken);
    },

    /** Restores a web session from the HttpOnly refresh cookie; null means "not signed in". */
    async refresh(): Promise<WebTokens | null> {
      if (native) {
        const refreshToken = await readRefreshToken();
        if (!refreshToken) return null;
        try { return await acceptNative(await request<WebTokens & { refreshToken: string }>("/api/v1/auth/refresh", { method: "POST", body: JSON.stringify({ refreshToken }) })); }
        catch (error) { if (error instanceof ApiProblem && [401, 403].includes(error.problem.status)) { await clearRefreshToken(); return null; } throw error; }
      }
      const csrf = currentWebCsrfToken();
      if (!csrf) return null;
      const response = await fetch(`${normalizedBase}/api/v1/auth/web/refresh`, {
        method: "POST",
        credentials: "include",
        headers: { Accept: "application/json", "X-CSRF-TOKEN": csrf }
      });
      if (!response.ok) {
        csrfToken = null;
        return null;
      }
      const tokens = await response.json() as WebTokens;
      csrfToken = tokens.csrfToken;
      return tokens;
    },

    async logout(accessToken: string | null): Promise<void> {
      if (native) {
        try { if (accessToken) await request<void>("/api/v1/auth/logout", { method: "POST" }, accessToken); } finally { await clearRefreshToken(); }
        return;
      }
      const csrf = currentWebCsrfToken();
      try {
        await fetch(`${normalizedBase}/api/v1/auth/web/logout`, {
          method: "POST",
          credentials: "include",
          headers: {
            Accept: "application/json",
            ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
            ...(csrf ? { "X-CSRF-TOKEN": csrf } : {})
          }
        });
      } finally {
        csrfToken = null;
      }
    }
  };
}

export function toAuthSession(profile: ApiProfile): AuthSession {
  return {
    displayName: profile.displayName || profile.username,
    method: "password",
    role: profile.role
  };
}
