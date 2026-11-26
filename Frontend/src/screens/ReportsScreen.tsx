import { faCircleInfo } from "@fortawesome/free-solid-svg-icons/faCircleInfo";
import { faFileCircleCheck } from "@fortawesome/free-solid-svg-icons/faFileCircleCheck";
import { faHourglassHalf } from "@fortawesome/free-solid-svg-icons/faHourglassHalf";
import { faPaperclip } from "@fortawesome/free-solid-svg-icons/faPaperclip";
import { faPlus } from "@fortawesome/free-solid-svg-icons/faPlus";
import { faTriangleExclamation } from "@fortawesome/free-solid-svg-icons/faTriangleExclamation";
import { faCircleCheck } from "@fortawesome/free-solid-svg-icons/faCircleCheck";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { useMemo, useState } from "react";
import { Pressable, Text, View } from "react-native";
import { DemoDataNotice } from "../components/DemoDataNotice";
import { ReportComposer, type NewReport } from "../components/ReportComposer";
import { Alert, Badge, Button, EmptyState, FilterBar } from "../components/ui";
import type { ReportRecord, ReportStatus } from "../data/mock";
import { scanTypeLabels } from "../lib/risk";
import { useAppTheme } from "../theme/AppTheme";

const statusMeta: Record<ReportStatus, { label: string; variant: "neutral" | "warning" | "destructive" | "safe" }> = {
  SUBMITTED: { label: "Đã gửi", variant: "neutral" },
  PENDING_REVIEW: { label: "Đang duyệt", variant: "warning" },
  NEED_MORE_INFO: { label: "Cần bổ sung", variant: "destructive" },
  VERIFIED: { label: "Đã xác minh", variant: "safe" },
  REJECTED: { label: "Không chấp nhận", variant: "neutral" }
};

