import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import type { IconDefinition } from "@fortawesome/fontawesome-svg-core";
import { Text, View } from "react-native";
import { useAppTheme } from "../../theme/AppTheme";
import { Button, type ButtonProps } from "./Button";

export function EmptyState({ icon, title, description, action, compact = false, align = "center" }: { icon: IconDefinition; title: string; description: string; action?: ButtonProps; compact?: boolean; align?: "center" | "start" }) {
  const { colors } = useAppTheme();
  const centered = align === "center";
  return <View className={`${centered ? "items-center" : "items-start"} ${compact ? "py-7" : "py-12"}`}><FontAwesomeIcon icon={icon} size={compact ? 18 : 22} color={colors.muted} /><Text className={`mt-4 font-display text-ink dark:text-dark-ink ${compact ? "text-lg" : "text-xl"} ${centered ? "text-center" : "text-left"}`}>{title}</Text><Text className={`mt-2 max-w-[420px] font-sans text-sm leading-6 text-muted dark:text-dark-muted ${centered ? "text-center" : "text-left"}`}>{description}</Text>{action ? <View className="mt-5"><Button {...action} /></View> : null}</View>;
}
