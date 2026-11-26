import { faFacebookF } from "@fortawesome/free-brands-svg-icons/faFacebookF";
import { faGoogle } from "@fortawesome/free-brands-svg-icons/faGoogle";
import { faArrowRight } from "@fortawesome/free-solid-svg-icons/faArrowRight";
import { faCheck } from "@fortawesome/free-solid-svg-icons/faCheck";
import { faCircleExclamation } from "@fortawesome/free-solid-svg-icons/faCircleExclamation";
import { faEye } from "@fortawesome/free-solid-svg-icons/faEye";
import { faEyeSlash } from "@fortawesome/free-solid-svg-icons/faEyeSlash";
import { faLock } from "@fortawesome/free-solid-svg-icons/faLock";
import { faRightFromBracket } from "@fortawesome/free-solid-svg-icons/faRightFromBracket";
import { faSquare } from "@fortawesome/free-solid-svg-icons/faSquare";
import { faSquareCheck } from "@fortawesome/free-solid-svg-icons/faSquareCheck";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { useState } from "react";
import { Pressable, Text, View, useWindowDimensions } from "react-native";
import { apiErrorMessage } from "../api/client";
import type { ApiAuthOutcome, RegistrationInput } from "../api/auth";
import { Alert, Button, Input, Tabs } from "../components/ui";
import { type AuthRole, type AuthSession } from "../lib/auth";
import { displayNameFromIdentifier, validateLogin, validateRegistration, type AuthErrors } from "../lib/authValidation";
import { useAppTheme } from "../theme/AppTheme";
import type { RecoveryApi } from "../api/account";
import { AuthRecoveryPanel } from "./AuthRecoveryPanel";
import type { OAuthProvider } from "../api/oauth";
import type { SocialOutcome } from "../lib/useOAuthFlow";

type Props = {
  session: AuthSession | null;
  onAuthenticated: (session: AuthSession) => void;
  onContinue: () => void;
  onLogout: () => void;
  apiEnabled?: boolean;
  onApiLogin?: (identifier: string, password: string) => Promise<ApiAuthOutcome>;
  onApiRegister?: (input: RegistrationInput) => Promise<ApiAuthOutcome>;
  onApiMfa?: (challenge: string, code: string) => Promise<AuthSession>;
  onApiMfaResend?: (challenge: string) => Promise<void>;
  recoveryApi?: RecoveryApi | null;
  onApiSocial?: (provider: OAuthProvider) => Promise<SocialOutcome>;
  onApiSocialContact?: (email: string) => Promise<SocialOutcome>;
  onApiSocialTicket?: (ticket: string) => Promise<SocialOutcome>;
  onCancelSocial?: () => void;
};
type Mode = "login" | "register";
type MfaChallenge = { challenge: string; method: string };

const demoRoles: { value: AuthRole; label: string }[] = [
  { value: "USER", label: "Người dùng" },
  { value: "ADMIN", label: "Quản trị viên" }
];

const roleLabels: Record<AuthRole, string> = {
  USER: "Người dùng",
  MODERATOR: "Kiểm duyệt viên",
  ADMIN: "Quản trị viên"
};

