/* Hallmark · review-first moderation · DESIGN.md Material3/Roboto/FontAwesome
 * Five contract statuses, atomic counts, polling and report workflow preserved.
 */
import { useCallback, useEffect, useState } from "react";
import { faChevronDown } from "@fortawesome/free-solid-svg-icons/faChevronDown";
import { faChevronUp } from "@fortawesome/free-solid-svg-icons/faChevronUp";
import { faArrowRotateRight } from "@fortawesome/free-solid-svg-icons/faArrowRotateRight";
import { Text, View } from "react-native";
import type { ReportApi, ReportStatus } from "../api/reports";
import type { AuthSession } from "../lib/auth";
import { Button, ErrorPanel, LoadingPanel } from "../components/ui";
import { apiErrorMessage } from "../api/client";
import { ReportWorkspace } from "./ReportWorkspace";
import { reportStatusMeta } from "../lib/admin";
const statuses: ReportStatus[] = ["SUBMITTED", "PENDING_REVIEW", "NEED_MORE_INFO", "VERIFIED", "REJECTED"];
const labels = statuses.map(status => reportStatusMeta[status].label);
export function AdminModerationScreen({ api, session }: { api: ReportApi; session: AuthSession }) {
  const [counts, setCounts] = useState<number[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);
  const refresh = useCallback(() => setRevision(v => v + 1), []);
  useEffect(() => {
    let active = true;
    setLoading(true);
    const load = async () => { try { const pages = await Promise.all(statuses.map(s => api.queue(s, 0))); if (active) { setCounts(pages.map(p => p.totalItems)); setLoadedAt(new Date()); setError(null); } } catch (e) { if (active) setError(apiErrorMessage(e, "Chưa tải được thống kê tố giác.")); } finally { if (active) setLoading(false); } };
    void load(); const timer = setInterval(refresh, 60000);
    return () => { active = false; clearInterval(timer); };
  }, [api, revision, refresh]);
  const muted = "font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant";
  return <View testID="admin-moderation" className="min-w-0 gap-5">
    <View className="gap-3 border-b border-md-outline-variant pb-4 dark:border-dark-md-outline-variant">
      <View className="flex-row flex-wrap items-center gap-3"><Button appearance="material" label={expanded ? "Ẩn thống kê tố giác" : "Thống kê tố giác"} variant="ghost" trailingIcon={expanded ? faChevronUp : faChevronDown} aria-expanded={expanded} aria-controls="moderation-counts" accessibilityState={{ expanded }} onPress={() => setExpanded(value => !value)} />{loading ? <Text accessibilityLiveRegion="polite" className={muted}>Đang tải số liệu…</Text> : error && !expanded ? <Text accessibilityLiveRegion="polite" className={muted}>Chưa cập nhật được số liệu.</Text> : null}</View>
      {expanded ? <View nativeID="moderation-counts" className="gap-4">
        <View className="flex-row flex-wrap items-center justify-between gap-2"><Text accessibilityRole="header" aria-level={2} className="font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface">Theo trạng thái</Text><Button appearance="material" label="Làm mới thống kê tố giác" icon={faArrowRotateRight} variant="ghost" loading={loading} disabled={loading} onPress={refresh} /></View>
        <Text className={muted}>Làm mới số liệu cũng cập nhật danh sách báo cáo.</Text>
        {loadedAt ? <Text className={muted}>Lần tải thành công: {loadedAt.toLocaleString("vi-VN")}.</Text> : null}
        {error ? <ErrorPanel title="Chưa cập nhật được thống kê" description={error} onRetry={loading ? undefined : refresh} /> : null}
        {loading && !counts ? <LoadingPanel label="Đang tải thống kê tố giác" /> : null}
        {error && counts ? <Text className={muted}>Số liệu dưới đây là lần tải thành công trước đó, chưa phải bản cập nhật mới nhất.</Text> : null}
        {counts ? <View className="flex-row flex-wrap gap-x-6 gap-y-4">{counts.map((count, i) => <View key={statuses[i]} className="min-w-[120px] flex-1 gap-1"><Text className={muted}>{labels[i]}</Text><Text accessibilityLabel={`${labels[i]}: ${count}`} className="font-medium text-xl leading-7 text-md-on-surface dark:text-dark-md-on-surface">{count.toLocaleString("vi-VN")}</Text></View>)}</View> : null}
      </View> : null}
    </View>
    <ReportWorkspace api={api} session={session} initialReview pinnedReview onChanged={refresh} refreshToken={revision} />
  </View>;
}
