import { faXmark } from "@fortawesome/free-solid-svg-icons/faXmark";
import type { ReactNode } from "react";
import { KeyboardAvoidingView, Modal, Platform, Pressable, Text, View, useWindowDimensions } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useReducedMotion } from "../Motion";
import { Button } from "./Button";
import { nativeCompactSheetHeight } from "./sheetLayout";

export function Sheet({ visible, title, description, closeLabel, onClose, children, footer, contentClassName = "", appearance = "default", dismissDisabled = false }: { visible: boolean; title: string; description?: string; closeLabel?: string; onClose: () => void; children: ReactNode; footer?: ReactNode; contentClassName?: string; appearance?: "default" | "material"; dismissDisabled?: boolean }) {
  const reduced = useReducedMotion();
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const compact = width < 640;
  return (
    <Modal accessibilityLabel={appearance === "material" ? title : undefined} visible={visible} transparent animationType={appearance === "material" && reduced ? "none" : compact ? "slide" : "fade"} onRequestClose={onClose} statusBarTranslucent>
      <KeyboardAvoidingView behavior={appearance === "material" && Platform.OS === "ios" ? "padding" : undefined} style={{ flex: 1 }}>
      <View accessibilityViewIsModal className={`flex-1 bg-ink/40 dark:bg-black/60 ${compact ? "justify-end" : "items-end"}`}>
        <Pressable accessible={false} disabled={dismissDisabled} onPress={onClose} className="absolute inset-0" />
        <View style={compact && Platform.OS !== "web" ? { height: nativeCompactSheetHeight(height, insets.top) } : undefined} className={`max-h-[92%] ${appearance === "material" ? "bg-md-surface-low dark:bg-dark-md-surface-low" : "bg-panel dark:bg-dark-panel"} ${compact ? appearance === "material" ? "w-full rounded-t-[28px]" : "w-full rounded-t-[18px]" : "h-full w-[440px] border-l border-line dark:border-dark-line"}`}>
          <View className="flex-row items-start border-b border-line px-5 py-4 dark:border-dark-line"><View className="min-w-0 flex-1"><Text accessibilityRole={appearance === "material" ? "header" : undefined} aria-level={appearance === "material" ? 1 : undefined} className="font-display text-xl text-ink dark:text-dark-ink">{title}</Text>{description ? <Text className="mt-1 font-sans text-xs leading-5 text-muted dark:text-dark-muted">{description}</Text> : null}</View><Button disabled={dismissDisabled} appearance={appearance} accessibilityLabel={closeLabel ?? `Đóng ${title}`} icon={faXmark} variant="ghost" size="icon" onPress={onClose} /></View>
          <View className={`min-h-0 flex-1 ${contentClassName}`}>{children}</View>
          {footer ? <View style={{ paddingBottom: Math.max(insets.bottom, 16) }} className="border-t border-line px-5 pt-4 dark:border-dark-line">{footer}</View> : null}
        </View>
      </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}
