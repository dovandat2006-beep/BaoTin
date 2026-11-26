import type { RiskLevel } from "../lib/risk";
import { Badge } from "./ui";
import { Text, View } from "react-native";
import { useAppTheme } from "../theme/AppTheme";

const styles: Record<RiskLevel, { variant: "safe" | "warning" | "destructive" | "solid"; label: string }> = {
  LOW: { variant: "safe", label: "Thấp" },
  MEDIUM: { variant: "warning", label: "Trung bình" },
  HIGH: { variant: "destructive", label: "Cao" },
  CRITICAL: { variant: "solid", label: "Rất cao" }
};

export function RiskBadge({ level, descriptive = false }: { level: RiskLevel; descriptive?: boolean }) {
  const { colors } = useAppTheme();
  const item = styles[level];
  if (descriptive) {
    const foreground = level === "LOW" ? colors.mdRiskLow : level === "MEDIUM" ? colors.mdRiskMedium : level === "CRITICAL" ? colors.mdOnError : colors.mdOnErrorContainer;
    const backgroundColor = level === "LOW" ? colors.mdRiskLowContainer : level === "MEDIUM" ? colors.mdRiskMediumContainer : level === "CRITICAL" ? colors.mdError : colors.mdErrorContainer;
    return <View style={{ backgroundColor }} className="self-start rounded-full px-3 py-1"><Text style={{ color: foreground }} className="font-medium text-sm leading-5">Mức rủi ro: {item.label.toLocaleLowerCase("vi-VN")}</Text></View>;
  }
  return <View accessible accessibilityLabel={`Mức rủi ro: ${item.label.toLocaleLowerCase("vi-VN")}`}><Badge label={item.label} variant={item.variant} /></View>;
}
