import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import type { IconDefinition } from "@fortawesome/fontawesome-svg-core";
import { Text, View } from "react-native";
import { useAppTheme } from "../../theme/AppTheme";

const tones = {
  neutral: "bg-rail dark:bg-dark-rail",
  safe: "bg-safe-soft dark:bg-dark-safe-soft",
  warning: "bg-warning-soft dark:bg-dark-warning-soft",
  destructive: "bg-danger-soft dark:bg-dark-danger-soft"
} as const;

export function Alert({ icon, title, description, tone = "neutral" }: { icon: IconDefinition; title?: string; description: string; tone?: keyof typeof tones }) {
  const { colors } = useAppTheme();
  const color = tone === "safe" ? colors.safe : tone === "warning" ? colors.warning : tone === "destructive" ? colors.danger : colors.muted;
  return <View className={`flex-row items-start rounded-[10px] px-3 py-3 ${tones[tone]}`}><View className="mt-0.5"><FontAwesomeIcon icon={icon} size={14} color={color} /></View><View className="ml-3 min-w-0 flex-1">{title ? <Text className="font-medium text-xs text-ink dark:text-dark-ink">{title}</Text> : null}<Text className={`${title ? "mt-1" : ""} font-sans text-xs leading-5 text-muted dark:text-dark-muted`}>{description}</Text></View></View>;
}
