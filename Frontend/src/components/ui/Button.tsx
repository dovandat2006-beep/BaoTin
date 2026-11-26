import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import type { IconDefinition } from "@fortawesome/fontawesome-svg-core";
import { ActivityIndicator, Pressable, Text, View, type PressableProps } from "react-native";
import { useAppTheme } from "../../theme/AppTheme";

type ButtonVariant = "default" | "secondary" | "outline" | "ghost" | "destructive" | "success";
type ButtonSize = "sm" | "default" | "icon";

const variants: Record<ButtonVariant, string> = {
  default: "bg-accent dark:bg-dark-accent",
  secondary: "bg-accent-soft dark:bg-dark-accent-soft",
  outline: "border border-control bg-panel dark:border-dark-control dark:bg-dark-panel",
  ghost: "bg-transparent",
  destructive: "bg-danger dark:bg-dark-danger",
  success: "bg-safe dark:bg-dark-safe"
};

const labels: Record<ButtonVariant, string> = {
  default: "text-white dark:text-dark-on-primary",
  secondary: "text-accent-strong dark:text-dark-accent-strong",
  outline: "text-ink dark:text-dark-ink",
  ghost: "text-ink dark:text-dark-ink",
  destructive: "text-white dark:text-dark-on-primary",
  success: "text-white dark:text-dark-on-primary"
};

const sizes: Record<ButtonSize, string> = {
  sm: "min-h-12 px-3",
  default: "min-h-12 px-4",
  icon: "h-12 w-12 px-0"
};

export type ButtonProps = Omit<PressableProps, "children"> & {
  label?: string;
  icon?: IconDefinition;
  trailingIcon?: IconDefinition;
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  /** Opt-in Material 3 roles; legacy consumers retain their current appearance. */
  appearance?: "default" | "material";
  className?: string;
};

export function Button({ label, icon, trailingIcon, variant = "default", size = "default", loading = false, disabled, appearance = "default", className = "", style, ...props }: ButtonProps) {
  const { colors } = useAppTheme();
  const blocked = Boolean(disabled || loading);
  const material = appearance === "material";
  const foreground = material
    ? variant === "default" ? colors.mdOnPrimary : variant === "secondary" ? colors.mdOnSecondaryContainer : variant === "destructive" ? colors.mdOnError : variant === "success" ? colors.mdRiskLow : colors.mdPrimary
    : variant === "default" || variant === "destructive" || variant === "success" ? colors.onPrimary : variant === "secondary" ? colors.accentStrong : colors.ink;
  const materialVariants: Record<ButtonVariant, string> = {
    default: "bg-md-primary dark:bg-dark-md-primary",
    secondary: "bg-md-secondary-container dark:bg-dark-md-secondary-container",
    outline: "border border-md-outline bg-transparent dark:border-dark-md-outline",
    ghost: "bg-transparent",
    destructive: "bg-md-error dark:bg-dark-md-error",
    success: "bg-md-risk-low-container dark:bg-dark-md-risk-low-container"
  };
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: blocked, busy: loading }}
      disabled={blocked}
      className={`flex-row items-center justify-center ${material ? "rounded-xl" : "rounded-[10px]"} ${sizes[size]} ${material ? materialVariants[variant] : variants[variant]} ${blocked ? "opacity-50" : "hover:opacity-90 active:opacity-80 focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 dark:focus-visible:ring-dark-accent dark:focus-visible:ring-offset-dark-canvas"} ${className}`}
      style={style}
      {...props}
    >
      {loading ? <ActivityIndicator size="small" color={foreground} /> : icon ? <FontAwesomeIcon icon={icon} size={14} color={foreground} /> : null}
      {label ? <Text numberOfLines={1} style={material ? { color: foreground } : undefined} className={`font-medium text-sm ${material ? "" : labels[variant]} ${loading || icon ? "ml-2" : ""}`}>{label}</Text> : null}
      {trailingIcon ? <View className="ml-2"><FontAwesomeIcon icon={trailingIcon} size={13} color={foreground} /></View> : null}
    </Pressable>
  );
}
