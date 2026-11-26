import { Text, View, type TextProps, type ViewProps } from "react-native";

const variants = {
  plain: "bg-transparent",
  surface: "rounded-[14px] bg-panel dark:bg-dark-panel",
  outlined: "rounded-[14px] border border-line bg-panel dark:border-dark-line dark:bg-dark-panel",
  muted: "rounded-[14px] bg-rail dark:bg-dark-rail"
} as const;

export function Card({ className = "", variant = "surface", ...props }: ViewProps & { className?: string; variant?: keyof typeof variants }) {
  return <View className={`${variants[variant]} ${className}`} {...props} />;
}
export function CardHeader({ className = "", ...props }: ViewProps & { className?: string }) { return <View className={`px-5 pt-5 ${className}`} {...props} />; }
export function CardContent({ className = "", ...props }: ViewProps & { className?: string }) { return <View className={`px-5 py-5 ${className}`} {...props} />; }
export function CardFooter({ className = "", ...props }: ViewProps & { className?: string }) { return <View className={`border-t border-line px-5 py-4 dark:border-dark-line ${className}`} {...props} />; }
export function CardTitle({ className = "", ...props }: TextProps & { className?: string }) { return <Text className={`font-display text-xl text-ink dark:text-dark-ink ${className}`} {...props} />; }
export function CardDescription({ className = "", ...props }: TextProps & { className?: string }) { return <Text className={`mt-1 font-sans text-sm leading-6 text-muted dark:text-dark-muted ${className}`} {...props} />; }
