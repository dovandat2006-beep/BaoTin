import { View } from "react-native";

export function Skeleton({ className = "h-4 w-full" }: { className?: string }) {
  return <View accessibilityLabel="Đang tải" className={`rounded-[8px] bg-line opacity-60 dark:bg-dark-line ${className}`} />;
}
