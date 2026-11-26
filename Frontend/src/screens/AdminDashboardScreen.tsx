import { faCircleInfo } from "@fortawesome/free-solid-svg-icons/faCircleInfo";
import { faHourglassHalf } from "@fortawesome/free-solid-svg-icons/faHourglassHalf";
import { faPaperclip } from "@fortawesome/free-solid-svg-icons/faPaperclip";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Animated, Easing, Pressable, Text, View } from "react-native";
import { apiErrorMessage } from "../api/client";
import type {
  AnalyticsApi,
  AnalyticsCounts,
  AnalyticsTrends,
  AdminSummary,
  TrendDays
} from "../api/analytics";
import { AdminTrendChart } from "../components/AdminTrendChart";
import { useReducedMotion } from "../components/Motion";
import { Alert, EmptyState, ErrorPanel, FilterBar, LoadingPanel } from "../components/ui";
import { adminQueueDemo, demoScansToday, getDemoAnalyticsTrends } from "../data/adminDemo";
import {
  adminTargetTypeLabel,
  adminQueueFilterLabels,
  adminQueueFilters,
  adminCategoryLabels,
  computeAdminQueueMetrics,
  countAdminQueueFilters,
  filterAdminQueue,
  fromAnalyticsQueueItem,
  reportStatusMeta,
  type AdminQueueFilter,
  type AdminQueueItem
} from "../lib/admin";
import type { RiskLevel } from "../lib/risk";
import type { ReportStatus } from "../data/mock";
import { useAppTheme } from "../theme/AppTheme";

/** Thanh đo rủi ro 4 segment — thay cho badge viên thuốc. */
const riskMeterConfig: Record<RiskLevel, { filled: number; label: string; className: string }> = {
  LOW: { filled: 1, label: "Thấp", className: "text-safe dark:text-dark-safe" },
  MEDIUM: { filled: 2, label: "Trung bình", className: "text-warning dark:text-dark-warning" },
  HIGH: { filled: 3, label: "Cao", className: "text-danger dark:text-dark-danger" },
  CRITICAL: { filled: 4, label: "Rất cao", className: "text-danger dark:text-dark-danger" }
};

function RiskMeter({ level }: { level: RiskLevel | null }) {
  const { colors } = useAppTheme();
  const item = level ? riskMeterConfig[level] : null;
  const filled = item?.filled ?? 0;
  const label = item?.label ?? "Chưa có điểm";
  return (
    <View accessibilityRole="image" accessibilityLabel={`Mức rủi ro: ${label}`} className="flex-row items-center gap-2">
      <View className="flex-row items-center gap-[3px]">
        {[0, 1, 2, 3].map((index) => (
          <View
            key={index}
            className={`h-1.5 w-3 rounded-[2px] ${index < filled ? "" : "bg-line dark:bg-dark-line"}`}
            style={index < filled ? { backgroundColor: colors.danger } : undefined}
          />
        ))}
      </View>
      <Text className={`font-sans text-[11px] font-medium ${item?.className ?? "text-muted dark:text-dark-muted"}`}>{label}</Text>
    </View>
  );
}

const statusDotClasses: Record<ReportStatus, { dot: string; text: string }> = {
  SUBMITTED: { dot: "bg-rail dark:bg-dark-rail", text: "text-muted dark:text-dark-muted" },
  PENDING_REVIEW: { dot: "bg-warning dark:bg-dark-warning", text: "text-warning dark:text-dark-warning" },
  NEED_MORE_INFO: { dot: "bg-danger dark:bg-dark-danger", text: "text-danger dark:text-dark-danger" },
  VERIFIED: { dot: "bg-safe dark:bg-dark-safe", text: "text-safe dark:text-dark-safe" },
  REJECTED: { dot: "bg-rail dark:bg-dark-rail", text: "text-muted dark:text-dark-muted" }
};

function queueReference(id: string): string {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)
    ? `#${id.slice(0, 8).toUpperCase()}`
    : id;
}

function StatusIndicator({ status }: { status: ReportStatus }) {
  const style = statusDotClasses[status];
  const label = reportStatusMeta[status].label;
  return (
    <View accessibilityRole="text" accessibilityLabel={`Trạng thái: ${label}`} className="flex-row items-center gap-2">
      <View className={`h-[7px] w-[7px] rounded-full ${style.dot}`} />
      <Text className={`font-sans text-[11px] font-medium ${style.text}`}>{label}</Text>
    </View>
  );
}

