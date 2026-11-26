/* Hallmark · pre-emit critique: P4 H4 E4 S5 R5 V4
 * component: adaptive navigation · BaoTin Material 3 · calm-practical
 * Existing section callbacks and ADMIN visibility preserved. No layout animation.
 */
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { faAnglesLeft } from "@fortawesome/free-solid-svg-icons/faAnglesLeft";
import { faAnglesRight } from "@fortawesome/free-solid-svg-icons/faAnglesRight";
import { faClockRotateLeft } from "@fortawesome/free-solid-svg-icons/faClockRotateLeft";
import { faEye } from "@fortawesome/free-solid-svg-icons/faEye";
import { faFileShield } from "@fortawesome/free-solid-svg-icons/faFileShield";
import { faGaugeHigh } from "@fortawesome/free-solid-svg-icons/faGaugeHigh";
import { faLock } from "@fortawesome/free-solid-svg-icons/faLock";
import { faMagnifyingGlass } from "@fortawesome/free-solid-svg-icons/faMagnifyingGlass";
import { faShield } from "@fortawesome/free-solid-svg-icons/faShield";
import { useEffect, useRef, useState, type ComponentRef } from "react";
import { Keyboard, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View, useWindowDimensions } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useAppTheme } from "../theme/AppTheme";
import { faEllipsis } from "@fortawesome/free-solid-svg-icons/faEllipsis";
import { faXmark } from "@fortawesome/free-solid-svg-icons/faXmark";
import { Button } from "./ui";
import { BrandWordmark } from "./BrandWordmark";
import { compactNavigation, compactNavigationLayout } from "../theme/navigation";

export type AppSection = "scan" | "search" | "history" | "reports" | "watchlist" | "admin" | "auth";
type Props = { onCollapsedChange?: (collapsed: boolean) => void; active: AppSection; onChange: (section: AppSection) => void; compact?: boolean; authenticated?: boolean; adminAllowed?: boolean };

const items = [
  { id: "scan" as const, label: "Kiểm tra", icon: faShield },
  { id: "search" as const, label: "Tra cứu", icon: faMagnifyingGlass },
  { id: "history" as const, label: "Lịch sử", icon: faClockRotateLeft, protected: true },
  { id: "reports" as const, label: "Báo cáo", icon: faFileShield, protected: true },
  { id: "watchlist" as const, label: "Theo dõi", icon: faEye, protected: true },
  { id: "admin" as const, label: "Quản trị", icon: faGaugeHigh }
];

