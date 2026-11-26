/* Hallmark · focused security settings · DESIGN.md Material3/Roboto/FontAwesome
 * Original challenge, factor, secret-clear and invalidation logic preserved.
 */
import { useEffect, useRef, useState, type ComponentRef } from "react";
import { AccessibilityInfo, AppState, Platform, Text, View } from "react-native";
import { emailActionProof, type AccountApi, type EmailAction, type EmailChallenge, type Enrollment } from "../api/account";
import type { ApiProfile } from "../api/auth";
import { apiErrorMessage } from "../api/client";
import { Button, ErrorPanel, Input } from "../components/ui";

type Action = "password" | "initial" | "totp" | "recovery" | "disable" | "email" | "emailStepUp" | "totpStepUp";
const hints: Record<Action, string> = {
  password: "Đổi mật khẩu sẽ đăng xuất mọi thiết bị.",
  initial: "Đăng nhập lại bằng Google hoặc Facebook trước khi tạo mật khẩu.",
  totp: "Nhập mật khẩu, rồi làm theo hướng dẫn trong ứng dụng xác thực.",
  recovery: "Nhập mật khẩu và mã từ ứng dụng xác thực. Các mã dự phòng cũ sẽ hết hiệu lực.",
  disable: "Tắt xác thực hai bước sẽ đăng xuất mọi thiết bị.",
  email: "Xác minh email trước khi bật xác thực qua email.",
  emailStepUp: "Nhập mã được gửi tới email của bạn.",
  totpStepUp: "Nhập mã mới từ ứng dụng xác thực."
};
const fields = "font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant";
const actions: Array<{ heading: string; items: Array<{ value: Action; label: string }> }> = [
  { heading: "Mật khẩu", items: [{ value: "password", label: "Đổi mật khẩu" }, { value: "initial", label: "Tạo mật khẩu" }] },
  { heading: "Xác thực hai bước", items: [{ value: "totp", label: "Thiết lập ứng dụng xác thực" }, { value: "email", label: "Xác thực qua email" }, { value: "recovery", label: "Tạo mã dự phòng mới" }, { value: "disable", label: "Tắt xác thực hai bước" }] },
  { heading: "Xác nhận thao tác", items: [{ value: "emailStepUp", label: "Xác nhận qua email" }, { value: "totpStepUp", label: "Xác nhận bằng ứng dụng" }] }
];
const submitLabels: Record<Action, string> = { password: "Lưu mật khẩu mới", initial: "Tạo mật khẩu", totp: "Tiếp tục thiết lập", recovery: "Tạo mã dự phòng mới", disable: "Tắt xác thực hai bước", email: "Gửi mã xác nhận", emailStepUp: "Xác nhận qua email", totpStepUp: "Xác nhận bằng ứng dụng" };
export function AccountSecurityPanel({ api, profile, onInvalidated }: { api: AccountApi; profile: ApiProfile; onInvalidated: (codes?: string[]) => void }) {
  const [editing, setEditing] = useState(false);
  const [action, setAction] = useState<Action>("password");
  const [password, setPassword] = useState("");
  const [nextPassword, setNextPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [code, setCode] = useState("");
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);
  const [email, setEmail] = useState<EmailChallenge | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const generation = useRef(0);
  const formRef = useRef<ComponentRef<typeof View>>(null);
  const errorRef = useRef<ComponentRef<typeof View>>(null);
  useEffect(() => {
    if (!error) return;
    if (Platform.OS === "web") {
      const region = errorRef.current as unknown as { focus?: () => void; scrollIntoView?: (options: { block: "center"; behavior: "auto" }) => void };
      region?.focus?.(); region?.scrollIntoView?.({ block: "center", behavior: "auto" });
    } else AccessibilityInfo.announceForAccessibility(error);
  }, [error]);
  useEffect(() => {
    if (!editing) return;
    if (Platform.OS === "web") {
      const form = formRef.current as unknown as { focus?: () => void; scrollIntoView?: (options: { block: "start"; behavior: "auto" }) => void };
      form?.focus?.(); form?.scrollIntoView?.({ block: "start", behavior: "auto" });
    } else AccessibilityInfo.announceForAccessibility(actions.flatMap(group => group.items).find(item => item.value === action)?.label ?? "Bảo mật tài khoản");
  }, [editing, action]);
  function clear() { generation.current++; setPassword(""); setNextPassword(""); setConfirmation(""); setCode(""); setEnrollment(null); setEmail(null); }
  useEffect(() => {
    const clearHidden = () => { if (typeof document === "undefined" || document.visibilityState === "hidden") clear(); };
    const sub = AppState.addEventListener("change", state => { if (state !== "active") clear(); });
    if (typeof document !== "undefined") document.addEventListener("visibilitychange", clearHidden);
    return () => { generation.current++; sub.remove(); if (typeof document !== "undefined") document.removeEventListener("visibilitychange", clearHidden); };
  }, []);
  useEffect(() => {
    if (!enrollment && !email) return;
    const timer = setTimeout(clear, enrollment ? 300_000 : Math.min(email!.expiresIn, 600) * 1000);
    return () => clearTimeout(timer);
  }, [enrollment, email]);
  const changeAction = (next: Action) => { clear(); setAction(next); setError(null); setNotice(null); };
  async function run(call: (current: () => boolean) => Promise<void>) {
    if (busy) return; const version = generation.current;
    setBusy(true); setError(null); setNotice(null);
    try { await call(() => version === generation.current); }
    catch (failure) { if (version === generation.current) setError(apiErrorMessage(failure, "Chưa hoàn tất thao tác bảo mật.")); }
    finally { setBusy(false); }
  }
  const emailMfa = profile.mfaMethod === "EMAIL";
  const purpose: EmailAction = action === "disable" ? "DISABLE_MFA" : action === "emailStepUp" ? "STEP_UP" : "CHANGE_PASSWORD";
  const factor = () => emailMfa && email ? emailActionProof(email.challenge, code.trim()) : code.trim();
  async function submit(current: () => boolean) {
    if (action === "totp" && !enrollment) { const result = await api.enrollTotp(password); if (current()) { setEnrollment(result); setPassword(""); } return; }
    if (action === "totp") { const result = await api.confirmTotp(code.trim()); if (current()) { clear(); onInvalidated(result.recoveryCodes); } return; }
    if (action === "recovery") { const result = await api.regenerateRecovery(password, code.trim()); if (current()) { clear(); onInvalidated(result.recoveryCodes); } return; }
    if (action === "email" && !email) { const result = await api.enrollEmailMfa(password); if (current()) { setEmail(result); setPassword(""); } return; }
    if (action === "email") { await api.confirmEmailMfa(email!.challenge, code.trim()); }
    else if (action === "emailStepUp") { await api.emailStepUp(email!.challenge, code.trim()); if (current()) { clear(); setNotice("Đã xác thực qua email."); } return; }
    else if (action === "totpStepUp") { await api.stepUp(code.trim()); if (current()) { clear(); setNotice("Đã xác thực bằng ứng dụng."); } return; }
    else if (action === "disable") await api.disableMfa(password, factor());
    else if (action === "initial") await api.initializePassword(nextPassword, code.trim() || undefined);
    else await api.changePassword(password, nextPassword, factor() || undefined);
    if (current()) { clear(); onInvalidated(); }
  }
  const needsNew = action === "password" || action === "initial";
  const needsPassword = ["password", "recovery", "disable"].includes(action) || (action === "totp" && !enrollment) || (action === "email" && !email);
  const needsCode = action === "totpStepUp" || action === "recovery" || action === "disable" || Boolean(enrollment) || Boolean(email) || (needsNew && profile.mfaEnabled);
  const valid = (!needsPassword || Boolean(password)) && (!needsNew || (nextPassword.length >= 12 && nextPassword.length <= 128 && nextPassword === confirmation)) && (!needsCode || Boolean(code.trim())) && (action !== "emailStepUp" || Boolean(email));
  const availableGroups = actions.map(group => ({ ...group, items: group.items.filter(item =>
    item.value === "emailStepUp" ? emailMfa : item.value === "totpStepUp" ? profile.mfaEnabled && !emailMfa : true
  ) })).filter(group => group.items.length > 0);
  const stepHint = action === "totp" && enrollment ? "Thêm khóa bên dưới vào ứng dụng xác thực rồi nhập mã mới để hoàn tất." : (action === "email" || action === "emailStepUp") && email ? "Nhập mã được gửi tới email cho yêu cầu này rồi xác nhận." : hints[action];
  return <View className="gap-4">
    <Text className={fields}>Xác thực hai bước: {profile.mfaEnabled ? (profile.mfaMethod === "EMAIL" ? "Qua email" : "Bằng ứng dụng") : "Chưa bật"}.</Text>
    {!editing ? <View className="gap-6">{availableGroups.map(group => <View key={group.heading} className="gap-3"><Text accessibilityRole="header" aria-level={2} className="font-medium text-base text-md-on-surface dark:text-dark-md-on-surface">{group.heading}</Text><View className="gap-2">{group.items.map(item => <Button appearance="material" key={item.value} label={item.label} variant="outline" disabled={busy} onPress={() => { changeAction(item.value); setEditing(true); }} />)}</View></View>)}</View> : <View ref={formRef} tabIndex={-1} testID="security-action-form" className="gap-4 focus-visible:ring-2 focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary">
    <View className="gap-2"><Text accessibilityRole="header" aria-level={2} className="font-display text-[22px] leading-7 text-md-on-surface dark:text-dark-md-on-surface">{actions.flatMap(group => group.items).find(item => item.value === action)?.label}</Text><Button appearance="material" className="self-start" label="Quay lại các tùy chọn" variant="ghost" disabled={busy} onPress={() => { clear(); setError(null); setNotice(null); setEditing(false); }} /></View>
    <Text className={fields}>{stepHint}</Text>
    <Text className={fields}>Thông tin tạm sẽ được xóa khi bạn rời ứng dụng. Khi quay lại, hãy bắt đầu lại bước đang làm.</Text>
    {!profile.emailVerified ? <Text className={fields}>Xác minh email trong Hồ sơ trước khi thay đổi bảo mật.</Text> : null}
    {needsPassword ? <Input appearance="material" label="Mật khẩu hiện tại" value={password} onChangeText={setPassword} secureTextEntry autoComplete="current-password" maxLength={128} editable={!busy} /> : null}
    {needsNew ? <><Input appearance="material" label="Mật khẩu mới" description="Dùng từ 12 đến 128 ký tự." value={nextPassword} onChangeText={setNextPassword} secureTextEntry autoComplete="new-password" maxLength={128} editable={!busy} /><Input appearance="material" label="Nhập lại mật khẩu mới" error={confirmation.length > 0 && nextPassword !== confirmation ? "Mật khẩu nhập lại chưa khớp." : undefined} value={confirmation} onChangeText={setConfirmation} secureTextEntry autoComplete="new-password" maxLength={128} editable={!busy} /></> : null}
    {enrollment ? <View className="gap-2"><Text className={fields}>Khóa thiết lập ứng dụng xác thực (chỉ hiển thị tạm thời):</Text><Text selectable className="font-mono text-sm text-ink dark:text-dark-ink">{enrollment.secret}</Text><Text className={fields}>Thêm khóa này vào ứng dụng xác thực, rồi nhập mã 6 chữ số.</Text></View> : null}
    {emailMfa && (action === "password" || action === "disable" || action === "emailStepUp") ? <Button appearance="material" label="Yêu cầu mã email cho thao tác này" variant="outline" disabled={busy} onPress={() => void run(async current => { const result = await api.emailAction(purpose); if (current()) { setEmail(result); setCode(""); setNotice("Đã yêu cầu mã. Mã chỉ có hiệu lực cho thao tác và phiên này."); } })} /> : null}
    {needsCode ? <Input appearance="material" label="Mã xác thực bảo mật" description={emailMfa || email || action === "email" || action === "emailStepUp" ? "Nhập mã được gửi đến email cho yêu cầu này." : "Nhập mã mới từ ứng dụng xác thực."} autoComplete="one-time-code" value={code} onChangeText={setCode} secureTextEntry autoCapitalize="none" autoCorrect={false} maxLength={128} editable={!busy} /> : null}
    {error ? <View ref={errorRef} tabIndex={-1} testID="security-action-error"><ErrorPanel title="Chưa hoàn tất thao tác bảo mật" description={error} /></View> : null}{notice ? <Text accessibilityRole="alert" className={fields}>{notice}</Text> : null}
    <Button appearance="material" label={enrollment || (action === "email" && email) ? "Xác nhận bật xác thực hai bước" : submitLabels[action]} disabled={busy || !profile.emailVerified || !valid} loading={busy} onPress={() => void run(submit)} />
    </View>}
  </View>;
}
