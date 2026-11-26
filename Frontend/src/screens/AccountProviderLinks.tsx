/* Hallmark · focused account linking · DESIGN.md Material3/Roboto/FontAwesome
 * OAuth transport, reauthentication and existing-session semantics unchanged.
 */
import { useEffect, useRef, useState, type ComponentRef } from "react";
import { AccessibilityInfo, Platform, Text, View } from "react-native";
import { faFacebookF, faGoogle } from "@fortawesome/free-brands-svg-icons";
import type { OAuthProvider } from "../api/oauth";
import type { SocialOutcome } from "../lib/useOAuthFlow";
import { apiErrorMessage } from "../api/client";
import { Button, ErrorPanel, Input } from "../components/ui";

export type ProviderLinkProps = {
  onLink: (provider: OAuthProvider) => Promise<SocialOutcome>;
  onTicket: (ticket: string) => Promise<SocialOutcome>;
  onCancel: () => void;
};
export function AccountProviderLinks({ onLink, onTicket, onCancel }: ProviderLinkProps) {
  const [busy, setBusy] = useState(false), [waiting, setWaiting] = useState(false), [ticket, setTicket] = useState("");
  const [error, setError] = useState<string | null>(null), [notice, setNotice] = useState<string | null>(null);
  const [provider, setProvider] = useState<OAuthProvider | null>(null);
  const feedbackRef = useRef<ComponentRef<typeof View>>(null);
  const progressRef = useRef<ComponentRef<typeof View>>(null);
  const providerName = provider === "GOOGLE" ? "Google" : provider === "FACEBOOK" ? "Facebook" : "";
  useEffect(() => {
    if (!busy) return;
    if (Platform.OS === "web") {
      const region = progressRef.current as unknown as { scrollIntoView?: (options: { block: "nearest"; behavior: "auto" }) => void };
      region?.scrollIntoView?.({ block: "nearest", behavior: "auto" });
    }
  }, [busy]);
  useEffect(() => {
    if (!error && !notice) return;
    if (Platform.OS === "web") {
      const region = feedbackRef.current as unknown as { focus?: () => void; scrollIntoView?: (options: { block: "nearest"; behavior: "auto" }) => void };
      region?.focus?.(); region?.scrollIntoView?.({ block: "nearest", behavior: "auto" });
    } else AccessibilityInfo.announceForAccessibility(error ?? notice ?? "");
  }, [error, notice]);
  async function run(call: () => Promise<SocialOutcome>, linkedProvider = providerName) {
    if (busy) return; setBusy(true); setError(null); setNotice(null);
    try {
      const result = await call();
      if (result.kind === "linked") { setWaiting(false); setTicket(""); setNotice(linkedProvider ? `Đã liên kết ${linkedProvider} với tài khoản BaoTin này.` : "Đã liên kết cách đăng nhập với tài khoản BaoTin này."); }
      else if (result.kind === "ticket") setWaiting(true);
      else throw new Error("Kết quả liên kết chưa hợp lệ. Phiên tài khoản hiện tại được giữ nguyên.");
    } catch (failure) { setError(apiErrorMessage(failure, "Chưa liên kết được nhà cung cấp.")); }
    finally { setBusy(false); }
  }
  const muted = "font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant";
  return <View testID="provider-links" className="gap-4 border-t border-md-outline-variant pt-6 dark:border-dark-md-outline-variant">
    <View className="gap-2">
      <Text accessibilityRole="header" aria-level={2} className="font-medium text-xl leading-7 text-md-on-surface dark:text-dark-md-on-surface">Liên kết đăng nhập</Text>
      <Text className={muted}>Thêm cách đăng nhập bằng Google hoặc Facebook. Bạn vẫn dùng tài khoản BaoTin hiện tại.</Text>
      <Text className={muted}>Trong mục “Xác nhận thao tác”, xác nhận bằng phương thức bảo mật của tài khoản rồi quay lại đây để liên kết.</Text>
    </View>
    <View className="flex-row flex-wrap gap-3">
      <Button appearance="material" label="Liên kết Google" icon={faGoogle} variant="outline" loading={busy && provider === "GOOGLE" && !waiting} disabled={busy || waiting} onPress={() => { setProvider("GOOGLE"); void run(() => onLink("GOOGLE"), "Google"); }} />
      <Button appearance="material" label="Liên kết Facebook" icon={faFacebookF} variant="outline" loading={busy && provider === "FACEBOOK" && !waiting} disabled={busy || waiting} onPress={() => { setProvider("FACEBOOK"); void run(() => onLink("FACEBOOK"), "Facebook"); }} />
    </View>
    {busy ? <View ref={progressRef}><Text accessibilityRole="progressbar" accessibilityLiveRegion="polite" className={muted}>{waiting ? "Đang xác nhận mã liên kết…" : Platform.OS === "web" ? `Đang chờ ${providerName}… Hoàn tất trong cửa sổ đăng nhập, hoặc đóng cửa sổ đó để hủy.` : `Đang chờ ${providerName}… Hoàn tất đăng nhập trong trình duyệt rồi quay lại BaoTin.`}</Text></View> : null}
    {waiting ? <View className="gap-3">
      <Input appearance="material" label="Mã liên kết một lần" description="Sao chép mã sau khi hoàn tất đăng nhập trong trình duyệt rồi dán vào đây." value={ticket} onChangeText={setTicket} editable={!busy} secureTextEntry autoCapitalize="none" autoCorrect={false} maxLength={128} />
      <View className="flex-row flex-wrap gap-3">
        <Button appearance="material" label="Hoàn tất liên kết" loading={busy} disabled={busy || !ticket.trim()} onPress={() => void run(() => onTicket(ticket.trim()))} />
        <Button appearance="material" label="Hủy yêu cầu liên kết" variant="ghost" disabled={busy} onPress={() => { onCancel(); setTicket(""); setWaiting(false); setError(null); setNotice(null); }} />
      </View>
    </View> : null}
    {error || notice ? <View ref={feedbackRef} testID="provider-link-feedback" {...(Platform.OS === "web" ? { tabIndex: -1 } : {})} className="gap-2">
      {error ? <ErrorPanel title="Chưa hoàn tất liên kết" description={error} /> : <Text accessibilityRole="alert" className="font-sans text-sm leading-6 text-md-on-surface dark:text-dark-md-on-surface">{notice}</Text>}
    </View> : null}
  </View>;
}
