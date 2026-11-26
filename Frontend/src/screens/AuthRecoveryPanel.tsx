import { useEffect, useState } from "react";
import { AppState, Text, View } from "react-native";
import type { RecoveryApi } from "../api/account";
import { apiErrorMessage } from "../api/client";
import { Button, ErrorPanel, Input, Tabs } from "../components/ui";

type Mode = "forgot" | "reset" | "verify";
export function AuthRecoveryPanel({ api, onClose }: { api: RecoveryApi; onClose: () => void }) {
  const [mode, setMode] = useState<Mode>("forgot");
  const [identifier, setIdentifier] = useState("");
  const [token, setToken] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  function clearSecrets() { setToken(""); setPassword(""); setConfirmation(""); }
  useEffect(() => {
    const sub = AppState.addEventListener("change", state => { if (state !== "active") clearSecrets(); });
    const hidden = () => { if (document.visibilityState === "hidden") clearSecrets(); };
    if (typeof document !== "undefined") document.addEventListener("visibilitychange", hidden);
    return () => { sub.remove(); if (typeof document !== "undefined") document.removeEventListener("visibilitychange", hidden); };
  }, []);
  async function submit() {
    if (busy) return; setBusy(true); setError(null); setNotice(null);
    try {
      if (mode === "forgot") { await api.forgot(identifier.trim()); setNotice("Nếu thông tin khớp tài khoản đủ điều kiện, hướng dẫn sẽ được gửi đến email. Kiểm tra hộp thư và thư rác."); }
      else if (mode === "reset") { await api.reset(token.trim(), password); clearSecrets(); setNotice("Đã đặt lại mật khẩu và thu hồi các phiên cũ. Hãy đăng nhập lại."); }
      else { await api.verifyEmail(token.trim()); clearSecrets(); setNotice("Đã xác minh email. Bạn có thể đăng nhập."); }
    } catch (failure) { setError(apiErrorMessage(failure, "Yêu cầu chưa hoàn tất. Kiểm tra thông tin hoặc yêu cầu token mới.")); }
    finally { setBusy(false); }
  }
  const valid = mode === "forgot" ? Boolean(identifier.trim()) : Boolean(token.trim()) && (mode !== "reset" || (password.length >= 12 && password === confirmation));
  return <View className="gap-4">
    <Text accessibilityRole="header" className="font-display text-2xl text-ink dark:text-dark-ink">Khôi phục và xác minh tài khoản</Text>
    <Tabs items={[{ value: "forgot", label: "Quên mật khẩu" }, { value: "reset", label: "Đặt lại" }, { value: "verify", label: "Xác minh email" }]} value={mode} onChange={next => { clearSecrets(); setMode(next); setError(null); setNotice(null); }} />
    {mode === "forgot" ? <Input label="Email hoặc username khôi phục" value={identifier} onChangeText={setIdentifier} autoCapitalize="none" autoCorrect={false} maxLength={320} editable={!busy} /> : <Input label={mode === "verify" ? "Token xác minh email" : "Token đặt lại mật khẩu"} value={token} onChangeText={setToken} secureTextEntry autoCapitalize="none" autoCorrect={false} maxLength={128} editable={!busy} />}
    {mode === "reset" ? <><Input label="Mật khẩu khôi phục mới" value={password} onChangeText={setPassword} secureTextEntry autoComplete="new-password" maxLength={128} editable={!busy} /><Input label="Nhập lại mật khẩu khôi phục" value={confirmation} onChangeText={setConfirmation} secureTextEntry autoComplete="new-password" maxLength={128} editable={!busy} /></> : null}
    {error ? <ErrorPanel description={error} /> : null}{notice ? <Text accessibilityRole="alert" className="font-sans text-sm leading-6 text-ink dark:text-dark-ink">{notice}</Text> : null}
    <Button label="Gửi yêu cầu khôi phục hoặc xác minh" disabled={busy || !valid} loading={busy} onPress={() => void submit()} />
    <Button label="Quay lại đăng nhập" variant="ghost" disabled={busy} onPress={onClose} />
  </View>;
}
