/* Hallmark · focused account settings · DESIGN.md Material3/Roboto/FontAwesome
 * Existing profile/session/MFA invalidation and provider/device APIs preserved.
 */
import { useEffect, useRef, useState } from "react";
import { Platform, Text, View } from "react-native";
import type { AccountApi, RecoveryApi, SessionResource } from "../api/account";
import type { ApiProfile } from "../api/auth";
import { apiErrorMessage } from "../api/client";
import type { PageResponse } from "../api/contracts";
import { Button, ErrorPanel, Input, LoadingPanel, Tabs } from "../components/ui";
import { AccountSecurityPanel } from "./AccountSecurityPanel";
import type { BaoTinApi } from "../api/baotin";
import { NotificationDevicesPanel } from "./NotificationDevicesPanel";
import { AccountProviderLinks, type ProviderLinkProps } from "./AccountProviderLinks";

const text = "font-sans text-sm leading-6 text-ink dark:text-dark-ink";
const muted = "font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant";
const formatDate = (value: string) => Number.isFinite(new Date(value).getTime()) ? new Date(value).toLocaleString("vi-VN") : "Chưa rõ thời điểm";
const roleLabels = { USER: "Người dùng", MODERATOR: "Kiểm duyệt viên", ADMIN: "Quản trị viên" } as const;
export type AccountWorkspaceProps = {
  api: AccountApi;
  recovery: RecoveryApi;
  notifications: BaoTinApi | null;
  providerLinks: ProviderLinkProps;
  onProfile: (profile: ApiProfile) => void;
  onInvalidated: (recoveryCodes?: string[]) => void;
  onContinue: () => void;
  onLogout: () => void;
};

export function AccountWorkspace(props: AccountWorkspaceProps) {
  const [tab, setTab] = useState<"profile" | "security" | "sessions" | "devices">("profile");
  const [nameDraft, setNameDraft] = useState<string | null>(null);
  const [profile, setProfile] = useState<ApiProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let current = true;
    setError(null);
    props.api.profile().then(result => { if (current) { setProfile(result); setNameDraft(draft => draft ?? result.displayName); } }).catch(failure => { if (current) { setProfile(null); setError(apiErrorMessage(failure, "Chưa tải được tài khoản.")); } });
    return () => { current = false; };
  }, [props.api, revision]);
  const updateProfile = (next: ApiProfile) => { setProfile(next); setNameDraft(next.displayName); props.onProfile(next); };
  return <View className="mx-auto w-full max-w-[760px] gap-5">
    <Text accessibilityRole="header" className="font-display text-[28px] leading-9 text-md-on-surface dark:text-dark-md-on-surface md:text-[32px] md:leading-10">Tài khoản và bảo mật</Text>
    <Tabs appearance="material" label="Tài khoản và bảo mật" panelId="account-panel" items={[{ value: "profile", label: "Hồ sơ" }, { value: "security", label: "Bảo mật" }, { value: "sessions", label: "Phiên" }, { value: "devices", label: "Thiết bị" }]} value={tab} onChange={setTab} />
    <View nativeID="account-panel" accessibilityRole={Platform.OS === "web" ? "tabpanel" as never : undefined} aria-labelledby={`account-panel-${tab}`} className="gap-5">
    {error ? <ErrorPanel description={error} onRetry={() => setRevision(value => value + 1)} /> : !profile ? <LoadingPanel label="Đang tải hồ sơ" /> : tab === "profile" ? <ProfilePanel name={nameDraft ?? profile.displayName} setName={setNameDraft} api={props.api} recovery={props.recovery} profile={profile} onProfile={updateProfile} /> : tab === "security" ? <AccountSecurityPanel api={props.api} profile={profile} onInvalidated={props.onInvalidated} /> : tab === "devices" ? props.notifications ? <NotificationDevicesPanel api={props.notifications} /> : <View className="gap-2 py-4"><Text accessibilityRole="header" aria-level={2} className="font-medium text-lg text-md-on-surface dark:text-dark-md-on-surface">Thiết bị nhận thông báo chưa khả dụng</Text><Text className={muted}>Chưa kết nối được chức năng thiết bị. Các mục tài khoản khác vẫn dùng được.</Text></View> : <SessionsPanel api={props.api} onInvalidated={props.onInvalidated} />}
    {profile && tab === "security" ? <AccountProviderLinks {...props.providerLinks} /> : null}
    </View>
    <View className="flex-row flex-wrap gap-3 border-t border-line pt-5 dark:border-dark-line"><Button appearance="material" label="Tiếp tục vào BaoTin" variant="outline" onPress={props.onContinue} /><Button appearance="material" label="Đăng xuất" variant="ghost" onPress={props.onLogout} /></View>
  </View>;
}

