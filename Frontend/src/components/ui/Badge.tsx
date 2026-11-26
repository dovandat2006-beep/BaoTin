import { Text, View } from "react-native";

const variants = {
  neutral: "bg-rail text-muted dark:bg-dark-rail dark:text-dark-muted",
  safe: "bg-safe-soft text-safe dark:bg-dark-safe-soft dark:text-dark-safe",
  warning: "bg-warning-soft text-warning dark:bg-dark-warning-soft dark:text-dark-warning",
  destructive: "bg-danger-soft text-danger dark:bg-dark-danger-soft dark:text-dark-danger",
  solid: "bg-danger text-white dark:bg-dark-danger dark:text-dark-canvas",
  outline: "border border-line bg-transparent text-ink dark:border-dark-line dark:text-dark-ink"
} as const;

export function Badge({ label, variant = "neutral" }: { label: string; variant?: keyof typeof variants }) {
  const classes = variants[variant].split(" ");
  const isText = (value: string) => value.startsWith("text-") || value.startsWith("dark:text-");
  const container = classes.filter((value) => !isText(value)).join(" ");
  const text = classes.filter(isText).join(" ");
  return <View className={`self-start rounded-full px-2.5 py-1 ${container}`}><Text className={`font-medium text-[11px] ${text}`}>{label}</Text></View>;
}
