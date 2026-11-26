import { useCallback, useEffect, useRef } from "react";
import { Platform } from "react-native";
import Constants, { ExecutionEnvironment } from "expo-constants";
import * as WebBrowser from "expo-web-browser";
import { callbackTicket, createPkce, validateAuthorizationUrl, validateReturnUri, type OAuthProvider, type OAuthRequest } from "../api/oauth";
import type { ApiAuthOutcome, createAuthApi, WebTokens } from "../api/auth";
import type { AuthSession } from "./auth";

export type SocialOutcome = ApiAuthOutcome | { kind: "contact" } | { kind: "ticket" } | { kind: "linked" };
type Pending = { provider: OAuthProvider; verifier: string; code?: string; expires: number; link: boolean };

function webProviderTicket(input: Promise<{ request: OAuthRequest; token?: string }>, returnUri: string, ownPopup: (cancel: (() => void) | null) => void) {
  const popupOrigin = new URL(returnUri).origin;
  const popup = window.open(popupOrigin + "/auth/oauth?parentOrigin=" + encodeURIComponent(window.location.origin), "baotin-oauth-" + Date.now(), "popup,width=520,height=720");
  if (!popup) throw new Error("Hãy cho phép cửa sổ đăng nhập Google/Facebook rồi thử lại.");
  return new Promise<string>((resolve, reject) => {
    let done = false;
    function finish(error?: Error, code?: string) {
      if (done) return; done = true; clearTimeout(timeout); clearInterval(closed); window.removeEventListener("message", message); ownPopup(null); popup!.close();
      if (error) reject(error); else resolve(code!);
    }
    function message(event: MessageEvent) {
      if (event.source !== popup || event.origin !== popupOrigin) return;
      if (event.data?.type === "baotin-oauth-ready") {
        void input.then(value => { if (!done) popup!.postMessage({ type: "baotin-oauth-start", request: value.request, accessToken: value.token }, popupOrigin); }).catch(error => finish(error as Error));
        return;
      }
      if (event.data?.type !== "baotin-oauth-result") return;
      void input.then(value => {
        if (event.data.nonce !== value.request.codeChallenge) return;
        try { finish(undefined, callbackTicket(event.data.url, returnUri)); } catch (error) { finish(error as Error); }
      }).catch(error => finish(error as Error));
    }
    const timeout = setTimeout(() => finish(new Error("Yêu cầu đăng nhập đã hết hạn. Hãy thử lại.")), 300_000);
    const closed = setInterval(() => { if (popup.closed) finish(new Error("Bạn đã đóng cửa sổ đăng nhập.")); }, 400);
    window.addEventListener("message", message);
    ownPopup(() => finish(new Error("Đã hủy đăng nhập nhà cung cấp.")));
    void input.catch(error => finish(error instanceof Error ? error : new Error("Chưa mở được nhà cung cấp.")));
  });
}

export function useOAuthFlow(auth: ReturnType<typeof createAuthApi> | null, finishSession: (tokens: WebTokens) => Promise<AuthSession>, accessToken: () => string | null) {
  const pending = useRef<Pending | null>(null);
  const cancelPopup = useRef<(() => void) | null>(null);
  const version = useRef(0);
  const cancel = useCallback(() => { version.current++; pending.current = null; cancelPopup.current?.(); cancelPopup.current = null; }, []);
  useEffect(() => cancel, [cancel]);
  useEffect(() => {
    const timer = setInterval(() => { if (pending.current && pending.current.expires < Date.now()) cancel(); }, 10_000);
    return () => clearInterval(timer);
  }, [cancel]);
  const exchange = useCallback(async (contactEmail?: string, ticket?: string): Promise<SocialOutcome> => {
    const request = pending.current, generation = version.current;
    if (!auth || !request || request.expires < Date.now()) { cancel(); throw new Error("Đăng nhập đã hết hạn. Hãy bắt đầu lại."); }
    if (ticket) {
      if (!/^[A-Za-z0-9_-]{20,128}$/.test(ticket.trim())) throw new Error("Mã đăng nhập một lần không hợp lệ.");
      request.code = ticket.trim();
    }
    if (!request.code) throw new Error("Cần mã đăng nhập một lần từ cửa sổ trình duyệt.");
    const result = await auth.oauthExchange(request.code, request.verifier, contactEmail);
    if (version.current !== generation) throw new Error("Yêu cầu đăng nhập đã thay đổi.");
    if (request.link && result.kind !== "linked") throw new Error("Liên kết chưa hoàn tất; không thay đổi phiên tài khoản hiện tại.");
    if (!request.link && result.kind === "linked") throw new Error("Kết quả nhà cung cấp không khớp yêu cầu đăng nhập.");
    if (result.kind === "onboarding") return { kind: "contact" };
    pending.current = null;
    if (result.kind === "linked") return { kind: "linked" };
    if (result.kind === "mfa") return result;
    const session = await finishSession(result.tokens);
    return { kind: "session", session: { ...session, method: request.provider === "GOOGLE" ? "google" : "facebook" } };
  }, [auth, cancel, finishSession]);
  const start = useCallback(async (provider: OAuthProvider, link = false): Promise<SocialOutcome> => {
    if (!auth) throw new Error("Chưa cấu hình Auth API.");
    cancel(); const generation = version.current;
    const web = Platform.OS === "web";
    const expoGo = Constants.executionEnvironment === ExecutionEnvironment.StoreClient;
    const returnUri = validateReturnUri(web ? (process.env.EXPO_PUBLIC_OAUTH_WEB_RETURN_URI || "https://baotin.rimiprod.me/auth/callback") : expoGo ? (process.env.EXPO_PUBLIC_OAUTH_NATIVE_RETURN_URI || "https://baotin.rimiprod.me/auth/native-callback") : "baotin://auth/callback", web);
    // Start the popup synchronously before awaiting PKCE/network, so browser popup protection is respected.
    const input = (async () => {
      const pkce = await createPkce();
      if (version.current !== generation) throw new Error("Yêu cầu đăng nhập đã thay đổi.");
      pending.current = { provider, verifier: pkce.verifier, expires: Date.now() + 300_000, link };
      const token = link ? accessToken() : undefined;
      if (link && !token) throw new Error("Cần đăng nhập trước khi liên kết nhà cung cấp.");
      return { request: { provider, transport: web ? "WEB" as const : "NATIVE" as const, returnUri, codeChallenge: pkce.challenge }, token: token || undefined };
    })();
    try {
      if (web) {
        const ticket = await webProviderTicket(input, returnUri, close => { cancelPopup.current = close; });
        if (version.current !== generation) throw new Error("Yêu cầu đăng nhập đã thay đổi.");
        return await exchange(undefined, ticket);
      }
      const prepared = await input;
      const response = await auth.oauthRequest(prepared.request, prepared.token);
      const url = validateAuthorizationUrl(response.authorizationUrl, provider);
      if (expoGo) { await WebBrowser.openBrowserAsync(url); return { kind: "ticket" }; }
      const result = await WebBrowser.openAuthSessionAsync(url, returnUri);
      if (result.type !== "success") throw new Error("Bạn đã hủy đăng nhập nhà cung cấp.");
      return await exchange(undefined, callbackTicket(result.url, returnUri));
    } catch (error) { void input.catch(() => undefined); if (generation === version.current) cancel(); throw error; }
  }, [auth, accessToken, cancel, exchange]);
  return { start, exchange, cancel };
}
