import { useEffect, useMemo, useState } from "react";
import { Pressable, Text, View } from "react-native";
import type { AnalyticsTrends, TrendDays } from "../api/analytics";
import {
  computeRiskBreakdownSegments,
  computeTrendBarHeights,
  formatTrendDayLabel,
  parseTrendDays,
  trendDayWindowOptions,
  type TrendDayWindowValue
} from "../lib/admin";
import type { RiskLevel } from "../lib/risk";
import { useAppTheme } from "../theme/AppTheme";
import { ErrorPanel } from "./ui";

const riskColorRole: Record<RiskLevel, "safe" | "warning" | "danger"> = {
  LOW: "safe",
  MEDIUM: "warning",
  HIGH: "danger",
  CRITICAL: "danger"
};

const BAR_TRACK_HEIGHT = 112;

export function AdminTrendChart({
  trends,
  days,
  loading = false,
  error = null,
  onChangeDays,
  onRetry
}: {
  trends: AnalyticsTrends | null;
  days: TrendDays;
  loading?: boolean;
  error?: string | null;
  onChangeDays: (days: TrendDays) => void;
  onRetry?: () => void;
}) {
  const { colors } = useAppTheme();
  const series = trends?.series ?? [];
  const barModel = useMemo(() => computeTrendBarHeights(series), [series]);
  const riskModel = useMemo(
    () =>
      computeRiskBreakdownSegments(
        trends?.riskBreakdown ?? { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 }
      ),
    [trends?.riskBreakdown]
  );

  const [selectedDate, setSelectedDate] = useState<string | null>(
    series[series.length - 1]?.date ?? null
  );

  useEffect(() => {
    if (series.length === 0) {
      setSelectedDate(null);
      return;
    }
    setSelectedDate((current) =>
      current && series.some((point) => point.date === current)
        ? current
        : series[series.length - 1]?.date ?? null
    );
  }, [series]);

  const selectedPoint = useMemo(() => {
    if (series.length === 0) return null;
    return (
      series.find((point) => point.date === selectedDate) ??
      series[series.length - 1] ??
      null
    );
  }, [selectedDate, series]);

  const activeWindowValue: TrendDayWindowValue = String(days) as TrendDayWindowValue;

  return (
    <View
      testID="admin-trend-chart"
      className="mt-6 min-w-0 w-full border-b border-line pb-6 dark:border-dark-line"
    >
      <View className="min-w-0 flex-row flex-wrap items-end justify-between gap-3">
        <View className="min-w-0 flex-1">
          <Text className="font-display text-lg leading-6 text-ink dark:text-dark-ink">
            Xu hướng kiểm tra & báo cáo
          </Text>
          <Text className="mt-1 font-sans text-xs leading-5 text-muted dark:text-dark-muted">
            Tổng hợp theo ngày chuẩn UTC từ dòng sự kiện kiểm tra và kiểm duyệt (không lưu nội dung hay định danh).
          </Text>
        </View>

        <View
          accessibilityRole="radiogroup"
          accessibilityLabel="Khoảng thời gian xu hướng"
          className="flex-row items-center gap-1"
        >
          {trendDayWindowOptions.map((option) => {
            const selected = option.value === activeWindowValue;
            return (
              <Pressable
                key={option.value}
                accessibilityRole="button"
                accessibilityLabel={`Xu hướng ${option.label}`}
                accessibilityState={{ selected }}
                onPress={() => onChangeDays(parseTrendDays(option.value))}
                className={`rounded-[4px] border px-2.5 py-1.5 active:opacity-75 ${
                  selected
                    ? "border-accent bg-rail dark:border-dark-accent dark:bg-dark-rail"
                    : "border-line bg-transparent dark:border-dark-line"
                }`}
              >
                <Text
                  className={`font-medium text-xs ${
                    selected
                      ? "text-accent-strong dark:text-dark-accent-strong"
                      : "text-muted dark:text-dark-muted"
                  }`}
                >
                  {option.label}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      {error ? (
        <View className="mt-4">
          <ErrorPanel
            title="Chưa thể tải xu hướng theo ngày"
            description={error}
            onRetry={onRetry}
          />
        </View>
      ) : null}

      {!error && trends ? (
        <>
          <View className="mt-4 flex-row flex-wrap border-y border-line py-3 dark:border-dark-line">
            <View className="w-1/2 pr-3 sm:w-1/4">
              <Text className="font-sans text-[11px] text-muted dark:text-dark-muted">
                Tổng lượt quét ({trends.days} ngày)
              </Text>
              <Text
                className="mt-1 font-medium text-base text-ink dark:text-dark-ink"
                style={{ fontVariant: ["tabular-nums"] }}
              >
                {trends.totals.totalScans}
              </Text>
            </View>
            <View className="w-1/2 border-l border-line pl-3 dark:border-dark-line sm:w-1/4">
              <Text className="font-sans text-[11px] text-muted dark:text-dark-muted">
                Quét nguy cơ cao ({trends.days} ngày)
              </Text>
              <Text
                className="mt-1 font-medium text-base text-danger dark:text-dark-danger"
                style={{ fontVariant: ["tabular-nums"] }}
              >
                {trends.totals.highRiskScans}
              </Text>
            </View>
            <View className="mt-3 w-1/2 pr-3 sm:mt-0 sm:w-1/4 sm:border-l sm:border-line sm:pl-3 dark:sm:border-dark-line">
              <Text className="font-sans text-[11px] text-muted dark:text-dark-muted">
                Báo cáo mới / Đã xác minh
              </Text>
              <Text
                className="mt-1 font-medium text-base text-ink dark:text-dark-ink"
                style={{ fontVariant: ["tabular-nums"] }}
              >
                {trends.totals.reportsCreated} / {trends.totals.reportsVerified}
              </Text>
            </View>
            <View className="mt-3 w-1/2 border-l border-line pl-3 dark:border-dark-line sm:mt-0 sm:w-1/4">
              <Text className="font-sans text-[11px] text-muted dark:text-dark-muted">
                Mục tiêu đe dọa mới
              </Text>
              <Text
                className="mt-1 font-medium text-base text-safe dark:text-dark-safe"
                style={{ fontVariant: ["tabular-nums"] }}
              >
                {trends.totals.newThreats}
              </Text>
            </View>
          </View>

          {!barModel.hasActivity ? (
            <View className="mt-4 rounded-[4px] border border-line px-4 py-5 dark:border-dark-line">
              <Text className="font-medium text-xs text-ink dark:text-dark-ink">
                Chưa có dữ liệu xu hướng theo ngày
              </Text>
              <Text className="mt-1 font-sans text-xs leading-5 text-muted dark:text-dark-muted">
                Các bộ đếm trong khoảng {formatTrendDayLabel(trends.fromDate)} – {formatTrendDayLabel(trends.toDate)} hiện bằng 0.
              </Text>
            </View>
          ) : (
            <View className="mt-5 min-w-0 w-full">
              <View
                accessibilityRole="none"
                className="min-w-0 w-full flex-row items-end gap-[2px] border-b border-line pb-2 dark:border-dark-line md:gap-1.5"
                style={{ height: BAR_TRACK_HEIGHT + 16 }}
              >
                {barModel.bars.map((bar) => {
                  const isSelected = selectedPoint?.date === bar.point.date;
                  const totalHeight =
                    bar.point.totalScans > 0
                      ? Math.max(6, Math.round(bar.totalRatio * BAR_TRACK_HEIGHT))
                      : 2;
                  const highRiskHeight =
                    bar.point.highRiskScans > 0
                      ? Math.min(
                          totalHeight,
                          Math.max(4, Math.round(bar.highRiskRatio * BAR_TRACK_HEIGHT))
                        )
                      : 0;

                  return (
                    <Pressable
                      key={bar.point.date}
                      accessibilityRole="button"
                      accessibilityLabel={`Ngày ${bar.shortLabel}: ${bar.point.totalScans} lượt kiểm tra, ${bar.point.highRiskScans} nguy cơ cao, ${bar.point.reportsCreated} báo cáo`}
                      accessibilityState={{ selected: isSelected }}
                      onPress={() => setSelectedDate(bar.point.date)}
                      className="min-w-0 flex-1 items-center justify-end active:opacity-75"
                      style={{ height: BAR_TRACK_HEIGHT + 12 }}
                    >
                      <View
                        className="w-full max-w-[24px] justify-end overflow-hidden rounded-t-[2px]"
                        style={{
                          height: totalHeight,
                          backgroundColor: isSelected ? colors.accent : colors.line,
                          opacity: loading ? 0.6 : 1
                        }}
                      >
                        {highRiskHeight > 0 ? (
                          <View
                            style={{
                              height: highRiskHeight,
                              width: "100%",
                              backgroundColor: colors.danger
                            }}
                          />
                        ) : null}
                      </View>
                    </Pressable>
                  );
                })}
              </View>

              <View className="mt-2 flex-row items-center justify-between">
                <Text
                  className="font-sans text-[11px] text-muted dark:text-dark-muted"
                  style={{ fontVariant: ["tabular-nums"] }}
                >
                  {formatTrendDayLabel(trends.fromDate)}
                </Text>
                {selectedPoint ? (
                  <Text
                    className="font-medium text-[11px] text-accent dark:text-dark-accent"
                    style={{ fontVariant: ["tabular-nums"] }}
                  >
                    Đang chọn: {formatTrendDayLabel(selectedPoint.date)}
                  </Text>
                ) : null}
                <Text
                  className="font-sans text-[11px] text-muted dark:text-dark-muted"
                  style={{ fontVariant: ["tabular-nums"] }}
                >
                  {formatTrendDayLabel(trends.toDate)}
                </Text>
              </View>

              {selectedPoint ? (
                <View
                  testID="admin-trend-selected-day"
                  className="mt-3 flex-row flex-wrap items-center justify-between gap-2 rounded-[4px] bg-rail/60 px-3 py-2.5 dark:bg-dark-rail/60"
                >
                  <Text
                    className="font-medium text-xs text-ink dark:text-dark-ink"
                    style={{ fontVariant: ["tabular-nums"] }}
                  >
                    Ngày {formatTrendDayLabel(selectedPoint.date)}: {selectedPoint.totalScans} lượt quét ({selectedPoint.textScans} tin nhắn, {selectedPoint.urlScans} URL)
                  </Text>
                  <Text
                    className="font-sans text-xs text-muted dark:text-dark-muted"
                    style={{ fontVariant: ["tabular-nums"] }}
                  >
                    Nguy cơ cao: {selectedPoint.highRiskScans} · Báo cáo: {selectedPoint.reportsCreated} gửi / {selectedPoint.reportsVerified} xác minh · Mối đe dọa mới: {selectedPoint.newThreats}
                  </Text>
                </View>
              ) : null}
            </View>
          )}

          <View className="mt-4">
            <View className="flex-row items-center justify-between">
              <Text className="font-medium text-xs text-ink dark:text-dark-ink">
                Phân bố mức rủi ro kiểm tra
              </Text>
              <Text
                className="font-sans text-[11px] text-muted dark:text-dark-muted"
                style={{ fontVariant: ["tabular-nums"] }}
              >
                Tổng: {riskModel.total}
              </Text>
            </View>

            <View className="mt-2 h-2 w-full flex-row overflow-hidden rounded-[2px] bg-line dark:bg-dark-line">
              {riskModel.segments.map((segment) =>
                segment.count > 0 ? (
                  <View
                    key={segment.level}
                    style={{
                      flex: segment.count,
                      backgroundColor: colors[riskColorRole[segment.level]],
                      opacity: segment.level === "HIGH" ? 0.75 : 1
                    }}
                  />
                ) : null
              )}
            </View>

            <View className="mt-2 flex-row flex-wrap gap-x-4 gap-y-1">
              {riskModel.segments.map((segment) => (
                <Text
                  key={segment.level}
                  className="font-sans text-[11px] text-muted dark:text-dark-muted"
                  style={{ fontVariant: ["tabular-nums"] }}
                >
                  {segment.label}: {segment.count} ({segment.percent}%)
                </Text>
              ))}
            </View>
          </View>
        </>
      ) : null}
    </View>
  );
}
