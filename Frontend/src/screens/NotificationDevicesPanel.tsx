/* Hallmark · notification device settings · DESIGN.md Material3/Roboto/FontAwesome
 * Existing device API, externally supplied token and foreground clearing retained.
 */
import { useEffect, useRef, useState, type ComponentRef } from "react";
import { AccessibilityInfo, AppState, Platform, Pressable, Text, View } from "react-native";
import type { BaoTinApi } from "../api/baotin";
import type { DeviceApiResource } from "../api/contracts";
import { apiErrorMessage } from "../api/client";
import { Button, ErrorPanel, Input, LoadingPanel } from "../components/ui";

export function NotificationDevicesPanel({ api }: { api: BaoTinApi }) {
  const [devices, setDevices] = useState<DeviceApiResource[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errorAction, setErrorAction] = useState<"load" | "register" | "remove">("load");
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [registering, setRegistering] = useState(false);
  const [platform, setPlatform] = useState<"WEB" | "ANDROID" | "IOS">("ANDROID");
  const [providerToken, setProviderToken] = useState("");
  const [tokenInvalid, setTokenInvalid] = useState(false);
  const [deviceId, setDeviceId] = useState("");
  const errorRef = useRef<ComponentRef<typeof View>>(null);
  const platformRefs = useRef<Array<ComponentRef<typeof Pressable> | null>>([]);
  useEffect(() => {
    if (!error) return;
    if (Platform.OS === "web") {
      const region = errorRef.current as unknown as { focus?: () => void; scrollIntoView?: (options: { block: "center"; behavior: "auto" }) => void };
      region?.focus?.(); region?.scrollIntoView?.({ block: "center", behavior: "auto" });
    } else AccessibilityInfo.announceForAccessibility(error);
  }, [error]);
  useEffect(() => {
    const clear = () => setProviderToken("");
    const sub = AppState.addEventListener("change", state => { if (state !== "active") clear(); });
    const hidden = () => { if (document.visibilityState === "hidden") clear(); };
    if (typeof document !== "undefined") document.addEventListener("visibilitychange", hidden);
    return () => { sub.remove(); if (typeof document !== "undefined") document.removeEventListener("visibilitychange", hidden); };
  }, []);
  useEffect(() => {
    let current = true; setBusy(true); setError(null); setErrorAction("load");
    api.devices().then(result => { if (current) setDevices(result.items); }).catch(failure => { if (current) { setDevices(null); setError(apiErrorMessage(failure, "Chưa tải được thiết bị thông báo.")); } }).finally(() => { if (current) setBusy(false); });
    return () => { current = false; };
  }, [api, revision]);
  async function remove(id: string) {
    if (busy) return; setBusy(true); setError(null); setErrorAction("remove"); setNotice(null);
    try { await api.deleteDevice(id); setConfirm(null); setNotice("Đã thu hồi đăng ký nhận thông báo của thiết bị."); setRevision(value => value + 1); }
    catch (failure) { setError(apiErrorMessage(failure, "Chưa thu hồi được thiết bị thông báo.")); }
    finally { setBusy(false); }
  }
  async function register() {
    if (busy) return;
    setErrorAction("register"); setNotice(null); setTokenInvalid(false);
    const token = providerToken.trim();
    if (token.startsWith("Expo") || token.startsWith("Exponent") || token.length < 20 || token.length > 512 || /\s/.test(token)) { setTokenInvalid(true); setError("Mã đăng ký thiết bị không hợp lệ."); return; }
    setBusy(true); setError(null);
    try { await api.registerDevice({ platform, token, deviceId: deviceId.trim() }); setProviderToken(""); setDeviceId(""); setRegistering(false); setNotice("Đã đăng ký thiết bị nhận thông báo."); setRevision(value => value + 1); }
    catch (failure) { setError(apiErrorMessage(failure, "Chưa đăng ký được thiết bị thông báo.")); }
    finally { setBusy(false); }
  }
  const muted = "font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant";
  const text = "font-sans text-sm leading-6 text-md-on-surface dark:text-dark-md-on-surface";
  const platformLabels = { WEB: "Trình duyệt web", ANDROID: "Android", IOS: "iOS" };
  const formatDate = (value: string) => Number.isFinite(new Date(value).getTime()) ? new Date(value).toLocaleString("vi-VN") : "Chưa rõ thời điểm";
  return <View testID="notification-devices" className="gap-5">
    <View className="gap-2"><Text accessibilityRole="header" aria-level={2} className="font-medium text-xl leading-7 text-md-on-surface dark:text-dark-md-on-surface">Thiết bị nhận thông báo</Text><Text className={muted}>Quản lý đăng ký nhận thông báo. Thu hồi đăng ký không đăng xuất thiết bị.</Text></View>
    {busy && !devices ? <LoadingPanel label="Đang tải thiết bị thông báo" /> : null}
    {busy && devices ? <Text accessibilityRole="progressbar" accessibilityLiveRegion="polite" className={muted}>Đang xử lý thiết bị…</Text> : null}
    {notice ? <Text accessibilityLiveRegion="polite" className={text}>{notice}</Text> : null}
    {error ? <View ref={errorRef} testID="device-error" {...(Platform.OS === "web" ? { tabIndex: -1 } : {})}><ErrorPanel title={errorAction === "load" ? "Chưa tải được thiết bị" : errorAction === "remove" ? "Chưa thu hồi được đăng ký" : "Chưa đăng ký được thiết bị"} description={errorAction === "load" && notice ? `Thao tác đã hoàn tất, nhưng danh sách chưa cập nhật. Chọn “Thử lại” để tải lại danh sách, không cần thực hiện lại thao tác. ${error}` : error} onRetry={errorAction === "load" && !busy ? () => setRevision(value => value + 1) : undefined} /></View> : null}
    <View className="gap-4">{devices?.map(device => <View key={device.id} className="gap-3 border-b border-md-outline-variant pb-5 dark:border-dark-md-outline-variant">
      <View className="flex-row flex-wrap items-center justify-between gap-2"><Text className="font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface">{platformLabels[device.platform]}</Text><Text className={muted}>{device.active ? "Đã đăng ký" : "Đã tắt"}</Text></View>
      <Text className={muted}>Lần ghi nhận gần nhất: {formatDate(device.lastSeenAt)}</Text>
      <Text selectable className={muted}>Mã thiết bị: {device.deviceId}</Text>
      {confirm === device.id ? <View accessibilityRole="alert" className="gap-3 rounded-xl bg-md-surface-low p-4 dark:bg-dark-md-surface-low">
        <Text className={text}>Thu hồi đăng ký của {platformLabels[device.platform]} được ghi nhận lúc {formatDate(device.lastSeenAt)}? Thiết bị sẽ cần đăng ký lại để nhận thông báo.</Text>
        <View className="flex-row flex-wrap gap-2"><Button appearance="material" label="Xác nhận thu hồi thiết bị" variant="destructive" loading={busy} disabled={busy} onPress={() => void remove(device.id)} /><Button appearance="material" label="Hủy thu hồi thiết bị" variant="ghost" disabled={busy} onPress={() => setConfirm(null)} /></View>
      </View> : <Button appearance="material" label="Thu hồi đăng ký" accessibilityLabel={`Thu hồi đăng ký ${platformLabels[device.platform]}, ${device.deviceId}`} variant="ghost" className="self-start" disabled={busy} onPress={() => setConfirm(device.id)} />}
    </View>)}</View>
    {devices?.length === 0 ? <View className="gap-2 py-3"><Text className={text}>Chưa có thiết bị nhận thông báo.</Text><Text className={muted}>Thiết bị sẽ xuất hiện tại đây sau khi được đăng ký.</Text></View> : null}
    <View className="flex-row flex-wrap gap-3"><Button appearance="material" label="Làm mới thiết bị thông báo" variant="outline" disabled={busy} onPress={() => setRevision(value => value + 1)} />{!registering ? <Button appearance="material" label="Đăng ký thiết bị" variant="ghost" disabled={busy} onPress={() => { setRegistering(true); setNotice(null); }} /> : null}</View>
    {registering ? <View className="gap-4 border-t border-md-outline-variant pt-5 dark:border-dark-md-outline-variant">
      <Text accessibilityRole="header" aria-level={3} className="font-medium text-lg leading-6 text-md-on-surface dark:text-dark-md-on-surface">Thêm thiết bị bằng mã</Text>
      <Text className={muted}>Chỉ dùng khi bạn đã có mã đăng ký được cấp cho thiết bị này. BaoTin chưa tự tạo mã trên thiết bị.</Text>
      <View accessibilityRole="radiogroup" accessibilityLabel="Nền tảng thiết bị" className="flex-row overflow-hidden rounded-xl border border-md-outline dark:border-dark-md-outline">
        {(["WEB", "ANDROID", "IOS"] as const).map((value, index, values) => <Pressable key={value} ref={node => { platformRefs.current[index] = node; }} accessibilityRole="radio" accessibilityState={{ checked: platform === value, disabled: busy }} aria-checked={platform === value} tabIndex={platform === value ? 0 : -1} disabled={busy} onPress={() => setPlatform(value)} {...(Platform.OS === "web" ? { onKeyDown: (raw: unknown) => {
          const event = raw as { key?: string; preventDefault: () => void }; if (busy || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End"].includes(event.key ?? "")) return; event.preventDefault();
          const next = event.key === "Home" ? 0 : event.key === "End" ? 2 : (index + (["ArrowLeft", "ArrowUp"].includes(event.key ?? "") ? -1 : 1) + 3) % 3;
          setPlatform(values[next]!); (platformRefs.current[next] as unknown as { focus?: () => void })?.focus?.();
        } } : {})} className={`min-h-12 min-w-0 flex-1 items-center justify-center px-2 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary ${platform === value ? "bg-md-secondary-container dark:bg-dark-md-secondary-container" : "hover:bg-md-surface-low dark:hover:bg-dark-md-surface-low"} ${busy ? "opacity-50" : "active:opacity-75"}`}><Text className={`font-medium text-sm ${platform === value ? "text-md-on-secondary-container dark:text-dark-md-on-secondary-container" : "text-md-on-surface-variant dark:text-dark-md-on-surface-variant"}`}>{value === "WEB" ? "Web" : platformLabels[value]}</Text></Pressable>)}
      </View>
      <Input appearance="material" label="Mã thiết bị" description="Mã nhận diện dùng để phân biệt thiết bị, không phải mã xác minh đăng nhập." value={deviceId} onChangeText={setDeviceId} editable={!busy} maxLength={128} autoCapitalize="none" autoCorrect={false} />
      <Input appearance="material" label="Mã đăng ký" description="Chỉ dùng mã được cấp cho thiết bị này. Mã sẽ được xóa khi bạn rời ứng dụng." error={tokenInvalid ? "Dán đầy đủ mã đăng ký được cấp, không có khoảng trắng ở giữa. Không dùng mã xác minh đăng nhập." : undefined} value={providerToken} onChangeText={value => { setProviderToken(value); setTokenInvalid(false); }} editable={!busy} secureTextEntry maxLength={512} autoCapitalize="none" autoCorrect={false} />
      <View className="flex-row flex-wrap gap-3"><Button appearance="material" label="Lưu thiết bị thông báo" loading={busy} disabled={busy || !deviceId.trim() || !providerToken.trim()} onPress={() => void register()} /><Button appearance="material" label="Hủy thêm thiết bị" variant="ghost" disabled={busy} onPress={() => { setRegistering(false); setProviderToken(""); if (errorAction === "register") setError(null); }} /></View>
    </View> : null}
  </View>;
}
