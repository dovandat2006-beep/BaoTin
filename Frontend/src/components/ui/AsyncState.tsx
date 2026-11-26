import { faArrowRotateRight } from "@fortawesome/free-solid-svg-icons/faArrowRotateRight";
import { Text, View } from "react-native";
import { Button } from "./Button";
import { Skeleton } from "./Skeleton";

export function LoadingPanel({ label = "Đang tải dữ liệu" }: { label?: string }) {
  return <View accessibilityLabel={label} accessibilityRole="progressbar" className="gap-3 py-5"><Text className="font-medium text-sm text-ink dark:text-dark-ink">{label}</Text><Skeleton className="h-5 w-2/3" /><Skeleton className="h-3 w-full" /><Skeleton className="h-3 w-5/6" /></View>;
}

export function ErrorPanel({ title = "Chưa thể tải dữ liệu", description, onRetry }: { title?: string; description: string; onRetry?: () => void }) {
  return <View accessibilityRole="alert" className="border-y border-danger py-5 dark:border-dark-danger"><Text className="font-display text-lg text-danger dark:text-dark-danger">{title}</Text><Text className="mt-2 font-sans text-sm leading-6 text-muted dark:text-dark-muted">{description}</Text>{onRetry ? <View className="mt-4 self-start"><Button label="Thử lại" icon={faArrowRotateRight} variant="outline" size="sm" onPress={onRetry} /></View> : null}</View>;
}