function ProfilePanel({ name, setName, api, recovery, profile, onProfile }: { name: string; setName: (value: string) => void; api: AccountApi; recovery: RecoveryApi; profile: ApiProfile; onProfile: (profile: ApiProfile) => void }) {
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  async function run(action: () => Promise<void>) {
    if (busy) return; setBusy(true); setError(null); setNotice(null);
    try { await action(); } catch (failure) { setError(apiErrorMessage(failure, "Chưa cập nhật được hồ sơ.")); } finally { setBusy(false); }
  }
  return <View className="gap-4">
    <Text className={text}>{profile.username} · {roleLabels[profile.role]}</Text>
    <Text className={muted}>Email: {profile.email ?? "Chưa có"} · {profile.emailVerified ? "Đã xác minh" : "Chưa xác minh"}</Text>
    {profile.phone ? <Text className={muted}>Điện thoại: {profile.phone} · {profile.phoneVerified ? "Đã xác minh" : "Chưa xác minh"}</Text> : null}
    {busy ? <Text accessibilityRole="progressbar" accessibilityLiveRegion="polite" className={muted}>Đang xử lý yêu cầu…</Text> : null}
    <Input appearance="material" label="Tên hiển thị" value={name} onChangeText={setName} maxLength={120} editable={!busy} />
    <Button appearance="material" label="Lưu hồ sơ" disabled={busy || !name.trim() || name.trim() === profile.displayName.trim()} onPress={() => void run(async () => { onProfile(await api.updateProfile(name)); setNotice("Đã lưu hồ sơ."); })} />
    {!profile.emailVerified && profile.email ? <><Button appearance="material" label="Gửi lại email xác minh" variant="outline" disabled={busy} onPress={() => void run(async () => { await api.resendVerification(); setNotice("Đã yêu cầu gửi email xác minh. Kiểm tra hộp thư của bạn."); })} /><Input appearance="material" label="Mã xác minh email" value={token} onChangeText={setToken} secureTextEntry autoCapitalize="none" autoCorrect={false} maxLength={128} /><Button appearance="material" label="Xác minh email" variant="outline" disabled={busy || !token.trim()} onPress={() => void run(async () => { await recovery.verifyEmail(token.trim()); setToken(""); onProfile(await api.profile()); setNotice("Email đã được xác minh."); })} /></> : null}
    {profile.phone && !profile.phoneVerified ? <Text className={muted}>Xác minh số điện thoại hiện chưa khả dụng.</Text> : null}
    {error ? <ErrorPanel description={error} /> : null}{notice ? <Text accessibilityRole="alert" className={text}>{notice}</Text> : null}
  </View>;
}

function SessionsPanel({ api, onInvalidated }: { api: AccountApi; onInvalidated: () => void }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResponse<SessionResource> | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const generation = useRef(0);
  async function load() {
    const current = ++generation.current; setBusy(true); setError(null);
    try { const result = await api.sessions(page); if (current === generation.current) setData(result); }
    catch (failure) { if (current === generation.current) { setData(null); setError(apiErrorMessage(failure, "Chưa tải được các phiên.")); } }
    finally { if (current === generation.current) setBusy(false); }
  }
  useEffect(() => { void load(); return () => { generation.current++; }; }, [api, page]);
  async function revoke(id: string) {
    if (busy) return; setBusy(true); setError(null); setNotice(null);
    try {
      if (id === "all") { await api.logoutAll(); onInvalidated(); return; }
      await api.revokeSession(id); await api.profile();
      setConfirm(null); await load(); setNotice("Đã đăng xuất phiên đã chọn.");
    } catch (failure) { setError(apiErrorMessage(failure, "Chưa thu hồi được phiên.")); }
    finally { setBusy(false); }
  }
  return <View className="gap-4">
    <Text className={muted}>Các phiên đăng nhập đang hoạt động. Đăng xuất phiên đang dùng sẽ yêu cầu bạn đăng nhập lại. Danh sách hiện chỉ cung cấp thời gian và trạng thái xác thực.</Text>
    {error ? <ErrorPanel description={error} onRetry={() => void load()} /> : null}
    {busy ? <LoadingPanel label="Đang xử lý phiên đăng nhập" /> : null}
    {notice ? <Text accessibilityLiveRegion="polite" className={text}>{notice}</Text> : null}
    {data?.items.map(item => <View key={item.id} className="gap-2 border-b border-line pb-4 dark:border-dark-line">
      <Text className={text}>Tạo lúc {formatDate(item.createdAt)}</Text>
      <Text className={muted}>Hết hạn {formatDate(item.expiresAt)} · Xác thực hai bước: {item.mfaAuthenticated ? "Có" : "Không"}</Text>
      <Button appearance="material" label="Đăng xuất phiên này" variant="outline" disabled={busy} onPress={() => setConfirm(item.id)} />
      {confirm === item.id ? <View accessibilityRole="alert" className="gap-3 rounded-xl bg-md-surface-low p-4 dark:bg-dark-md-surface-low"><Text className={text}>Đăng xuất phiên được tạo lúc {formatDate(item.createdAt)}? Nếu đây là phiên đang dùng, bạn sẽ cần đăng nhập lại.</Text><View className="flex-row flex-wrap gap-2"><Button appearance="material" label="Xác nhận đăng xuất phiên" variant="destructive" disabled={busy} onPress={() => void revoke(item.id)} /><Button appearance="material" label="Giữ phiên đăng nhập" variant="ghost" onPress={() => setConfirm(null)} /></View></View> : null}
    </View>)}
    {data?.items.length === 0 ? <Text className={text}>Không có phiên còn hoạt động.</Text> : null}
    {data && data.totalPages > 1 ? <View className="flex-row flex-wrap gap-3"><Button appearance="material" label="Phiên trang trước" variant="outline" disabled={busy || page === 0} onPress={() => setPage(value => value - 1)} /><Text className={muted}>{page + 1}/{data.totalPages}</Text><Button appearance="material" label="Phiên trang sau" variant="outline" disabled={busy || page + 1 >= data.totalPages} onPress={() => setPage(value => value + 1)} /></View> : null}
    <Button appearance="material" label="Đăng xuất mọi thiết bị" variant="outline" disabled={busy} onPress={() => setConfirm("all")} />
    {confirm === "all" ? <View className="gap-2"><Text className={muted}>Mọi phiên, kể cả phiên này, sẽ bị thu hồi.</Text><Button appearance="material" label="Xác nhận đăng xuất mọi thiết bị" variant="destructive" disabled={busy} onPress={() => void revoke("all")} /><Button appearance="material" label="Giữ phiên đăng nhập" variant="ghost" onPress={() => setConfirm(null)} /></View> : null}
  </View>;
}
