import { faEye } from "@fortawesome/free-solid-svg-icons/faEye";
import { faFlag } from "@fortawesome/free-solid-svg-icons/faFlag";
import { faCircleExclamation } from "@fortawesome/free-solid-svg-icons/faCircleExclamation";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { useAppTheme } from "../theme/AppTheme";
import { useState } from "react";
import { Text, View } from "react-native";
import { scanTypeLabels, type ScanResult } from "../lib/risk";
import { Button } from "./ui";
import { RiskBadge } from "./RiskBadge";

export function RiskResult({ result, onReport, onWatch }: { result: ScanResult; onReport: () => void; onWatch: () => void }) {
  const { colors } = useAppTheme();
  const critical = result.level === "CRITICAL" || result.level === "HIGH";
  const [details, setDetails] = useState(false);
  const snapshot = result.snapshot;
  const checkedAt = new Date(result.scannedAt);
  const checkedAtLabel = Number.isFinite(checkedAt.getTime()) ? checkedAt.toLocaleString("vi-VN") : null;

  return (
    <View className="min-w-0">
        <Text className="font-medium text-sm text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Kết quả kiểm tra</Text>
        <Text accessibilityRole="header" className={`mt-3 font-display text-2xl leading-8 ${critical ? "text-md-error dark:text-dark-md-error" : "text-md-on-surface dark:text-dark-md-on-surface"}`}>{result.title}</Text>
        <View className="mt-4"><RiskBadge level={result.level} descriptive /></View>
        <View className="mt-6 gap-2">
          <Text className="font-medium text-base text-md-on-surface dark:text-dark-md-on-surface">Bạn nên làm gì?</Text>
          <Text className="font-sans text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface">{result.summary}</Text>
        </View>
        {result.indicators.length ? <View className="mt-6 gap-3">
          <Text className="font-medium text-base text-md-on-surface dark:text-dark-md-on-surface">Dấu hiệu ghi nhận</Text>
          {result.indicators.map((indicator, index) => <View key={`${index}-${indicator}`} className="flex-row items-start gap-3"><View className="mt-2 h-1 w-1 rounded-full bg-md-on-surface-variant dark:bg-dark-md-on-surface-variant" /><Text className="min-w-0 flex-1 font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">{indicator}</Text></View>)}
        </View> : null}
        {snapshot && !snapshot.aiAvailable ? <View className="mt-5 flex-row items-start gap-3"><View className="mt-1"><FontAwesomeIcon icon={faCircleExclamation} size={16} color={colors.mdRiskMedium} /></View><View className="min-w-0 flex-1"><Text className="font-medium text-sm leading-5 text-md-risk-medium dark:text-dark-md-risk-medium">Phân tích AI chưa sẵn sàng</Text><Text className="mt-1 font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Kết quả hiện dựa trên các tín hiệu còn có thể kiểm tra. Mức rủi ro thấp chưa có nghĩa là nội dung an toàn.</Text></View></View> : null}
        {snapshot?.warnings.includes("EMBEDDED_URL_LIMIT") ? <Text className="mt-3 font-sans text-sm leading-6 text-warning dark:text-dark-warning">Tin nhắn có nhiều đường dẫn. Chỉ 5 đường dẫn đầu được phân tích.</Text> : null}
        <View className="mt-5 gap-1">
          <Text className="font-medium text-sm text-ink dark:text-dark-ink">Loại: {scanTypeLabels[result.type]}</Text>
          {checkedAtLabel ? <Text className="font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Kiểm tra lúc {checkedAtLabel}</Text> : null}
          {result.ephemeral ? <Text className="font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Kết quả chỉ áp dụng cho nội dung tin nhắn này.</Text> : null}
        </View>
        <View className="mt-5 flex-row flex-wrap gap-3">
          <Button appearance="material" className="min-w-[112px] flex-1" label="Tố giác" icon={faFlag} variant="outline" onPress={onReport} />
          {!result.ephemeral ? <Button appearance="material" variant="secondary" className="min-w-[112px] flex-1" label="Mở theo dõi" icon={faEye} onPress={onWatch} /> : null}
        </View>
        {snapshot ? <View className="mt-4">
          <Button appearance="material" accessibilityState={{ expanded: details }} label={details ? "Ẩn chi tiết phân tích" : "Xem chi tiết phân tích"} variant="ghost" onPress={() => setDetails((value) => !value)} />
          {details ? <View className="mt-3 gap-3 border-t border-line pt-4 dark:border-dark-line">
            <Text className="font-medium text-sm text-ink dark:text-dark-ink">Điểm rủi ro: {result.score.toLocaleString("vi-VN")} / 100</Text>
            {snapshot.type === "TEXT" ? snapshot.urls.map((url) => <View key={url.targetId} className="border-t border-line pt-3 dark:border-dark-line">
              <Text selectable className="min-w-0 font-medium text-sm leading-6 text-ink dark:text-dark-ink">{url.url}</Text>
              <Text className="mt-1 font-sans text-sm leading-6 text-muted dark:text-dark-muted">Điểm rủi ro: {url.risk.score} / 100</Text>
            </View>) : null}
          </View> : null}
        </View> : null}

    </View>
  );
}