export function AuthScreen({ session, onAuthenticated, onContinue, onLogout, apiEnabled = false, onApiLogin, onApiRegister, onApiMfa, onApiMfaResend, recoveryApi, onApiSocial, onApiSocialContact, onApiSocialTicket, onCancelSocial }: Props) {
  const { colors } = useAppTheme();
  const { width } = useWindowDimensions();
  const wide = width >= 900;
  const [mode, setMode] = useState<Mode>("login");
  const [identifier, setIdentifier] = useState("");
  const [username, setUsername] = useState("");
  const [contact, setContact] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [terms, setTerms] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<AuthErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [mfa, setMfa] = useState<MfaChallenge | null>(null);
  const [mfaCode, setMfaCode] = useState("");
  const [demoRole, setDemoRole] = useState<AuthRole>("USER");
  const [recovering, setRecovering] = useState(false);
  const [recoveryCode, setRecoveryCode] = useState(false);
  const [mfaNotice, setMfaNotice] = useState<string | null>(null);
  const [socialStep, setSocialStep] = useState<"contact" | "ticket" | null>(null);
  const [socialValue, setSocialValue] = useState("");

  const switchMode = (next: Mode) => {
    setMode(next);
    setErrors({});
    setFormError(null);
    setMfa(null);
    setMfaCode("");
    setPassword("");
    setConfirmPassword("");
  };
  const complete = (next: Omit<AuthSession, "role">, role: AuthRole) => {
    setSubmitting(true);
    setTimeout(() => { setSubmitting(false); onAuthenticated({ ...next, role }); }, 450);
  };
  const applyOutcome = (outcome: ApiAuthOutcome) => {
    if (outcome.kind === "session") {
      onAuthenticated(outcome.session);
      return;
    }
    setMfa({ challenge: outcome.challenge, method: outcome.method });
    setMfaCode("");
  };
  const runApi = async (call: () => Promise<ApiAuthOutcome>) => {
    setSubmitting(true);
    setFormError(null);
    try {
      applyOutcome(await call());
    } catch (error) {
      setFormError(apiErrorMessage(error, "Không thể kết nối dịch vụ xác thực. Vui lòng thử lại."));
    } finally {
      setSubmitting(false);
    }
  };
  const submit = () => {
    const nextErrors = mode === "login"
      ? validateLogin(identifier, password)
      : validateRegistration({ username, contact, password, confirmPassword, terms }, apiEnabled ? 12 : 8);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;
    setFormError(null);
    if (!apiEnabled) {
      complete({ displayName: mode === "login" ? displayNameFromIdentifier(identifier) : username.trim(), method: "password" }, mode === "login" ? demoRole : "USER");
      return;
    }
    if (mode === "login") {
      if (!onApiLogin) { setFormError("Đăng nhập chưa sẵn sàng."); return; }
      void runApi(() => onApiLogin(identifier.trim(), password));
      return;
    }
    if (!onApiRegister) { setFormError("Đăng ký chưa sẵn sàng."); return; }
    void runApi(() => onApiRegister({ username: username.trim(), contact: contact.trim(), password }));
  };
  const confirmMfa = () => {
    if (!mfa || !onApiMfa) return;
    if (!mfaCode.trim()) { setFormError("Nhập mã xác thực."); return; }
    setSubmitting(true);
    setFormError(null);
    onApiMfa(mfa.challenge, mfaCode.trim())
      .then(onAuthenticated)
      .catch((error) => setFormError(apiErrorMessage(error, "Mã xác thực không hợp lệ hoặc đã hết hạn.")))
      .finally(() => setSubmitting(false));
  };
  const continueWith = (method: "google" | "facebook") => complete({ displayName: method === "google" ? "Tài khoản Google" : "Tài khoản Facebook", method }, demoRole);
  async function runSocial(call: () => Promise<SocialOutcome>) {
    setSubmitting(true); setFormError(null);
    try {
      const result = await call();
      if (result.kind === "contact" || result.kind === "ticket") { setSocialStep(result.kind); setSocialValue(""); }
      else if (result.kind === "linked") { setFormError("Nhà cung cấp đã liên kết. Hãy đăng nhập lại từ tài khoản của bạn."); }
      else { setSocialStep(null); applyOutcome(result); }
    } catch (failure) { setFormError(apiErrorMessage(failure, "Chưa hoàn tất đăng nhập nhà cung cấp.")); }
    finally { setSubmitting(false); }
  }
  const social = (provider: OAuthProvider) => {
    if (!apiEnabled) { continueWith(provider === "GOOGLE" ? "google" : "facebook"); return; }
    if (onApiSocial) void runSocial(() => onApiSocial(provider));
  };

  if (!session && apiEnabled && recovering && recoveryApi) return <AuthRecoveryPanel api={recoveryApi} onClose={() => setRecovering(false)} />;
  if (!session && apiEnabled && socialStep) return <View className="mx-auto w-full max-w-[500px] gap-4"><Text className="font-display text-2xl text-ink dark:text-dark-ink">{socialStep === "contact" ? "Bổ sung email liên hệ" : "Hoàn tất đăng nhập Expo Go"}</Text><Text className="font-sans text-sm leading-6 text-muted dark:text-dark-muted">{socialStep === "contact" ? "Thêm email liên hệ để hoàn tất đăng nhập." : "Nhập mã đăng nhập hiển thị trong trình duyệt."}</Text><Input label={socialStep === "contact" ? "Email liên hệ OAuth" : "Mã đăng nhập một lần"} value={socialValue} onChangeText={setSocialValue} secureTextEntry={socialStep === "ticket"} autoCapitalize="none" autoCorrect={false} maxLength={socialStep === "contact" ? 320 : 128} />{formError ? <Alert icon={faCircleExclamation} tone="destructive" description={formError} /> : null}<Button label="Hoàn tất đăng nhập nhà cung cấp" loading={submitting} disabled={submitting || !socialValue.trim()} onPress={() => { if (socialStep === "contact" && onApiSocialContact) void runSocial(() => onApiSocialContact(socialValue.trim())); else if (onApiSocialTicket) void runSocial(() => onApiSocialTicket(socialValue.trim())); }} /><Button label="Hủy đăng nhập nhà cung cấp" variant="ghost" disabled={submitting} onPress={() => { onCancelSocial?.(); setSocialStep(null); setSocialValue(""); setFormError(null); }} /></View>;

  if (session) {
    return (
      <View className="mx-auto w-full max-w-[520px] border-y border-line py-8 dark:border-dark-line">
          <View className="items-center">
            <View className="h-12 w-12 items-center justify-center rounded-full bg-safe-soft dark:bg-dark-safe-soft"><FontAwesomeIcon icon={faCheck} size={18} color={colors.safe} /></View>
            <Text className="mt-5 text-center font-display text-2xl text-ink dark:text-dark-ink">Đã đăng nhập</Text>
            <Text className="mt-2 text-center font-sans text-sm leading-6 text-muted dark:text-dark-muted">{session.displayName}</Text>
            <Text className="mt-1 text-center font-sans text-xs leading-5 text-muted dark:text-dark-muted">
              {apiEnabled
                ? `Vai trò từ Auth-Service: ${roleLabels[session.role]}. Quyền vẫn được kiểm tra lại tại API Gateway và từng dịch vụ.`
                : `Vai trò mô phỏng: ${session.role === "ADMIN" ? "Quản trị viên" : "Người dùng"}. Chưa phải phân quyền backend.`}
            </Text>
            <View className="mt-6 w-full gap-3"><Button label="Tiếp tục vào BaoTin" trailingIcon={faArrowRight} onPress={onContinue} /><Button label="Đăng xuất" icon={faRightFromBracket} variant="outline" onPress={onLogout} /></View>
          </View>
      </View>
    );
  }

  return (
    <View className="mx-auto w-full max-w-[500px]">
      <View className="w-full">
        <View className="mb-6">
          <Text className="font-display text-[32px] leading-10 text-ink dark:text-dark-ink">{mode === "login" ? "Đăng nhập BaoTin" : "Tạo tài khoản BaoTin"}</Text>
        </View>
        {mfa ? (
          <View className="border-t border-line pt-5 dark:border-dark-line">
            <Text className="font-medium text-sm text-ink dark:text-dark-ink">Xác thực hai lớp</Text>
            <Text className="mt-2 font-sans text-sm leading-6 text-muted dark:text-dark-muted">
              Nhập {mfa.method === "EMAIL" ? "mã đã gửi tới email" : "mã từ ứng dụng xác thực"} để hoàn tất đăng nhập.
            </Text>
            <View className="mt-5 gap-4">
              <Input label={recoveryCode ? "Mã dự phòng đăng nhập" : "Mã xác thực"} accessibilityLabel={recoveryCode ? "Mã dự phòng đăng nhập" : "Mã xác thực"} value={mfaCode} onChangeText={setMfaCode} keyboardType={recoveryCode ? "default" : "number-pad"} secureTextEntry={recoveryCode} autoComplete={recoveryCode ? "off" : "one-time-code"} autoCapitalize="none" autoCorrect={false} maxLength={128} placeholder={recoveryCode ? "Nhập mã dự phòng một lần" : "123456"} error={formError ?? undefined} />
            </View>
            <View className="mt-5 gap-3">
              <Button className="w-full" label="Xác nhận mã" icon={faLock} loading={submitting} onPress={confirmMfa} />
              {mfa.method !== "EMAIL" ? <Button label={recoveryCode ? "Dùng mã Authenticator" : "Dùng mã dự phòng"} variant="outline" disabled={submitting} onPress={() => { setRecoveryCode(value => !value); setMfaCode(""); setFormError(null); }} /> : onApiMfaResend ? <Button label="Gửi lại mã email MFA" variant="outline" disabled={submitting} onPress={() => { setSubmitting(true); setFormError(null); void onApiMfaResend(mfa.challenge).then(() => setMfaNotice("Đã yêu cầu mã email mới; mã trước đó mất hiệu lực.")).catch(error => setFormError(apiErrorMessage(error, "Chưa gửi lại được mã."))).finally(() => setSubmitting(false)); }} /> : null}
              {mfaNotice ? <Text accessibilityRole="alert" className="font-sans text-xs text-muted dark:text-dark-muted">{mfaNotice}</Text> : null}
              <Button className="w-full" label="Quay lại" variant="ghost" disabled={submitting} onPress={() => { setMfa(null); setMfaCode(""); setFormError(null); }} />
            </View>
          </View>
        ) : (
        <View className="border-t border-line pt-5 dark:border-dark-line">
          <Tabs items={[{ value: "login", label: "Đăng nhập" }, { value: "register", label: "Đăng ký" }]} value={mode} onChange={switchMode} />
          <View className="mt-6 gap-4">
            {mode === "register" ? <Input label="Username" accessibilityLabel="Username đăng ký" value={username} onChangeText={setUsername} autoCapitalize="none" autoCorrect={false} placeholder="an.nguyen" error={errors.username} /> : null}
            {mode === "login"
              ? <Input label="Email, số điện thoại hoặc username" accessibilityLabel="Email, số điện thoại hoặc username" value={identifier} onChangeText={setIdentifier} autoCapitalize="none" autoCorrect={false} placeholder="an.nguyen@example.com" error={errors.identifier} />
              : <Input label="Email hoặc số điện thoại" accessibilityLabel="Email hoặc số điện thoại đăng ký" value={contact} onChangeText={setContact} autoCapitalize="none" autoCorrect={false} placeholder="an.nguyen@example.com" error={errors.contact} />}
            <Input label="Mật khẩu" accessibilityLabel="Mật khẩu" value={password} onChangeText={setPassword} secureTextEntry={!showPassword} placeholder={`Tối thiểu ${apiEnabled ? 12 : 8} ký tự`} error={errors.password} trailing={<Button accessibilityLabel={showPassword ? "Ẩn mật khẩu" : "Hiện mật khẩu"} icon={showPassword ? faEyeSlash : faEye} variant="ghost" size="icon" onPress={() => setShowPassword((value) => !value)} />} />
            {mode === "register" ? <Input label="Xác nhận mật khẩu" accessibilityLabel="Xác nhận mật khẩu" value={confirmPassword} onChangeText={setConfirmPassword} secureTextEntry={!showPassword} placeholder="Nhập lại mật khẩu" error={errors.confirmPassword} /> : null}
          </View>

          {mode === "register" ? <View className="mt-4"><Pressable accessibilityRole="checkbox" accessibilityState={{ checked: terms }} onPress={() => setTerms((value) => !value)} className="flex-row items-start rounded-[8px] py-1 focus-visible:ring-2 focus-visible:ring-accent dark:focus-visible:ring-dark-accent"><View className="mt-0.5"><FontAwesomeIcon icon={terms ? faSquareCheck : faSquare} size={17} color={terms ? colors.accent : colors.muted} /></View><Text className="ml-3 flex-1 font-sans text-xs leading-5 text-muted dark:text-dark-muted">Tôi đồng ý với điều khoản và quyền riêng tư.</Text></Pressable>{errors.terms ? <Text accessibilityRole="alert" className="mt-1.5 font-sans text-xs text-danger dark:text-dark-danger">{errors.terms}</Text> : null}</View> : null}

          {formError ? <View className="mt-4"><Alert icon={faCircleExclamation} tone="destructive" title={mode === "login" ? "Chưa thể đăng nhập" : "Chưa thể đăng ký"} description={formError} /></View> : null}
          <Button className="mt-5 w-full" label={mode === "login" ? "Đăng nhập" : "Tạo tài khoản"} icon={faLock} loading={submitting} onPress={submit} />
          {apiEnabled && recoveryApi ? <Button className="mt-2 w-full" label="Quên mật khẩu hoặc xác minh email" variant="ghost" disabled={submitting} onPress={() => { setPassword(""); setConfirmPassword(""); setRecovering(true); }} /> : null}
          <View className="my-5 flex-row items-center"><View className="h-px flex-1 bg-line dark:bg-dark-line" /><Text className="mx-3 font-sans text-[11px] text-muted dark:text-dark-muted">hoặc</Text><View className="h-px flex-1 bg-line dark:bg-dark-line" /></View>
          <View className="gap-3 sm:flex-row"><Button className="min-w-0 flex-1" accessibilityLabel="Tiếp tục với Google" label="Google" icon={faGoogle} variant="outline" disabled={submitting || (apiEnabled && !onApiSocial)} onPress={() => social("GOOGLE")} /><Button className="min-w-0 flex-1" accessibilityLabel="Tiếp tục với Facebook" label="Facebook" icon={faFacebookF} variant="outline" disabled={submitting || (apiEnabled && !onApiSocial)} onPress={() => social("FACEBOOK")} /></View>
        </View>
        )}
        {!apiEnabled && mode === "login" ? (
          <View className="mt-5 rounded-[10px] border border-line p-3 dark:border-dark-line">
            <Text className="font-medium text-xs text-ink dark:text-dark-ink">Vai trò mô phỏng (demo)</Text>
            <Text className="mt-1 font-sans text-xs leading-5 text-muted dark:text-dark-muted">Chỉ dùng để xem giao diện.</Text>
            <View className="mt-3"><Tabs<AuthRole> items={demoRoles} value={demoRole} onChange={setDemoRole} variant="line" /></View>
          </View>
        ) : null}
        {!wide ? <Text className="mt-4 text-center font-sans text-xs leading-5 text-muted dark:text-dark-muted">Kiểm tra không cần đăng nhập.</Text> : null}
      </View>
    </View>
  );
}
