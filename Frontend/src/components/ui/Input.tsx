import { useId, useState, type ReactNode } from "react";
import { Platform, Text, TextInput, View, type TextInputProps } from "react-native";
import { useAppTheme } from "../../theme/AppTheme";

export type InputProps = TextInputProps & {
  label: string;
  description?: string;
  error?: string;
  success?: string;
  trailing?: ReactNode;
  multiline?: boolean;
  appearance?: "default" | "material";
};

export function Input({ label, description, error, success, trailing, onFocus, onBlur, multiline, appearance = "default", ...props }: InputProps) {
  const { colors } = useAppTheme();
  const [focused, setFocused] = useState(false);
  const helperId = useId();
  const material = appearance === "material";
  return (
    <View className="w-full">
      <Text className={`mb-2 font-medium ${material ? "text-sm text-md-on-surface dark:text-dark-md-on-surface" : "text-xs text-ink dark:text-dark-ink"}`}>{label}</Text>
      <View className={material
        ? `min-h-14 flex-row items-center rounded-xl border bg-md-surface-lowest dark:bg-dark-md-surface-lowest hover:bg-md-surface-low dark:hover:bg-dark-md-surface-low ${props.editable === false ? "opacity-50" : ""} ${error ? "border-md-error dark:border-dark-md-error" : success ? "border-md-risk-low dark:border-dark-md-risk-low" : focused ? "border-md-primary ring-2 ring-md-primary-container dark:border-dark-md-primary dark:ring-dark-md-primary-container" : "border-md-outline dark:border-dark-md-outline"}`
        : `min-h-12 flex-row items-center rounded-[10px] border bg-panel hover:border-accent dark:bg-dark-panel dark:hover:border-dark-accent ${props.editable === false ? "opacity-55" : ""} ${error ? "border-danger dark:border-dark-danger" : success ? "border-safe dark:border-dark-safe" : focused ? "border-accent ring-2 ring-accent-soft dark:border-dark-accent dark:ring-dark-accent-soft" : "border-control dark:border-dark-control"}`}>
        <TextInput
          accessibilityLabel={label}
          accessibilityState={{ disabled: Boolean(props.editable === false) }}
          aria-invalid={Boolean(error)}
          {...(Platform.OS === "web" && (description || error || success) ? { "aria-describedby": helperId } : {})}
          accessibilityHint={error ?? success ?? description}
          multiline={multiline}
          placeholderTextColor={material ? colors.mdOnSurfaceVariant : colors.placeholder}
          className={material ? `min-w-0 flex-1 px-4 font-sans text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface ${multiline ? "min-h-40 py-4" : "min-h-14 py-3"}` : `min-w-0 flex-1 px-3 font-sans text-sm text-ink dark:text-dark-ink ${multiline ? "min-h-28 py-3" : "h-12"}`}
          style={{ textAlignVertical: multiline ? "top" : "center" }}
          onFocus={(event) => { setFocused(true); onFocus?.(event); }}
          onBlur={(event) => { setFocused(false); onBlur?.(event); }}
          {...props}
        />
        {trailing}
      </View>
      <View className="min-h-5">{error || success || description ? <Text nativeID={helperId} accessibilityRole={error ? "alert" : undefined} className={`font-sans leading-5 ${material ? "mt-2 text-sm" : "mt-1.5 text-xs"} ${error ? "text-danger dark:text-dark-danger" : success ? "text-safe dark:text-dark-safe" : "text-muted dark:text-dark-muted"}`}>{error ?? success ?? description}</Text> : null}</View>
    </View>
  );
}
