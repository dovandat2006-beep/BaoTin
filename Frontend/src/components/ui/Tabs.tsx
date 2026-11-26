import { useRef, type ComponentRef } from "react";
import { Platform, Pressable, Text, View } from "react-native";

export type TabItem<T extends string> = { value: T; label: string };

export function Tabs<T extends string>({ items, value, onChange, variant = "default", appearance = "default", label, panelId }: { items: readonly TabItem<T>[]; value: T; onChange: (value: T) => void; variant?: "default" | "line"; appearance?: "default" | "material"; label?: string; panelId?: string }) {
  const refs = useRef<Array<ComponentRef<typeof Pressable> | null>>([]);
  const material = appearance === "material";
  return (
    <View accessibilityRole="tablist" accessibilityLabel={label} className={material ? "min-w-0 flex-row border-b border-md-outline-variant dark:border-dark-md-outline-variant" : variant === "default" ? "flex-row rounded-[10px] bg-rail p-1 dark:bg-dark-rail" : "flex-row border-b border-line dark:border-dark-line"}>
      {items.map((item, index) => {
        const selected = item.value === value;
        return (
          <Pressable key={item.value} ref={node => { refs.current[index] = node; }} nativeID={panelId ? `${panelId}-${item.value}` : undefined} tabIndex={material ? selected ? 0 : -1 : undefined} aria-controls={panelId} aria-selected={selected} {...(material && Platform.OS === "web" ? { onKeyDown: (raw: unknown) => {
            const e = raw as { key?: string; nativeEvent?: { key?: string }; preventDefault: () => void }; const key = e.key ?? e.nativeEvent?.key ?? "";
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(key)) return; e.preventDefault();
            const next = key === "Home" ? 0 : key === "End" ? items.length - 1 : (index + (key === "ArrowRight" ? 1 : -1) + items.length) % items.length;
            onChange(items[next]!.value); (refs.current[next] as unknown as { focus?: () => void })?.focus?.();
          } } : {})} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => onChange(item.value)} className={material ? `min-h-12 min-w-0 flex-1 items-center justify-center border-b-2 px-2 active:opacity-75 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary ${selected ? "border-md-primary dark:border-dark-md-primary" : "border-transparent hover:bg-md-surface-low dark:hover:bg-dark-md-surface-low"}` : `min-h-11 min-w-0 flex-1 items-center justify-center px-2 hover:bg-accent-soft/60 active:opacity-75 focus-visible:ring-2 focus-visible:ring-accent dark:hover:bg-dark-accent-soft/60 dark:focus-visible:ring-dark-accent ${variant === "default" ? `rounded-[8px] ${selected ? "bg-panel dark:bg-dark-panel" : "bg-transparent"}` : `${selected ? "border-b-2 border-accent dark:border-dark-accent" : "border-b-2 border-transparent"}`}`}>
            <Text numberOfLines={1} className={material ? `font-medium text-sm ${selected ? "text-md-primary dark:text-dark-md-primary" : "text-md-on-surface-variant dark:text-dark-md-on-surface-variant"}` : `font-medium text-xs ${selected ? "text-ink dark:text-dark-ink" : "text-muted dark:text-dark-muted"}`}>{item.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}
