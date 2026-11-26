/* Hallmark · URL monitoring list · DESIGN.md Material3 · consumer utility
 * Live watch/preference callbacks preserved; no invented check interval or delivery.
 */
import { faEye } from "@fortawesome/free-solid-svg-icons/faEye";
import { faFloppyDisk } from "@fortawesome/free-solid-svg-icons/faFloppyDisk";
import { faPlus } from "@fortawesome/free-solid-svg-icons/faPlus";
import { faTrashCan } from "@fortawesome/free-solid-svg-icons/faTrashCan";
import { useState } from "react";
import { Platform, Pressable, Switch, Text, View } from "react-native";
import { apiErrorMessage } from "../api/client";
import { DemoDataNotice } from "../components/DemoDataNotice";
import { RiskBadge } from "../components/RiskBadge";
import { Button, EmptyState, ErrorPanel, Input, LoadingPanel } from "../components/ui";
import type { WatchItem } from "../data/mock";
import { scanTypeLabels } from "../lib/risk";
import { useAppTheme } from "../theme/AppTheme";

export type WatchlistPreferences = {
  risk: boolean;
  report: boolean;
  push: boolean;
  inApp: boolean;
};

type Props = {
  items: WatchItem[];
  demo?: boolean;
  onRemove: (id: string) => void | Promise<void>;
  onOpenSearch: () => void;
  onAdd?: (url: string) => Promise<void>;
  preferences?: WatchlistPreferences;
  preferencesLoading?: boolean;
  preferenceError?: string | null;
  onUpdatePreference?: (key: keyof WatchlistPreferences, value: boolean) => void | Promise<void>;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  onRetryPreferences?: () => void;
};

const defaultPreferences: WatchlistPreferences = { risk: true, report: true, push: true, inApp: true };

function normalizeUrlInput(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  try {
    const url = new URL(trimmed.includes("://") ? trimmed : `https://${trimmed}`);
    return url.protocol === "http:" || url.protocol === "https:" ? trimmed : null;
  } catch {
    return null;
  }
}