export function ReportsScreen({ items, demo = false, onCreate }: { items: ReportRecord[]; demo?: boolean; onCreate: (report: NewReport) => void }) {
  const { colors } = useAppTheme();
  const [composerOpen, setComposerOpen] = useState(false);
  const [filter, setFilter] = useState<"ALL" | ReportStatus>("ALL");
  const [expanded, setExpanded] = useState<string | null>(items[0]?.id ?? null);
  const [created, setCreated] = useState(false);
  const visible = useMemo(() => filter === "ALL" ? items : items.filter((item) => item.status === filter), [filter, items]);
  const verified = items.filter((item) => item.status === "VERIFIED").length;
  const actionNeeded = items.filter((item) => item.status === "NEED_MORE_INFO").length;
  const pending = items.length - verified;
  const submitReport = (report: NewReport) => { onCreate(report); setCreated(true); };

  return (
    <View className="min-w-0 w-full">
      <View className="md:flex-row md:items-end md:justify-between"><View className="max-w-[760px]"><Text className="font-display text-[34px] leading-[41px] text-ink dark:text-dark-ink md:text-[42px] md:leading-[50px]">Tố giác của tôi</Text></View><View className="mt-5 self-start md:mt-0"><Button label="Tố giác lừa đảo" icon={faPlus} onPress={() => setComposerOpen(true)} /></View></View>
      {demo ? <View className="mt-5"><DemoDataNotice description="Báo cáo minh họa chỉ lưu trong phiên này, chưa gửi tới máy chủ." /></View> : null}
      {created ? <View className="mt-5"><Alert icon={faCircleCheck} title={demo ? "Đã tạo tố giác minh họa" : "Đã gửi tố giác"} description={demo ? "Báo cáo chỉ tồn tại trong phiên này và chưa được gửi tới máy chủ." : "Đang chờ xử lý."} tone="safe" /></View> : null}

      <View className="mt-8 flex-row border-y border-line dark:border-dark-line">
        <View className="min-w-0 flex-[1.5] py-4 pr-4"><Text className="font-sans text-xs text-muted dark:text-dark-muted">Đang xử lý</Text><Text className="mt-2 font-medium text-3xl text-ink dark:text-dark-ink">{pending}</Text></View>
        <View className="min-w-0 flex-1 border-l border-line px-4 py-4 dark:border-dark-line"><Text className="font-sans text-xs text-muted dark:text-dark-muted">Đã xác minh</Text><Text className="mt-2 font-medium text-xl text-safe dark:text-dark-safe">{verified}</Text></View>
        <View className="min-w-0 flex-1 border-l border-line pl-4 py-4 dark:border-dark-line"><Text className="font-sans text-xs text-muted dark:text-dark-muted">Cần bổ sung</Text><Text className="mt-2 font-medium text-xl text-danger dark:text-dark-danger">{actionNeeded}</Text></View>
      </View>

      <View className="mt-4 border-b border-line dark:border-dark-line"><FilterBar options={(["ALL", "PENDING_REVIEW", "NEED_MORE_INFO", "VERIFIED"] as const).map((item) => ({ value: item, label: item === "ALL" ? "Tất cả" : statusMeta[item].label }))} value={filter} onChange={setFilter} /></View>

      <View className="mt-5 border-y border-line dark:border-dark-line">
        {visible.map((report, index) => {
          const open = expanded === report.id;
          // A newly created demo report is local-only; do not show it as a
          // server-confirmed "submitted" state.
          const meta = demo && report.id.startsWith("BT-DEMO-")
            ? { label: "Bản nháp minh họa", variant: "neutral" as const }
            : statusMeta[report.status];
          return (
            <Pressable key={report.id} onPress={() => setExpanded(open ? null : report.id)} className={`py-4 hover:bg-rail/50 active:opacity-75 focus-visible:ring-2 focus-visible:ring-accent dark:hover:bg-dark-rail/50 dark:focus-visible:ring-dark-accent ${index < visible.length - 1 ? "border-b border-line dark:border-dark-line" : ""}`}>
              <View className="flex-row items-start gap-4"><View className="min-w-0 flex-1"><View className="flex-row items-center"><Text className="font-medium text-xs text-accent dark:text-dark-accent">{report.id}</Text><Text className="ml-2 font-sans text-xs text-muted dark:text-dark-muted">{report.updated}</Text></View><Text className="mt-2 font-medium text-base text-ink dark:text-dark-ink">{report.title}</Text><Text className="mt-1 font-sans text-xs text-muted dark:text-dark-muted" numberOfLines={1}>{scanTypeLabels[report.targetType]} / {report.target}</Text></View><Badge label={meta.label} variant={meta.variant} /></View>
              <View className="mt-3 flex-row items-center"><FontAwesomeIcon icon={faPaperclip} size={12} color={colors.muted} /><Text className="ml-2 font-sans text-xs text-muted dark:text-dark-muted">{report.evidenceCount} bằng chứng</Text><Text className="ml-4 font-sans text-xs text-muted dark:text-dark-muted">{report.priority === "HIGH" ? "Ưu tiên cao" : report.priority === "MEDIUM" ? "Ưu tiên trung bình" : "Ưu tiên thấp"}</Text></View>
              {open ? <View className="mt-4 border-t border-line pt-4 dark:border-dark-line"><View className="flex-row items-start"><FontAwesomeIcon icon={faCircleInfo} size={14} color={colors.accent} /><View className="ml-3 flex-1"><Text className="font-medium text-xs text-ink dark:text-dark-ink">Phản hồi xử lý</Text><Text className="mt-1 font-sans text-xs leading-5 text-muted dark:text-dark-muted">{report.moderatorNote ?? "Báo cáo đang chờ xử lý."}</Text></View></View></View> : null}
            </Pressable>
          );
        })}
        {visible.length === 0 ? <EmptyState icon={filter === "VERIFIED" ? faFileCircleCheck : filter === "NEED_MORE_INFO" ? faTriangleExclamation : faHourglassHalf} title="Không có báo cáo phù hợp" description="Thử bộ lọc khác." action={{ label: "Tố giác lừa đảo", icon: faPlus, onPress: () => setComposerOpen(true) }} /> : null}
      </View>
      <ReportComposer visible={composerOpen} demo={demo} onClose={() => setComposerOpen(false)} onSubmit={submitReport} />
    </View>
  );
}
