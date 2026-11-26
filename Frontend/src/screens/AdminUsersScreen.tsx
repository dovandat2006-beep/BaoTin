/* Hallmark · focused user administration · DESIGN.md Material3/Roboto/FontAwesome
 * Existing ADMIN/step-up, lock, generation and mutation handlers preserved.
 */
import { useEffect, useRef, useState, type ComponentRef } from "react";
import { AccessibilityInfo, AppState, Platform, ScrollView, Text, View } from "react-native";
import type { AccountApi, ManagedRole, ManagedStatus, ManagedUser } from "../api/account";
import { ApiProblem, apiErrorMessage } from "../api/client";
import type { PageResponse } from "../api/contracts";
import { canAccessAdmin, type AuthSession } from "../lib/auth";
import { Button, ErrorPanel, Input, LoadingPanel, Sheet } from "../components/ui";

type Change = { user: ManagedUser; role?: ManagedRole; status?: ManagedStatus };
const body = "font-sans text-sm leading-6 text-md-on-surface dark:text-dark-md-on-surface";
const muted = "font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant";
const roleLabels = { USER: "Người dùng", MODERATOR: "Kiểm duyệt viên", ADMIN: "Quản trị viên" };
const statusLabels = { ACTIVE: "Đang hoạt động", LOCKED: "Đã khóa", DISABLED: "Đã vô hiệu hóa" };
const statusActions = { ACTIVE: "Kích hoạt", LOCKED: "Khóa", DISABLED: "Vô hiệu hóa" };
export function AdminUsersScreen({ api, session }: { api: AccountApi; session: AuthSession }) {
  const [code, setCode] = useState("");
  const [unlocked, setUnlocked] = useState(false);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResponse<ManagedUser> | null>(null);
  const [change, setChange] = useState<Change | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [expandedUser, setExpandedUser] = useState<string | null>(null);
  const errorRef = useRef<ComponentRef<typeof View>>(null);
  const generation = useRef(0);
  const allowed = canAccessAdmin(session);
  useEffect(() => { if (!unlocked) setExpandedUser(null); }, [unlocked]);
  useEffect(() => {
    if (!error) return;
    if (Platform.OS === "web") {
      const region = errorRef.current as unknown as { focus?: () => void; scrollIntoView?: (options: { block: "nearest"; behavior: "auto" }) => void };
      region?.focus?.(); region?.scrollIntoView?.({ block: "nearest", behavior: "auto" });
    } else AccessibilityInfo.announceForAccessibility(error);
  }, [error]);
  function lock() { generation.current++; setData(null); setChange(null); setCode(""); setUnlocked(false); }
  useEffect(() => {
    const sub = AppState.addEventListener("change", state => { if (state !== "active") lock(); });
    const hidden = () => { if (document.visibilityState === "hidden") lock(); };
    if (typeof document !== "undefined") document.addEventListener("visibilitychange", hidden);
    return () => { generation.current++; sub.remove(); if (typeof document !== "undefined") document.removeEventListener("visibilitychange", hidden); };
  }, []);
  useEffect(() => {
    if (!unlocked) return;
    const timer = setTimeout(lock, 300_000);
    return () => clearTimeout(timer);
  }, [unlocked]);
  async function load(targetPage = page) {
    const version = generation.current;
    const result = await api.adminUsers(targetPage);
    if (version === generation.current) setData(result);
  }
  async function run(operation: () => Promise<void>) {
    if (!allowed || busy) return; setBusy(true); setError(null); setNotice(null);
    try { await operation(); }
    catch (failure) {
      if (failure instanceof ApiProblem && [401, 403].includes(failure.problem.status)) lock();
      const code = failure instanceof ApiProblem ? failure.problem.code : undefined;
      setError(code === "UNAUTHORIZED" ? "Mã xác thực không hợp lệ hoặc đã dùng. Hãy chờ mã Authenticator mới rồi thử lại." : code === "TARGET_MFA_REQUIRED" ? "Tài khoản đích cần xác minh email và bật Authenticator trước khi được cấp quyền kiểm duyệt." : code === "STEP_UP_REQUIRED" ? "Xác thực quản trị đã hết hạn. Nhập mã Authenticator mới để tiếp tục." : code === "MFA_REQUIRED" ? "Tài khoản quản trị cần bật Authenticator trong phần Tài khoản và bảo mật." : apiErrorMessage(failure, "Chưa hoàn tất thao tác quản trị."));
    } finally { setBusy(false); }
  }
  async function unlock() {
    const version = generation.current;
    await api.stepUp(code.trim());
    if (version !== generation.current) return;
    setCode("");
    await load(0);
    if (version === generation.current) { setPage(0); setUnlocked(true); setNotice("Đã xác thực và tải danh sách người dùng."); }
  }
  async function apply() {
    if (!change) return;
    if (change.role) await api.setRole(change.user.id, change.role);
    else if (change.status) await api.setStatus(change.user.id, change.status);
    setChange(null); await load(); setNotice("Đã cập nhật. Các phiên đăng nhập cũ đã được thu hồi.");
  }
  if (!allowed) return null;
  return <View testID="admin-users" className="w-full min-w-0 gap-5">
    <Text accessibilityRole="header" className="font-display text-[28px] leading-9 text-md-on-surface dark:text-dark-md-on-surface md:text-[32px] md:leading-10">Quản lý người dùng</Text>
    {error && !change ? <View ref={errorRef} {...(Platform.OS === "web" ? { tabIndex: -1 } : {})}><ErrorPanel title="Chưa hoàn tất thao tác quản trị" description={error} />{unlocked ? <Text className={muted}>Làm mới danh sách để kiểm tra trạng thái trước khi thực hiện lại thay đổi.</Text> : null}</View> : null}
    {notice ? <Text accessibilityLiveRegion="polite" className={body}>{notice}</Text> : null}
    {!unlocked ? <View className="max-w-lg gap-4"><Text className={muted}>Xác nhận bằng mã 6 chữ số mới từ ứng dụng xác thực trước khi xem và quản lý tài khoản. Nếu vừa dùng mã để đăng nhập, hãy chờ mã tiếp theo.</Text><Input appearance="material" label="Mã xác thực quản trị" description="Màn hình sẽ tự khóa khi bạn rời ứng dụng hoặc hết thời gian xác thực." value={code} onChangeText={setCode} secureTextEntry keyboardType="number-pad" maxLength={6} autoComplete="one-time-code" editable={!busy} /><Button appearance="material" label="Xác thực và tải người dùng" loading={busy} disabled={busy || !/^\d{6}$/.test(code.trim())} onPress={() => void run(unlock)} /></View> : <>
      <View className="flex-row flex-wrap gap-3"><Button appearance="material" label="Làm mới người dùng" variant="outline" disabled={busy} onPress={() => void run(async () => { await load(); setNotice("Đã làm mới danh sách người dùng."); })} /><Button appearance="material" label="Khóa màn hình quản trị" variant="ghost" onPress={() => { lock(); setError(null); setNotice("Đã khóa màn hình quản trị."); }} /></View>
      {busy ? <LoadingPanel label="Đang xử lý quản trị" /> : null}
      {data?.items.map(user => <View key={user.id} className="min-w-0 gap-3 border-b border-md-outline-variant pb-5 dark:border-dark-md-outline-variant">
        <Text selectable className="font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface">{user.displayName} · {user.username}</Text>
        <Text className={body}>{roleLabels[user.role]} · {statusLabels[user.status]}</Text>
        <Text className={muted}>Xác thực hai bước: {user.mfaEnabled ? "Đã bật" : "Chưa bật"}</Text>
        {user.role !== "ADMIN" ? <>
          <Button appearance="material" label={expandedUser === user.id ? "Ẩn thao tác" : "Quản lý tài khoản"} accessibilityLabel={expandedUser === user.id ? `Ẩn thao tác cho ${user.username}` : `Quản lý tài khoản ${user.username}`} accessibilityState={{ expanded: expandedUser === user.id, disabled: busy }} aria-expanded={expandedUser === user.id} aria-controls={`user-actions-${user.id}`} variant="outline" className="self-start" disabled={busy} onPress={() => setExpandedUser(value => value === user.id ? null : user.id)} />
          {expandedUser === user.id ? <View nativeID={`user-actions-${user.id}`} className="gap-4">
            <View className="gap-2"><Text className={muted}>Quyền truy cập</Text><Text className={muted}>Cấp quyền kiểm duyệt yêu cầu tài khoản đã xác minh email và bật ứng dụng xác thực.</Text><View className="flex-row flex-wrap gap-2">{(["USER", "MODERATOR"] as const).map(role => <Button appearance="material" key={role} label={roleLabels[role]} accessibilityLabel={`Đặt quyền ${roleLabels[role]} cho ${user.username}`} variant="outline" disabled={busy || role === user.role} onPress={() => setChange({ user, role })} />)}</View></View>
            <View className="gap-2"><Text className={muted}>Trạng thái tài khoản</Text><View className="flex-row flex-wrap gap-2">{(["ACTIVE", "LOCKED", "DISABLED"] as const).map(status => <Button appearance="material" key={status} label={statusActions[status]} accessibilityLabel={`${statusActions[status]} tài khoản ${user.username}`} variant="ghost" disabled={busy || status === user.status} onPress={() => setChange({ user, status })} />)}</View></View>
          </View> : null}
        </> : <Text className={muted}>Tài khoản quản trị được quản lý riêng.</Text>}
      </View>)}
      {data?.items.length === 0 ? <Text className={body}>Không có tài khoản trong trang này.</Text> : null}
      {data && data.totalPages > 1 ? <View className="flex-row flex-wrap items-center gap-3"><Button appearance="material" label="Người dùng trang trước" variant="outline" disabled={busy || page === 0} onPress={() => void run(async () => { await load(page - 1); setPage(value => value - 1); })} /><Text className={muted}>{page + 1}/{data.totalPages}</Text><Button appearance="material" label="Người dùng trang sau" variant="outline" disabled={busy || page + 1 >= data.totalPages} onPress={() => void run(async () => { await load(page + 1); setPage(value => value + 1); })} /></View> : null}
    </>}
    <Sheet appearance="material" dismissDisabled={busy} visible={Boolean(change)} title="Xác nhận thay đổi người dùng" onClose={() => { if (!busy) setChange(null); }} footer={<View className="gap-2"><Button appearance="material" label={change?.role ? "Xác nhận đổi quyền" : change?.status ? `Xác nhận ${statusActions[change.status].toLowerCase()}` : "Xác nhận thay đổi người dùng"} variant="destructive" loading={busy} disabled={busy} onPress={() => void run(apply)} /><Button appearance="material" label="Hủy thay đổi" variant="ghost" disabled={busy} onPress={() => setChange(null)} /></View>}>
      <ScrollView contentContainerStyle={{ padding: 20, gap: 16 }}>
        <Text className="font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface">{change?.user.displayName} · {change?.user.username}</Text>
        <Text className={body}>{change?.role ? `Đổi quyền từ ${roleLabels[change.user.role]} thành ${roleLabels[change.role]}?` : change?.status ? `${statusActions[change.status]} tài khoản này? Trạng thái hiện tại: ${statusLabels[change.user.status]}.` : ""}</Text>
        <Text className={muted}>Các phiên đăng nhập của tài khoản này sẽ bị thu hồi.</Text>
        {error ? <View ref={errorRef} {...(Platform.OS === "web" ? { tabIndex: -1 } : {})}><ErrorPanel title="Chưa cập nhật được tài khoản" description={error} /></View> : null}
      </ScrollView>
    </Sheet>
  </View>;
}
