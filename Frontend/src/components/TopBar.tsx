/* Hallmark · pre-emit critique: P4 H4 E4 S5 R5 V4
 * component: app bar · BaoTin Material 3 · calm-practical
 * Existing account/notification callbacks and persisted theme retained.
 */
import { faBell } from "@fortawesome/free-solid-svg-icons/faBell";
import { faMoon } from "@fortawesome/free-solid-svg-icons/faMoon";
import { faSun } from "@fortawesome/free-solid-svg-icons/faSun";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { faUser } from "@fortawesome/free-solid-svg-icons/faUser";
import { faEllipsisVertical } from "@fortawesome/free-solid-svg-icons/faEllipsisVertical";
import { faXmark } from "@fortawesome/free-solid-svg-icons/faXmark";
import { useRef, useState, type ComponentRef } from "react";
import { Modal, Platform, Pressable, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useAppTheme } from "../theme/AppTheme";
import { Button } from "./ui";
import { AndroidDownloadButton } from "./AndroidDownloadButton";
import { BrandWordmark } from "./BrandWordmark";

export type TopBarProps = { authenticated?: boolean; showBrand?: boolean; horizontalPadding?: number; compact: boolean; unreadCount: number; accountLabel: string; onOpenNotifications: () => void; onOpenAccount: () => void };

export function TopBar({ showBrand, horizontalPadding, compact, unreadCount, accountLabel, onOpenNotifications, onOpenAccount }: TopBarProps) {
  const { isDark, toggleTheme, colors } = useAppTheme();
  const [optionsOpen, setOptionsOpen] = useState(false);
  const trigger = useRef<ComponentRef<typeof View>>(null);
  const insets = useSafeAreaInsets();
  const closeOptions = () => {
    setOptionsOpen(false);
    if (Platform.OS === "web") requestAnimationFrame(() => (trigger.current as unknown as { querySelector?: (s: string) => { focus: () => void } | null })?.querySelector?.("button")?.focus());
  };
  const count = Number.isFinite(unreadCount) ? Math.max(0, Math.trunc(unreadCount)) : 0;
  return <>
    <View testID="top-bar" style={horizontalPadding === undefined ? undefined : { paddingHorizontal: horizontalPadding }} className="min-h-16 min-w-0 flex-row items-center gap-1 bg-md-surface px-4 dark:bg-dark-md-surface md:px-8 lg:px-12">
      {(showBrand ?? compact) ? <View className="min-w-0 flex-1"><BrandWordmark /></View> : <View className="flex-1" />}
      <View className="relative">
        <Button appearance="material" accessibilityLabel={`Thông báo, ${count} chưa đọc`} icon={faBell} variant="ghost" size="icon" onPress={onOpenNotifications} />
        {count > 0 ? <View pointerEvents="none" accessible={false} testID="notification-count" className="absolute right-0 top-0 min-h-5 min-w-5 items-center justify-center rounded-full bg-md-error px-1 dark:bg-dark-md-error"><Text className="font-medium text-[11px] leading-4 text-md-on-error dark:text-dark-md-on-error">{count > 99 ? "99+" : count}</Text></View> : null}
      </View>
      <Pressable accessibilityRole="button" accessibilityLabel={accountLabel} onPress={onOpenAccount} className="min-h-14 w-16 items-center justify-center gap-1 rounded-xl hover:bg-md-secondary-container dark:hover:bg-dark-md-secondary-container active:opacity-75 focus-visible:ring-2 focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary">
        <FontAwesomeIcon icon={faUser} size={18} color={colors.mdPrimary} />
      </Pressable>
      <View ref={trigger}><Button appearance="material" accessibilityLabel="Tùy chọn ứng dụng" aria-expanded={optionsOpen} icon={faEllipsisVertical} variant="ghost" size="icon" onPress={() => setOptionsOpen(true)} /></View>
    </View>
    <AndroidDownloadButton compact={false} appearance="material" onOpen={() => setOptionsOpen(false)} renderTrigger={download => <Modal transparent visible={optionsOpen} animationType="none" onRequestClose={closeOptions}>
      <View className={`flex-1 bg-ink/40 dark:bg-black/60 ${compact ? "justify-end" : "items-end"}`} style={compact ? undefined : { paddingTop: insets.top + 64, paddingRight: 24 }}>
        <Pressable accessible={false} className="absolute inset-0" onPress={closeOptions} />
        <View testID="topbar-options" accessibilityViewIsModal style={{ paddingBottom: Math.max(insets.bottom, 20) }} className={`${compact ? "w-full rounded-t-[28px]" : "w-[320px] rounded-2xl"} gap-4 bg-md-surface-low p-5 dark:bg-dark-md-surface-low`}>
          <View className="flex-row items-center justify-between"><Text accessibilityRole="header" className="font-display text-xl text-md-on-surface dark:text-dark-md-on-surface">Tùy chọn</Text><Button appearance="material" accessibilityLabel="Đóng tùy chọn" icon={faXmark} variant="ghost" size="icon" onPress={closeOptions} /></View>
          <View className="gap-2"><Text className="font-medium text-sm text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Giao diện hiện tại: {isDark ? "Tối" : "Sáng"}</Text><Button appearance="material" accessibilityLabel={isDark ? "Chuyển sang giao diện sáng" : "Chuyển sang giao diện tối"} label={isDark ? "Dùng giao diện sáng" : "Dùng giao diện tối"} icon={isDark ? faSun : faMoon} variant="outline" onPress={toggleTheme} /></View>
          {download}
        </View>
      </View>
    </Modal>} />
  </>;
}
