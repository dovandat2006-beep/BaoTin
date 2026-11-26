import { faChevronDown } from "@fortawesome/free-solid-svg-icons/faChevronDown";
import { faChevronUp } from "@fortawesome/free-solid-svg-icons/faChevronUp";
import { faClockRotateLeft } from "@fortawesome/free-solid-svg-icons/faClockRotateLeft";
import { faTrashCan } from "@fortawesome/free-solid-svg-icons/faTrashCan";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { useMemo, useState } from "react";
import { Pressable, Text, View } from "react-native";
import { RiskBadge } from "../components/RiskBadge";
import { Button, EmptyState, FilterBar } from "../components/ui";
import type { HistoryItem } from "../data/mock";
import { scanTypeLabels, type ScanType } from "../lib/risk";
import { useAppTheme } from "../theme/AppTheme";

export function HistoryScreen({ items, onDelete }: { items: HistoryItem[]; onDelete: (id: string) => void }) {
  const { colors } = useAppTheme();
  const [filter, setFilter] = useState<"ALL" | ScanType>("ALL");
  const [expanded, setExpanded] = useState<string | null>(items[0]?.id ?? null);
  const visible = useMemo(() => filter === "ALL" ? items : items.filter((item) => item.type === filter), [filter, items]);
  const filters: Array<"ALL" | ScanType> = ["ALL", "URL", "SMS", "PHONE", "BANK_ACCOUNT"];

  return (
    <View className="min-w-0 w-full">
      <Text className="font-display text-[34px] leading-[41px] text-ink dark:text-dark-ink md:text-[42px] md:leading-[50px]">Lịch sử kiểm tra</Text>
      
      <View className="mt-8 border-y border-line py-1 dark:border-dark-line"><FilterBar options={filters.map((item) => ({ value: item, label: item === "ALL" ? "Tất cả" : item === "BANK_ACCOUNT" ? "Ngân hàng" : scanTypeLabels[item] }))} value={filter} onChange={setFilter} /></View>

      <View className="mt-6 border-y border-line dark:border-dark-line">
        {visible.map((item, index) => {
          const open = expanded === item.id;
          const changed = item.currentScore !== null && item.currentScore !== item.snapshotScore;
          return (
            <View key={item.id} className={index < visible.length - 1 ? "border-b border-line dark:border-dark-line" : ""}>
              <Pressable onPress={() => setExpanded(open ? null : item.id)} className="flex-row items-center gap-3 py-4 hover:bg-rail/50 active:opacity-75 focus-visible:ring-2 focus-visible:ring-accent dark:hover:bg-dark-rail/50 dark:focus-visible:ring-dark-accent">
                <View className="min-w-0 flex-1"><View className="flex-row items-center"><Text className="font-medium text-xs text-accent dark:text-dark-accent">{scanTypeLabels[item.type]}</Text><Text className="ml-2 font-sans text-xs text-muted dark:text-dark-muted">{item.date}</Text></View><Text className="mt-1 font-medium text-sm text-ink dark:text-dark-ink" numberOfLines={1}>{item.target}</Text></View>
                <View className="items-end"><Text className="font-medium text-lg text-ink dark:text-dark-ink">{item.snapshotScore}</Text><View className="mt-1"><RiskBadge level={item.snapshotLevel} /></View></View>
                <FontAwesomeIcon icon={open ? faChevronUp : faChevronDown} size={12} color={colors.muted} />
              </Pressable>
              {open ? <View className="border-t border-line bg-rail py-4 dark:border-dark-line dark:bg-dark-rail md:px-5">
                <View className="flex-row">
                  <View className="min-w-0 flex-1"><Text className="font-sans text-xs text-muted dark:text-dark-muted">Khi quét</Text><Text className="mt-1 font-medium text-xl text-ink dark:text-dark-ink">{item.snapshotScore}</Text><View className="mt-2"><RiskBadge level={item.snapshotLevel} /></View></View>
                  <View className="mx-4 w-px bg-line dark:bg-dark-line" />
                  <View className="min-w-0 flex-1"><Text className="font-sans text-xs text-muted dark:text-dark-muted">Hiện tại</Text><Text className="mt-1 font-medium text-xl text-ink dark:text-dark-ink">{item.currentScore ?? "Không có"}</Text>{item.currentLevel ? <View className="mt-2"><RiskBadge level={item.currentLevel} /></View> : <Text className="mt-2 font-sans text-xs text-muted dark:text-dark-muted">SMS không có uy tín dài hạn</Text>}</View>
                </View>
                {changed ? <Text className="mt-4 font-sans text-xs leading-5 text-warning dark:text-dark-warning">Mức rủi ro đã thay đổi.</Text> : null}
                <View className="mt-4 items-end"><Button accessibilityLabel="Xóa mục lịch sử" label="Xóa khỏi lịch sử" icon={faTrashCan} variant="ghost" onPress={() => onDelete(item.id)} /></View>
              </View> : null}
            </View>
          );
        })}
        {visible.length === 0 ? <EmptyState icon={faClockRotateLeft} title="Chưa có lịch sử phù hợp" description="Thử bộ lọc khác." /> : null}
      </View>
    </View>
  );
}