export function WatchlistScreen({
  items,
  demo = false,
  onRemove,
  onOpenSearch,
  onAdd,
  preferences,
  preferencesLoading = false,
  preferenceError = null,
  onUpdatePreference,
  loading = false,
  error = null,
  onRetry,
  onRetryPreferences
}: Props) {
  const { colors } = useAppTheme();
  const [localPreferences, setLocalPreferences] = useState(defaultPreferences);
  const effectivePreferences = preferences ?? localPreferences;
  const [addOpen, setAddOpen] = useState(false);
  const [url, setUrl] = useState("");
  const [addError, setAddError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [savingPreference, setSavingPreference] = useState<keyof WatchlistPreferences | null>(null);
  const preferenceRows: { key: keyof WatchlistPreferences; title: string; note: string }[] = [
    { key: "risk", title: "Thay đổi điểm rủi ro", note: "Khi điểm hoặc mức rủi ro thay đổi đáng kể" },
    { key: "report", title: "Cập nhật báo cáo", note: "Khi báo cáo được xử lý hoặc cần thêm thông tin" },
    { key: "push", title: "Thông báo đẩy", note: "Cho phép thông báo tới thiết bị đã đăng ký và cấp quyền" },
    { key: "inApp", title: "Thông báo trong ứng dụng", note: "Hiển thị trong bảng thông báo của BaoTin" }
  ];

  const updatePreference = async (key: keyof WatchlistPreferences, value: boolean) => {
    if (!onUpdatePreference) {
      setLocalPreferences((current) => ({ ...current, [key]: value }));
      return;
    }
    setSavingPreference(key);
    setActionError(null);
    try {
      await onUpdatePreference(key, value);
    } catch (caught) {
      setActionError(apiErrorMessage(caught, "Chưa thể lưu tùy chọn thông báo."));
    } finally {
      setSavingPreference(null);
    }
  };

  const toggleAdd = () => {
    if (adding) return;
    if (!onAdd) { onOpenSearch(); return; }
    setAddOpen((value) => !value);
    setAddError(null);
  };

  const add = async () => {
    if (!onAdd) return;
    const normalized = normalizeUrlInput(url);
    if (!normalized) { setAddError("Nhập URL http/https hợp lệ, ví dụ https://example.com."); return; }
    setAdding(true);
    setAddError(null);
    setActionError(null);
    try {
      await onAdd(normalized);
      setUrl("");
      setAddOpen(false);
    } catch (caught) {
      setAddError(apiErrorMessage(caught, "Chưa thể thêm URL vào danh sách theo dõi."));
    } finally {
      setAdding(false);
    }
  };

  const remove = async (id: string) => {
    setRemoving(id);
    setActionError(null);
    try {
      await onRemove(id);
      setConfirmRemove(null);
    } catch (caught) {
      setActionError(apiErrorMessage(caught, "Chưa thể bỏ theo dõi. Vui lòng thử lại."));
    } finally {
      setRemoving(null);
    }
  };

  return (
    <View className="min-w-0 w-full">
      <View className="md:flex-row md:items-end md:justify-between"><View className="max-w-[720px]"><Text accessibilityRole="header" className="font-display text-[28px] leading-9 text-md-on-surface dark:text-dark-md-on-surface md:text-[32px] md:leading-10">Theo dõi</Text></View><View className="mt-5 self-start md:mt-0"><Button appearance="material" label="Thêm theo dõi" icon={faPlus} disabled={adding} aria-expanded={addOpen} accessibilityState={{ expanded: addOpen }} onPress={toggleAdd} /></View></View>
      <Text className="mt-2 max-w-[640px] font-sans text-base leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Quản lý đường dẫn đang theo dõi và tùy chọn nhận thông báo.</Text>
      {demo ? <View className="mt-5"><DemoDataNotice description="Danh sách minh họa và thay đổi chỉ lưu trong phiên này." /></View> : null}

      {addOpen ? (
        <View className="mt-6 gap-3 rounded-2xl bg-md-surface-low p-4 dark:bg-dark-md-surface-low">
          <Input
            appearance="material"
            label="URL cần theo dõi"
            accessibilityLabel="URL cần theo dõi"
            value={url}
            onChangeText={(value) => { setUrl(value); setAddError(null); }}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="https://example.com"
            error={addError ?? undefined}
          />
          <View className="mt-3 flex-row gap-3">
            <Button appearance="material" label="Lưu theo dõi" icon={faFloppyDisk} loading={adding} onPress={add} />
            <Button appearance="material" label="Hủy" variant="ghost" disabled={adding} onPress={() => { setAddOpen(false); setUrl(""); setAddError(null); }} />
          </View>
        </View>
      ) : null}

      {actionError ? <View className="mt-4"><ErrorPanel description={actionError} /></View> : null}

      <View className="mt-6">
        {loading ? <LoadingPanel label="Đang tải danh sách theo dõi" /> : error ? <ErrorPanel description={error} onRetry={onRetry} /> : (
          <>
            {items.map((item, index) => {
              const delta = item.score !== null && item.previousScore !== null ? item.score - item.previousScore : null;
              return (
                <View key={item.id} className={`py-5 ${index < items.length - 1 ? "border-b border-md-outline-variant dark:border-dark-md-outline-variant" : ""}`}><View className="min-w-0 flex-col-reverse md:flex-row md:items-center md:gap-4">
                  <View className="min-w-0 flex-1"><Text className="font-sans text-xs text-muted dark:text-dark-muted">{scanTypeLabels[item.type]} / {item.updated}</Text><Text selectable className="mt-1 font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface">{item.target}</Text><Text className="mt-1 font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">{item.label}</Text></View>
                  <View className="mb-3 flex-row flex-wrap items-center gap-y-3 md:mb-0">
                    <View className="items-end">
                      <Text className="font-medium text-sm text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Điểm rủi ro</Text>
                      {item.previousScore !== null ? <Text className="font-sans text-xs text-muted dark:text-dark-muted">{item.previousScore} trước đây</Text> : null}
                      <Text className="mt-1 font-medium text-xl text-ink dark:text-dark-ink">
                        {item.score ?? "—"}
                        {delta !== null ? <Text className={`text-xs ${delta > 0 ? "text-danger dark:text-dark-danger" : delta < 0 ? "text-safe dark:text-dark-safe" : "text-muted dark:text-dark-muted"}`}> {delta > 0 ? `Tăng ${delta} điểm` : delta < 0 ? `Giảm ${Math.abs(delta)} điểm` : "Không đổi"}</Text> : null}
                      </Text>
                    </View>
                    <View className="ml-4">{item.level ? <RiskBadge level={item.level} /> : <Text className="font-sans text-xs text-muted dark:text-dark-muted">Chưa đánh giá</Text>}</View>
                    <View className="ml-auto md:ml-4"><Button appearance="material" accessibilityLabel={`Bỏ theo dõi ${item.target}`} icon={faTrashCan} variant="ghost" size="icon" loading={removing === item.id} disabled={removing !== null} onPress={() => setConfirmRemove(item.id)} /></View>
                  </View>
                  </View>
                  {confirmRemove === item.id ? <View accessibilityRole="alert" className="mt-3 gap-3 rounded-xl bg-md-surface-low p-4 dark:bg-dark-md-surface-low"><Text className="font-sans text-sm leading-6 text-md-on-surface dark:text-dark-md-on-surface">Bỏ theo dõi đối tượng này?</Text><Text selectable className="font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">{item.target}</Text><View className="flex-row flex-wrap gap-3"><Button appearance="material" label="Xác nhận bỏ theo dõi" variant="destructive" loading={removing === item.id} disabled={removing !== null} onPress={() => void remove(item.id)} /><Button appearance="material" label="Giữ theo dõi" variant="ghost" disabled={removing !== null} onPress={() => setConfirmRemove(null)} /></View></View> : null}
                </View>
              );
            })}
            {items.length === 0 ? <EmptyState compact align="start" icon={faEye} title="Chưa theo dõi đối tượng" description={demo ? "Thêm URL để xem trước danh sách theo dõi trong phiên này." : onAdd ? "Thêm URL để nhận cảnh báo khi điểm rủi ro thay đổi." : "Thêm đối tượng từ Tra cứu."} action={{ appearance: "material", label: onAdd ? "Thêm URL" : "Tìm đối tượng", icon: faPlus, disabled: adding, onPress: onAdd ? toggleAdd : onOpenSearch }} /> : null}
          </>
        )}
      </View>

      <View className="mt-10">
        <Text className="font-display text-[22px] leading-7 text-md-on-surface dark:text-dark-md-on-surface">Thông báo</Text>
        <View className="mt-5 border-y border-line dark:border-dark-line">
          {preferencesLoading ? <LoadingPanel label="Đang tải tùy chọn thông báo" /> : onUpdatePreference && !preferences ? <ErrorPanel description={preferenceError || "Chưa tải được tùy chọn thông báo. Vui lòng thử lại sau."} onRetry={onRetryPreferences} /> : preferenceRows.map((row, index) => (
            <View key={row.key} className={`flex-row items-center py-4 ${index < preferenceRows.length - 1 ? "border-b border-line dark:border-dark-line" : ""}`}>
              <View className="min-w-0 flex-1"><Text className="font-medium text-sm text-ink dark:text-dark-ink">{row.title}</Text><Text className="mt-1 font-sans text-xs leading-5 text-muted dark:text-dark-muted">{row.note}</Text></View>
              {savingPreference === row.key ? <Text accessibilityLiveRegion="polite" className="mx-3 font-sans text-sm text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Đang lưu…</Text> : null}
              <Pressable accessible={Platform.OS === "web"} accessibilityRole={Platform.OS === "web" ? "switch" : undefined} accessibilityLabel={Platform.OS === "web" ? row.title : undefined} accessibilityState={Platform.OS === "web" ? { checked: effectivePreferences[row.key], disabled: savingPreference !== null } : undefined} aria-checked={Platform.OS === "web" ? effectivePreferences[row.key] : undefined} disabled={Platform.OS !== "web" || savingPreference !== null} onPress={() => void updatePreference(row.key, !effectivePreferences[row.key])} className="h-12 w-12 items-center justify-center rounded-xl focus-visible:ring-2 focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary">
                <View pointerEvents={Platform.OS === "web" ? "none" : "auto"} aria-hidden={Platform.OS === "web" ? true : undefined}><Switch accessible={Platform.OS !== "web"} tabIndex={Platform.OS === "web" ? -1 : undefined} hitSlop={12} accessibilityLabel={Platform.OS === "web" ? undefined : row.title} value={effectivePreferences[row.key]} disabled={savingPreference !== null} onValueChange={(value) => void updatePreference(row.key, value)} trackColor={{ false: colors.mdOutlineVariant, true: colors.mdPrimaryContainer }} thumbColor={effectivePreferences[row.key] ? colors.mdPrimary : colors.mdOutline} /></View>
              </Pressable>
            </View>
          ))}
        </View>
        {preferenceError && preferences ? <View className="mt-4"><ErrorPanel description={preferenceError} onRetry={onRetryPreferences} /></View> : null}
        <Button appearance="material" className="mt-4 self-start" accessibilityLabel="Tìm đối tượng để theo dõi" label="Tìm đối tượng khác" variant="ghost" onPress={onOpenSearch} />
      </View>
    </View>
  );
}