/**
 * Màn hình Quản trị (Admin Dashboard) — hàng chờ kiểm duyệt cho team mod/admin.
 *
 * Khi có Analytics API (chế độ API), màn hình đọc summary và hàng chờ thật. Khi
 * `EXPO_PUBLIC_API_URL` chưa được cấu hình, màn hình giữ dữ liệu minh họa để E2E
 * mock và bản xem trước giao diện tiếp tục hoạt động.
 */
export function AdminDashboardScreen({ api = null }: { api?: AnalyticsApi | null }) {
  const { colors } = useAppTheme();
  const reduced = useReducedMotion();
  const demo = adminQueueDemo;
  const [liveItems, setLiveItems] = useState<AdminQueueItem[]>([]);
  const [summary, setSummary] = useState<AdminSummary | null>(null);
  const [counts, setCounts] = useState<AnalyticsCounts | null>(null);
  const [trendDays, setTrendDays] = useState<TrendDays>(14);
  const [liveTrends, setLiveTrends] = useState<AnalyticsTrends | null>(null);
  const [trendLoading, setTrendLoading] = useState<boolean>(false);
  const [trendError, setTrendError] = useState<string | null>(null);
  const [loading, setLoading] = useState(Boolean(api));
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<AdminQueueFilter>("ALL");
  const [expanded, setExpanded] = useState<string | null>(demo[0]?.id ?? null);

  const loadTrends = useCallback(async (days: TrendDays) => {
    if (!api || typeof api.trends !== "function") return;
    setTrendLoading(true);
    setTrendError(null);
    try {
      const nextTrends = await api.trends(days);
      setLiveTrends(nextTrends);
    } catch (cause) {
      setTrendError(apiErrorMessage(cause, "Chưa thể tải xu hướng theo ngày."));
    } finally {
      setTrendLoading(false);
    }
  }, [api]);

  const load = useCallback(async () => {
    if (!api) return;
    setLoading(true);
    setError(null);
    try {
      const [nextSummary, page, nextCounts] = await Promise.all([
        api.summary(),
        api.queue({ page: 0, size: 100, sort: "submittedAt,desc" }),
        api.counts()
      ]);
      setSummary(nextSummary);
      setLiveItems(page.items.map(fromAnalyticsQueueItem));
      setCounts(nextCounts);
    } catch (cause) {
      setError(apiErrorMessage(cause, "Chưa thể tải dữ liệu quản trị."));
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadTrends(trendDays);
  }, [loadTrends, trendDays]);

  const demoTrends = useMemo(() => getDemoAnalyticsTrends(trendDays), [trendDays]);
  const trends = api ? liveTrends : demoTrends;

  const items = api ? liveItems : demo;
  const metrics = useMemo(() => api && summary
    ? {
        pendingReview: summary.pendingReview,
        needInfo: summary.needMoreInfo,
        resolved: summary.resolvedToday,
        highRisk: summary.highRisk
      }
    : computeAdminQueueMetrics(items), [api, items, summary]);
  const visible = useMemo(() => filterAdminQueue(items, filter), [filter, items]);

  useEffect(() => {
    if (!api || liveItems.length === 0) return;
    setExpanded((current) => current && liveItems.some((item) => item.id === current)
      ? current
      : liveItems[0]?.id ?? null);
  }, [api, liveItems]);

  const firstLoad = Boolean(api && loading && !summary);
  const unavailable = Boolean(api && error && !summary);

  // Số tick dần vào vị trí (Stat-Led: một khoảnh khắc chuyển động duy nhất), tôn trọng reduced-motion.
  const tick = useRef(new Animated.Value(metrics.pendingReview)).current;
  const [pendingDisplay, setPendingDisplay] = useState(metrics.pendingReview);
  useEffect(() => {
    const listener = tick.addListener(({ value }) => setPendingDisplay(Math.round(value)));
    return () => tick.removeListener(listener);
  }, [tick]);
  useEffect(() => {
    if (reduced) {
      tick.setValue(metrics.pendingReview);
      return;
    }
    tick.setValue(0);
    const animation = Animated.timing(tick, {
      toValue: metrics.pendingReview,
      duration: 450,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: false
    });
    animation.start();
    return () => animation.stop();
  }, [metrics.pendingReview, reduced, tick]);

  const statCells = [
    { label: "Cần bổ sung", value: metrics.needInfo, className: "text-danger dark:text-dark-danger" },
    { label: api ? "Đã xử lý hôm nay" : "Đã xử lý", value: metrics.resolved, className: "text-safe dark:text-dark-safe" },
    { label: api ? "Quét nguy cơ cao" : "Nguy cơ cao", value: metrics.highRisk, className: "text-danger dark:text-dark-danger" },
    { label: "Lượt kiểm tra hôm nay", value: api ? summary?.scansToday ?? 0 : demoScansToday, className: "text-ink dark:text-dark-ink" }
  ];

  const filterCounts = counts ? countAdminQueueFilters(counts) : null;
  const filterOptions = adminQueueFilters.map((value) => ({
    value,
    label: filterCounts ? `${adminQueueFilterLabels[value]} (${filterCounts[value]})` : adminQueueFilterLabels[value]
  }));

  if (firstLoad || unavailable) {
    return (
      <View className="min-w-0 w-full">
        <View className="max-w-[760px]">
          <Text className="font-display text-[34px] leading-[41px] text-ink dark:text-dark-ink md:text-[42px] md:leading-[50px]">Hàng chờ kiểm duyệt</Text>
          <Text className="mt-2 font-sans text-sm leading-6 text-muted dark:text-dark-muted">Danh sách báo cáo người dùng gửi lên, chờ team kiểm duyệt xác minh hoặc yêu cầu bổ sung.</Text>
        </View>
        <View className="mt-5">
          {error
            ? <ErrorPanel title="Chưa thể tải dữ liệu quản trị" description={error} onRetry={() => void load()} />
            : <LoadingPanel label="Đang tải dữ liệu quản trị" />}
        </View>
      </View>
    );
  }

  return (
    <View className="min-w-0 w-full">
      <View className="max-w-[760px]">
        <Text className="font-display text-[34px] leading-[41px] text-ink dark:text-dark-ink md:text-[42px] md:leading-[50px]">Hàng chờ kiểm duyệt</Text>
        <Text className="mt-2 font-sans text-sm leading-6 text-muted dark:text-dark-muted">Danh sách báo cáo người dùng gửi lên, chờ team kiểm duyệt xác minh hoặc yêu cầu bổ sung.</Text>
      </View>

      {!api ? (
        <View className="mt-5">
          <Alert
            icon={faCircleInfo}
            tone="neutral"
            title="Dữ liệu minh họa"
            description="Số liệu và báo cáo trên trang này là dữ liệu mô phỏng để xem trước giao diện. Chưa cấu hình API nên màn hình giữ chế độ mock."
          />
        </View>
      ) : null}

      {api && error ? (
        <View className="mt-5">
          <ErrorPanel title="Không thể làm mới dữ liệu" description={error} onRetry={() => void load()} />
        </View>
      ) : null}

      {/* Hero stat — Stat-Led: con số đọc cùng câu chữ giải nghĩa, không đứng một mình. */}
      <View className="mt-7 border-y border-line py-6 dark:border-dark-line md:flex-row md:items-end md:gap-10">
        <View
          className="min-w-0 flex-[1.2]"
          accessibilityLabel={`${metrics.pendingReview} báo cáo đang chờ kiểm duyệt`}
        >
          <Animated.Text
            className="font-display text-[72px] leading-[78px] text-ink dark:text-dark-ink"
            style={{ fontVariant: ["tabular-nums"] }}
          >
            {pendingDisplay}
          </Animated.Text>
          <Text className="mt-1 max-w-[440px] font-display text-xl leading-7 text-ink dark:text-dark-ink">báo cáo đang chờ kiểm duyệt</Text>
          <Text className="mt-2 max-w-[440px] font-sans text-xs leading-5 text-muted dark:text-dark-muted">Việc cần làm đầu tiên hôm nay: duyệt báo cáo hoặc yêu cầu người gửi bổ sung thông tin.</Text>
        </View>

        <View className="mt-5 min-w-0 flex-1 md:mt-0">
          <View className="flex-row flex-wrap border-t border-line dark:border-dark-line md:border-t-0">
            {statCells.map((cell, index) => (
              <View
                key={cell.label}
                className={`w-1/2 border-line py-4 dark:border-dark-line ${index % 2 === 1 ? "border-l pl-4" : "pr-4"} ${index < 2 ? "border-b" : ""}`}
              >
                <Text className="font-sans text-xs text-muted dark:text-dark-muted">{cell.label}</Text>
                <Text className={`mt-2 font-medium text-xl ${cell.className}`} style={{ fontVariant: ["tabular-nums"] }}>{cell.value}</Text>
              </View>
            ))}
          </View>
        </View>
      </View>

      <AdminTrendChart
        trends={trends}
        days={trendDays}
        loading={trendLoading}
        error={api ? trendError : null}
        onChangeDays={setTrendDays}
        onRetry={() => void loadTrends(trendDays)}
      />

      <View className="mt-4 border-b border-line dark:border-dark-line">
        <FilterBar label="Bộ lọc hàng chờ" options={filterOptions} value={filter} onChange={setFilter} />
      </View>

      <View className="mt-5 border-y border-line dark:border-dark-line">
        {visible.map((item, index) => {
          const open = expanded === item.id;
          return (
            <Pressable
              key={item.id}
              accessibilityRole="button"
              accessibilityState={{ expanded: open }}
              onPress={() => setExpanded(open ? null : item.id)}
              className={`py-4 hover:bg-rail/50 active:opacity-75 focus-visible:ring-2 focus-visible:ring-accent dark:hover:bg-dark-rail/50 dark:focus-visible:ring-dark-accent ${index < visible.length - 1 ? "border-b border-line dark:border-dark-line" : ""}`}
            >
              <View className="flex-row items-start gap-4">
                <View className="min-w-0 flex-1">
                  <View className="flex-row items-center">
                    <Text className="font-medium text-xs text-accent dark:text-dark-accent">{queueReference(item.id)}</Text>
                    <Text className="ml-2 font-sans text-xs text-muted dark:text-dark-muted">{item.submitted}</Text>
                  </View>
                  <Text className="mt-2 font-medium text-base text-ink dark:text-dark-ink">{item.title}</Text>
                  <Text className="mt-1 font-sans text-xs text-muted dark:text-dark-muted" numberOfLines={1}>{adminTargetTypeLabel(item.targetType)} / {item.target}</Text>
                  <View className="mt-3 flex-row flex-wrap items-center gap-x-4 gap-y-2">
                    <RiskMeter level={item.riskLevel} />
                    <StatusIndicator status={item.status} />
                    <Text className="font-sans text-xs text-muted dark:text-dark-muted">{adminCategoryLabels[item.category] ?? item.category}</Text>
                    <View accessibilityRole="text" accessibilityLabel={`${item.evidenceCount} bằng chứng`} className="flex-row items-center">
                      <FontAwesomeIcon icon={faPaperclip} size={11} color={colors.muted} />
                      <Text className="ml-1.5 font-sans text-xs text-muted dark:text-dark-muted">{item.evidenceCount} bằng chứng</Text>
                    </View>
                  </View>
                </View>
              </View>
              {open ? (
                <View className="mt-4 border-t border-line pt-4 dark:border-dark-line">
                  <View className="flex-row items-start">
                    <FontAwesomeIcon icon={faCircleInfo} size={14} color={colors.accent} />
                    <View className="ml-3 flex-1">
                      <Text className="font-medium text-xs text-ink dark:text-dark-ink">Ghi chú kiểm duyệt</Text>
                      <Text className="mt-1 font-sans text-xs leading-5 text-muted dark:text-dark-muted">{item.moderatorNote ?? "Chưa có ghi chú. Chờ kiểm duyệt viên xác minh nội dung."}</Text>
                    </View>
                  </View>
                </View>
              ) : null}
            </Pressable>
          );
        })}
        {visible.length === 0 ? (
          <EmptyState icon={faHourglassHalf} title="Không có báo cáo nào" description="Hãy chọn bộ lọc khác để xem các mục còn lại." align="start" compact />
        ) : null}
      </View>

      <Text className="mt-4 font-sans text-[11px] leading-5 text-muted dark:text-dark-muted">
        {api
          ? "Nguồn: Analytics-Service, tổng hợp từ Report-Service và Threat-Service. Điểm rủi ro lấy từ Reputation-Service; “Chưa có điểm” nghĩa là mục tiêu chưa có hồ sơ uy tín."
          : "Nguồn: dữ liệu minh họa trong src/data/adminDemo.ts. Cấu hình EXPO_PUBLIC_API_URL để dùng dữ liệu quản trị thật."}
      </Text>
    </View>
  );
}