export function AppNav({ onCollapsedChange, active, onChange, compact = false, authenticated = false, adminAllowed = false }: Props) {
  const { width, fontScale } = useWindowDimensions();
  const [manualCollapsed, setManualCollapsed] = useState<boolean | null>(null);
  const collapsed = manualCollapsed ?? width < 1200;
  const [moreOpen, setMoreOpen] = useState(false);
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const moreTrigger = useRef<ComponentRef<typeof Pressable>>(null);
  const { colors } = useAppTheme();
  const insets = useSafeAreaInsets();
  const compactLayout = compactNavigationLayout(insets.bottom, fontScale);
  const visibleItems = items.filter(item => item.id !== "admin" || (authenticated && adminAllowed));
  const primaryItems = visibleItems.filter(item => ["scan", "search", "history", "reports"].includes(item.id));
  const secondaryItems = visibleItems.filter(item => !["scan", "search", "history", "reports"].includes(item.id));
  const moreSelected = secondaryItems.some(item => item.id === active);

  useEffect(() => { onCollapsedChange?.(collapsed); }, [collapsed, onCollapsedChange]);
  useEffect(() => { setMoreOpen(false); }, [active, compact, authenticated, adminAllowed]);
  useEffect(() => {
    if (Platform.OS === "web") return;
    const show = Keyboard.addListener("keyboardDidShow", () => setKeyboardOpen(true));
    const hide = Keyboard.addListener("keyboardDidHide", () => setKeyboardOpen(false));
    return () => { show.remove(); hide.remove(); };
  }, []);

  const closeMore = () => {
    setMoreOpen(false);
    if (Platform.OS === "web") requestAnimationFrame(() => (moreTrigger.current as unknown as { focus?: () => void })?.focus?.());
  };
  const select = (next: AppSection) => { setMoreOpen(false); onChange(next); };
  const focus = "focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary";
  const selectedSurface = "bg-md-secondary-container dark:bg-dark-md-secondary-container";
  const textColor = (selected: boolean) => selected ? "text-md-on-secondary-container dark:text-dark-md-on-secondary-container" : "text-md-on-surface-variant dark:text-dark-md-on-surface-variant";
  const iconColor = (selected: boolean) => selected ? colors.mdOnSecondaryContainer : colors.mdOnSurfaceVariant;

  if (compact) {
    if (keyboardOpen) return null;
    return <>
      <View testID="bottom-navigation" accessibilityRole="tablist" accessibilityLabel="Điều hướng chính" style={[styles.compactBar, { height: compactLayout.height, paddingBottom: compactLayout.bottomPadding }]} className="bg-md-surface-low dark:bg-dark-md-surface-low">
        {primaryItems.map(item => {
          const selected = active === item.id;
          const locked = Boolean(item.protected && !authenticated);
          return <Pressable key={item.id} testID={`nav-${item.id}`} accessibilityRole="tab" accessibilityLabel={item.label} aria-current={selected ? "page" : undefined} accessibilityState={{ selected }} accessibilityHint={locked ? "Đăng nhập để mở mục này" : undefined} onPress={() => select(item.id)} style={[styles.compactItem, { height: compactLayout.itemHeight }]} className={`active:opacity-75 ${focus}`}>
            <View style={styles.compactIndicator} className={selected ? selectedSurface : ""}><FontAwesomeIcon icon={item.icon} size={compactNavigation.iconSize} color={iconColor(selected)} /></View>
            <Text numberOfLines={compactLayout.labelLines} style={styles.compactLabel} className={`font-medium ${textColor(selected)}`}>{item.label}</Text>
          </Pressable>;
        })}
        <Pressable ref={moreTrigger} accessibilityRole="button" accessibilityLabel="Thêm" aria-expanded={moreOpen} aria-current={moreSelected ? "page" : undefined} accessibilityState={{ expanded: moreOpen, selected: moreSelected }} onPress={() => setMoreOpen(true)} style={[styles.compactItem, { height: compactLayout.itemHeight }]} className={`active:opacity-75 ${focus}`}>
          <View style={styles.compactIndicator} className={moreSelected ? selectedSurface : ""}><FontAwesomeIcon icon={faEllipsis} size={compactNavigation.iconSize} color={iconColor(moreSelected)} /></View>
          <Text numberOfLines={compactLayout.labelLines} style={styles.compactLabel} className={`font-medium ${textColor(moreSelected)}`}>Thêm</Text>
        </Pressable>
      </View>
      <Modal visible={moreOpen} transparent animationType="none" onRequestClose={closeMore}>
        <View className="flex-1 justify-end bg-ink/40 dark:bg-black/60">
          <Pressable accessible={false} onPress={closeMore} className="absolute inset-0" />
          <View testID="navigation-more" accessibilityViewIsModal style={{ paddingBottom: Math.max(insets.bottom, 16) }} className="max-h-[85%] rounded-t-[28px] bg-md-surface-low px-4 pt-4 dark:bg-dark-md-surface-low">
            <View className="mb-3 flex-row items-center justify-between"><Text accessibilityRole="header" className="font-display text-xl text-md-on-surface dark:text-dark-md-on-surface">Thêm</Text><Button appearance="material" accessibilityLabel="Đóng điều hướng bổ sung" variant="ghost" size="icon" style={styles.closeButton} icon={faXmark} onPress={closeMore} /></View>
            <ScrollView style={styles.moreList} keyboardShouldPersistTaps="handled">
              {secondaryItems.map(item => <Pressable key={item.id} accessibilityRole="button" accessibilityLabel={item.label} accessibilityState={{ selected: active === item.id }} accessibilityHint={item.protected && !authenticated ? "Đăng nhập để mở mục này" : undefined} onPress={() => select(item.id)} style={styles.moreItem} className={`active:opacity-75 ${focus} ${active === item.id ? selectedSurface : "hover:bg-md-primary-container dark:hover:bg-dark-md-primary-container"}`}><FontAwesomeIcon icon={item.icon} size={compactNavigation.iconSize} color={iconColor(active === item.id)} /><Text style={styles.moreLabel} className={`font-medium ${textColor(active === item.id)}`}>{item.label}</Text></Pressable>)}
            </ScrollView>
          </View>
        </View>
      </Modal>
    </>;
  }

  return <View testID="sidebar" style={{ width: collapsed ? 80 : 248 }} className="bg-md-surface-low py-4 dark:bg-dark-md-surface-low">
    <View testID="sidebar-header" className={`mb-5 min-h-12 flex-row items-center ${collapsed ? "justify-center" : "justify-between px-5"}`}>
      {!collapsed ? <BrandWordmark /> : null}
      <Button appearance="material" accessibilityLabel={collapsed ? "Mở rộng thanh bên" : "Thu gọn thanh bên"} accessibilityState={{ expanded: !collapsed }} icon={collapsed ? faAnglesRight : faAnglesLeft} variant="ghost" size="icon" onPress={() => setManualCollapsed(!collapsed)} />
    </View>
    <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: 8, paddingBottom: 16 }}>
      {visibleItems.map(item => {
        const selected = active === item.id;
        const locked = Boolean(item.protected && !authenticated);
        return <Pressable key={item.id} testID={`sidebar-${item.id}`} accessibilityRole="button" accessibilityLabel={item.label} aria-current={selected ? "page" : undefined} accessibilityState={{ selected }} accessibilityHint={locked ? "Đăng nhập để mở mục này" : undefined} onPress={() => select(item.id)} className={`mb-1 min-h-14 rounded-xl active:opacity-75 ${focus} ${collapsed ? "items-center justify-center py-2" : "flex-row items-center gap-3 px-4"} ${selected && !collapsed ? selectedSurface : "hover:bg-md-primary-container dark:hover:bg-dark-md-primary-container"}`}>
          <View className={`${collapsed ? "h-8 w-14 items-center justify-center rounded-full" : "w-6 items-center"} ${selected && collapsed ? selectedSurface : ""}`}><FontAwesomeIcon icon={item.icon} size={20} color={iconColor(selected)} /></View>
          <Text numberOfLines={1} className={`font-medium ${collapsed ? "mt-1 text-[11px] leading-4" : "min-w-0 flex-1 text-sm"} ${textColor(selected)}`}>{item.label}</Text>
          {locked && !collapsed ? <View testID={`nav-lock-${item.id}`}><FontAwesomeIcon icon={faLock} size={12} color={colors.mdOnSurfaceVariant} /></View> : null}
        </Pressable>;
      })}
    </ScrollView>
  </View>;
}

const styles = StyleSheet.create({
  compactBar: { position: "absolute", left: 0, right: 0, bottom: 0, zIndex: 20, flexDirection: "row", alignItems: "center", paddingHorizontal: compactNavigation.horizontalPadding, paddingTop: compactNavigation.verticalPadding },
  compactItem: { flexGrow: 1, flexShrink: 1, flexBasis: 0, minWidth: 0, alignItems: "center", justifyContent: "center", borderRadius: 12 },
  compactIndicator: { width: compactNavigation.indicatorWidth, height: compactNavigation.indicatorHeight, alignItems: "center", justifyContent: "center", borderRadius: 16 },
  compactLabel: { marginTop: compactNavigation.labelGap, fontSize: compactNavigation.labelSize, lineHeight: compactNavigation.labelLineHeight, textAlign: "center" },
  moreList: { flexGrow: 0 },
  moreItem: { minHeight: 56, flexDirection: "row", alignItems: "center", gap: 16, paddingHorizontal: 16, paddingVertical: 8, marginBottom: 8, borderRadius: 12 },
  moreLabel: { flexShrink: 1, fontSize: 16, lineHeight: 24 },
  closeButton: { width: 48, height: 48 }
});
