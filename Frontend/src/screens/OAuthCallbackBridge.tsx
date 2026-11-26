import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import { Button } from "../components/ui";
import { callbackTicket, validateAuthorizationUrl, type OAuthRequest } from "../api/oauth";
import { createAuthApi } from "../api/auth";

const bindingKey = "baotin-oauth-popup-binding";
function parentOrigins() { return [window.location.origin, "http://localhost:18081", "http://127.0.0.1:18081", "http://localhost:8081", "http://127.0.0.1:8081", ...(process.env.EXPO_PUBLIC_OAUTH_PARENT_ORIGINS || "").split(",").filter(Boolean)]; }
function allowedParent(origin: string) { return parentOrigins().includes(origin); }

export function OAuthCallbackBridge() {
  const [ticket, setTicket] = useState<string | null>(null);
  const [message, setMessage] = useState("Đang xử lý kết quả đăng nhập…");
  useEffect(() => {
    if (window.location.pathname === "/auth/oauth") {
      const parentOrigin = new URL(window.location.href).searchParams.get("parentOrigin");
      window.history.replaceState(null, "", window.location.pathname);
      if (!parentOrigin || !allowedParent(parentOrigin) || !window.opener) { setMessage("Hãy bắt đầu đăng nhập từ cửa sổ BaoTin hợp lệ."); return; }
      let started = false;
      const ready = () => {
        window.opener?.postMessage({ type: "baotin-oauth-ready" }, parentOrigin);
      };
      const listener = (event: MessageEvent) => {
        if (started || event.source !== window.opener || event.origin !== parentOrigin || event.data?.type !== "baotin-oauth-start") return;
        const request = event.data.request as OAuthRequest;
        if (!request || !["GOOGLE", "FACEBOOK"].includes(request.provider) || request.transport !== "WEB" || request.returnUri !== window.location.origin + "/auth/callback" || !/^[A-Za-z0-9_-]{43}$/.test(request.codeChallenge)) return;
        const token = event.data.accessToken;
        if (token !== undefined && (typeof token !== "string" || token.length > 8192)) return;
        started = true; clearInterval(timer);
        sessionStorage.setItem(bindingKey, JSON.stringify({ origin: event.origin, nonce: request.codeChallenge, expires: Date.now() + 300_000 }));
        void createAuthApi(window.location.origin).oauthRequest(request, token).then(result => window.location.replace(validateAuthorizationUrl(result.authorizationUrl, request.provider))).catch(() => {
          sessionStorage.removeItem(bindingKey);
          window.opener?.postMessage({ type: "baotin-oauth-result", nonce: request.codeChallenge, url: request.returnUri + "?error=oauth_denied" }, event.origin);
          setMessage("Chưa mở được đăng nhập. Vui lòng thử lại.");
        });
      };
      const timer = setInterval(ready, 500); ready(); window.addEventListener("message", listener);
      return () => { clearInterval(timer); window.removeEventListener("message", listener); };
    }
    const original = window.location.href;
    const base = window.location.origin + window.location.pathname;
    window.history.replaceState(null, "", window.location.pathname);
    let binding: { origin: string; nonce: string; expires: number } | null = null;
    try { const stored = sessionStorage.getItem(bindingKey); binding = stored ? JSON.parse(stored) : null; } catch { /* Invalid local metadata never authenticates a callback. */ }
    sessionStorage.removeItem(bindingKey);
    try {
      const code = callbackTicket(original, base);
      if (window.location.pathname === "/auth/callback" && window.opener && binding && allowedParent(binding.origin) && binding.expires > Date.now()) {
        window.opener.postMessage({ type: "baotin-oauth-result", nonce: binding.nonce, url: original }, binding.origin);
        setMessage("Đã chuyển kết quả về cửa sổ BaoTin. Bạn có thể đóng cửa sổ này."); window.close();
      } else if (window.location.pathname === "/auth/native-callback") {
        setTicket(code); setMessage("Quay lại ứng dụng và nhập mã bên dưới trong 60 giây.");
      } else setMessage("Hãy bắt đầu đăng nhập từ BaoTin. Cửa sổ này không có yêu cầu đang chờ.");
    } catch {
      if (window.opener && binding && allowedParent(binding.origin) && binding.expires > Date.now()) window.opener.postMessage({ type: "baotin-oauth-result", nonce: binding.nonce, url: base + "?error=oauth_denied" }, binding.origin);
      setMessage("Nhà cung cấp đã hủy hoặc chưa hoàn tất đăng nhập. Quay lại BaoTin để thử lại.");
    }
    const timer = setTimeout(() => setTicket(null), 60_000);
    const hidden = () => { if (document.visibilityState === "hidden") setTicket(null); };
    document.addEventListener("visibilitychange", hidden);
    return () => { clearTimeout(timer); document.removeEventListener("visibilitychange", hidden); };
  }, []);
  return <View className="mx-auto w-full max-w-[520px] gap-5 p-6"><Text accessibilityRole="header" className="font-display text-2xl text-ink dark:text-dark-ink">Đăng nhập BaoTin</Text><Text className="font-sans text-sm leading-6 text-ink dark:text-dark-ink">{message}</Text>{ticket ? <Text selectable className="font-mono text-sm text-ink dark:text-dark-ink">{ticket}</Text> : null}<Button label="Đóng kết quả đăng nhập" variant="outline" onPress={() => { setTicket(null); window.close(); }} /></View>;
}
