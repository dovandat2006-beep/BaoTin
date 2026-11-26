import { useRef, useState, type ComponentRef } from "react";
import { Platform, Pressable, ScrollView, Text, View } from "react-native";

export type FilterOption<T extends string> = { value: T; label: string };

export function FilterBar<T extends string>({ label, options, value, onChange, scroll = true, appearance = "default" }: { label?: string; options: readonly FilterOption<T>[]; value: T; onChange: (value: T) => void; scroll?: boolean; appearance?: "default" | "material" }) {
  const refs = useRef<Array<ComponentRef<typeof Pressable> | null>>([]);
  const [contentWidth, setContentWidth] = useState(0);
  const [availableWidth, setAvailableWidth] = useState(0);
  const material = appearance === "material";
  const content = options.map((option, index) => {
    const selected = option.value === value;
    return (
      <Pressable
        key={option.value}
        ref={node => { refs.current[index] = node; }}
        tabIndex={material ? (selected ? 0 : -1) : undefined}
        {...(material && Platform.OS === "web" ? { onKeyDown: (raw: unknown) => {
          const event = raw as { key?: string; nativeEvent?: { key?: string }; preventDefault: () => void };
          const key = event.key ?? event.nativeEvent?.key ?? "";
          if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(key)) return;
          event.preventDefault();
          const next = key === "Home" ? 0 : key === "End" ? options.length - 1 : (index + (key === "ArrowRight" ? 1 : -1) + options.length) % options.length;
          onChange(options[next]!.value);
          (refs.current[next] as unknown as { focus?: () => void })?.focus?.();
        } } : {})}
        accessibilityRole="tab"
        accessibilityState={{ selected }}
        aria-selected={selected}
        onPress={() => onChange(option.value)}
        className={appearance === "material" ? `min-h-12 min-w-12 justify-center rounded-full border px-4 active:opacity-75 focus-visible:ring-2 focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary ${selected ? "border-md-primary bg-md-secondary-container dark:border-dark-md-primary dark:bg-dark-md-secondary-container" : "border-md-outline-variant hover:bg-md-surface-low dark:border-dark-md-outline-variant dark:hover:bg-dark-md-surface-low"}` : `min-h-12 justify-center border-b px-3 active:opacity-75 focus-visible:ring-2 focus-visible:ring-accent dark:focus-visible:ring-dark-accent ${selected ? "border-accent dark:border-dark-accent" : "border-transparent hover:border-line dark:hover:border-dark-line"}`}
      >
        <Text numberOfLines={1} className={appearance === "material" ? `font-medium text-sm ${selected ? "text-md-on-secondary-container dark:text-dark-md-on-secondary-container" : "text-md-on-surface-variant dark:text-dark-md-on-surface-variant"}` : `font-medium text-xs ${selected ? "text-accent-strong dark:text-dark-accent-strong" : "text-muted dark:text-dark-muted"}`}>{option.label}</Text>
      </Pressable>
    );
  });

  return (
    <View>
      {label ? <Text className={appearance === "material" ? "mb-2 font-medium text-sm text-md-on-surface dark:text-dark-md-on-surface" : "mb-1 font-medium text-xs text-ink dark:text-dark-ink"}>{label}</Text> : null}
      {scroll ? <ScrollView horizontal accessibilityRole="tablist" accessibilityLabel={label} onLayout={event => setAvailableWidth(event.nativeEvent.layout.width)} onContentSizeChange={width => setContentWidth(width)} showsHorizontalScrollIndicator={material} contentContainerStyle={{ gap: appearance === "material" ? 8 : 4, paddingRight: 12, paddingVertical: appearance === "material" ? 4 : 0 }}>{content}</ScrollView> : <View accessibilityRole="tablist" accessibilityLabel={label} className="flex-row flex-wrap gap-1">{content}</View>}
      {material && scroll && contentWidth > availableWidth + 4 ? <Text className="mt-1 font-sans text-xs leading-4 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Cuộn ngang để xem thêm bộ lọc.</Text> : null}
    </View>
  );
}
