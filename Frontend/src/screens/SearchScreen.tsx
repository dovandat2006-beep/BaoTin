import { faArrowRight } from "@fortawesome/free-solid-svg-icons/faArrowRight";
import { faEye } from "@fortawesome/free-solid-svg-icons/faEye";
import { faMagnifyingGlass } from "@fortawesome/free-solid-svg-icons/faMagnifyingGlass";
import { faRotate } from "@fortawesome/free-solid-svg-icons/faRotate";
import { useMemo, useState } from "react";
import { Pressable, ScrollView, Text, View } from "react-native";
import { DemoDataNotice } from "../components/DemoDataNotice";
import { RiskBadge } from "../components/RiskBadge";
import { Button, EmptyState, FilterBar, Input } from "../components/ui";
import { filterMockSearch } from "../api/mock/search";
import { scanTypeLabels, type RiskLevel, type ScanType } from "../lib/risk";

export function SearchScreen({ demo = false, onScan, onWatch }: { demo?: boolean; onScan: () => void; onWatch: () => void }) {
  const [query, setQuery] = useState("");
  const [type, setType] = useState<"ALL" | ScanType>("ALL");
  const [risk, setRisk] = useState<"ALL" | RiskLevel>("ALL");
  const results = useMemo(() => filterMockSearch({ query, type, risk }), [query, risk, type]);

  return (
    <View className="min-w-0 w-full">
      <Text className="font-display text-[34px] leading-[41px] text-ink dark:text-dark-ink md:text-[42px] md:leading-[50px]">Tra cứu</Text>
      {demo ? <View className="mt-5"><DemoDataNotice description="Kết quả minh họa, chưa phải dữ liệu tra cứu thực." /></View> : null}

      <View className="mt-8 gap-4 border-y border-line py-4 dark:border-dark-line">
        <Input label="Từ khóa tra cứu" accessibilityLabel="Từ khóa tra cứu" value={query} onChangeText={setQuery} placeholder="Tên miền, số điện thoại, email..." autoCapitalize="none" trailing={query ? <Button accessibilityLabel="Xóa từ khóa" icon={faRotate} variant="ghost" size="icon" onPress={() => setQuery("")} /> : undefined} />
        <FilterBar label="Loại đối tượng" options={(["ALL", "URL", "PHONE", "BANK_ACCOUNT"] as const).map((item) => ({ value: item, label: item === "ALL" ? "Tất cả" : item === "BANK_ACCOUNT" ? "Ngân hàng" : item === "PHONE" ? "Điện thoại" : "URL" }))} value={type} onChange={setType} />
        <FilterBar label="Mức rủi ro" options={(["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"] as const).map((item) => ({ value: item, label: item === "ALL" ? "Mọi mức" : item === "CRITICAL" ? "Rất cao" : item === "MEDIUM" ? "Trung bình" : item === "HIGH" ? "Cao" : "Thấp" }))} value={risk} onChange={setRisk} />
      </View>

      <View className="mt-6 border-y border-line dark:border-dark-line">
        {results.map((item, index) => (
          <View key={item.id} className={`py-4 md:flex-row md:items-center ${index < results.length - 1 ? "border-b border-line dark:border-dark-line" : ""}`}>
            <View className="min-w-0 flex-1"><View className="flex-row items-center"><Text className="font-medium text-xs text-accent dark:text-dark-accent">{scanTypeLabels[item.type]}</Text><Text className="ml-2 font-sans text-xs text-muted dark:text-dark-muted">{item.updated}</Text></View><Text className="mt-1 font-medium text-base text-ink dark:text-dark-ink" numberOfLines={1}>{item.display}</Text><Text className="mt-1 font-sans text-xs text-muted dark:text-dark-muted">{item.reportCount} báo cáo</Text></View>
            <View className="mt-4 flex-row items-center md:mt-0"><Text className="mr-3 font-medium text-xl text-ink dark:text-dark-ink">{item.score}</Text><RiskBadge level={item.level} /><View className="ml-auto flex-row gap-1 md:ml-4"><Button accessibilityLabel="Theo dõi đối tượng" icon={faEye} variant="ghost" size="icon" onPress={onWatch} /><Button accessibilityLabel="Kiểm tra lại" icon={faArrowRight} variant="secondary" size="icon" onPress={onScan} /></View></View>
          </View>
        ))}
        {results.length === 0 ? <EmptyState icon={faMagnifyingGlass} title="Không có kết quả" description="Thử từ khóa khác." action={{ label: "Kiểm tra đối tượng", trailingIcon: faArrowRight, onPress: onScan }} /> : null}
      </View>
    </View>
  );
}
