import type {
  AnalyticsCounts,
  AnalyticsQueueItem,
  DailyTrendPoint,
  RiskBreakdown,
  TrendDays
} from "../api/analytics";
import type { ReportStatus } from "../data/mock";
import { relativeTime } from "./time";
import { scanTypeLabels, type RiskLevel, type ScanType } from "./risk";

/** Một mục trong hàng chờ kiểm duyệt của màn hình quản trị. */
export type AdminQueueItem = {
  id: string;
  targetType: string;
  target: string;
  category: string;
  title: string;
  status: ReportStatus;
  /** null khi mục tiêu chưa có hồ sơ uy tín; không được hiển thị thành LOW. */
  riskLevel: RiskLevel | null;
  submitted: string;
  evidenceCount: number;
  moderatorNote: string | null;
};

/** Nhãn loại mục tiêu cho cả dữ liệu demo và dữ liệu Report-Service. */
export function adminTargetTypeLabel(type: string): string {
  if (type === "MESSAGE_TEMPLATE") return "Tin nhắn";
  return scanTypeLabels[type as ScanType] ?? type;
}

/** Chuyển một dòng Analytics-Service thành view model của dashboard. */
export function fromAnalyticsQueueItem(item: AnalyticsQueueItem): AdminQueueItem {
  return {
    id: item.id,
    targetType: item.targetType,
    target: item.target,
    category: item.category,
    title: item.title,
    status: item.status,
    riskLevel: item.riskLevel,
    submitted: relativeTime(item.submittedAt),
    evidenceCount: item.evidenceCount,
    moderatorNote: item.moderatorNote
  };
}

/** Trạng thái mà moderator cần xem xét. */
export const reviewableStatuses: ReportStatus[] = ["SUBMITTED", "PENDING_REVIEW"];
/** Trạng thái đã kết thúc. */
export const resolvedStatuses: ReportStatus[] = ["VERIFIED", "REJECTED"];
export const highRiskLevels: RiskLevel[] = ["HIGH", "CRITICAL"];

export const reportStatusMeta: Record<ReportStatus, { label: string; variant: "neutral" | "warning" | "destructive" | "safe" }> = {
  SUBMITTED: { label: "Đã gửi", variant: "neutral" },
  PENDING_REVIEW: { label: "Đang duyệt", variant: "warning" },
  NEED_MORE_INFO: { label: "Cần bổ sung", variant: "destructive" },
  VERIFIED: { label: "Đã xác minh", variant: "safe" },
  REJECTED: { label: "Không chấp nhận", variant: "neutral" }
};

export const adminCategoryLabels: Record<string, string> = {
  FAKE_SHIPPER: "Giả mạo shipper",
  BANK_IMPERSONATION: "Mạo danh ngân hàng",
  GOV_IMPERSONATION: "Mạo danh cơ quan nhà nước",
  PHISHING: "Đánh cắp thông tin",
  INVESTMENT_SCAM: "Lừa đảo đầu tư",
  JOB_SCAM: "Lừa đảo việc làm",
  PRIZE_SCAM: "Lừa đảo trúng thưởng",
  ACCOUNT_THEFT: "Đánh cắp tài khoản",
  SOCIAL_IMPERSONATION: "Giả mạo trên mạng xã hội",
  MARKETPLACE_SCAM: "Lừa đảo sàn thương mại",
  SUSPICIOUS_MESSAGE: "Tin nhắn đáng ngờ",
  PHISHING_URL: "Đường dẫn lừa đảo",
  SPAM: "Spam",
  OTHER: "Khác"
};

export const adminQueueFilters = ["ALL", "REVIEWABLE", "NEED_MORE_INFO", "RESOLVED"] as const;
export type AdminQueueFilter = (typeof adminQueueFilters)[number];

export const adminQueueFilterLabels: Record<AdminQueueFilter, string> = {
  ALL: "Tất cả",
  REVIEWABLE: "Chờ duyệt",
  NEED_MORE_INFO: "Cần bổ sung",
  RESOLVED: "Đã xử lý"
};

/** Đếm số báo cáo cho từng tab từ counts của Report-Service. */
export function countAdminQueueFilters(counts: AnalyticsCounts): Record<AdminQueueFilter, number> {
  const count = (status: ReportStatus) => counts.byStatus[status] ?? 0;
  return {
    ALL: Object.values(counts.byStatus).reduce((sum, value) => sum + value, 0),
    REVIEWABLE: count("SUBMITTED") + count("PENDING_REVIEW"),
    NEED_MORE_INFO: count("NEED_MORE_INFO"),
    RESOLVED: count("VERIFIED") + count("REJECTED")
  };
}

export function filterAdminQueue(items: AdminQueueItem[], filter: AdminQueueFilter): AdminQueueItem[] {
  if (filter === "REVIEWABLE") return items.filter((item) => reviewableStatuses.includes(item.status));
  if (filter === "NEED_MORE_INFO") return items.filter((item) => item.status === "NEED_MORE_INFO");
  if (filter === "RESOLVED") return items.filter((item) => resolvedStatuses.includes(item.status));
  return items;
}

export type AdminQueueMetrics = {
  pendingReview: number;
  needInfo: number;
  resolved: number;
  highRisk: number;
};

/** Tổng hợp số liệu màn hình quản trị từ danh sách báo cáo. */
export function computeAdminQueueMetrics(items: AdminQueueItem[]): AdminQueueMetrics {
  return {
    pendingReview: items.filter((item) => reviewableStatuses.includes(item.status)).length,
    needInfo: items.filter((item) => item.status === "NEED_MORE_INFO").length,
    resolved: items.filter((item) => resolvedStatuses.includes(item.status)).length,
    highRisk: items.filter((item) => item.riskLevel !== null && highRiskLevels.includes(item.riskLevel)).length
  };
}

export const trendDayWindowOptions = [
  { value: "7", label: "7 ngày" },
  { value: "14", label: "14 ngày" },
  { value: "30", label: "30 ngày" }
] as const;

export type TrendDayWindowValue = (typeof trendDayWindowOptions)[number]["value"];

export function parseTrendDays(value: TrendDayWindowValue): TrendDays {
  if (value === "7") return 7;
  if (value === "30") return 30;
  return 14;
}

/** Định dạng chuỗi YYYY-MM-DD chuẩn UTC thành nhãn ngắn DD/MM không phụ thuộc múi giờ máy khách. */
export function formatTrendDayLabel(isoDate: string): string {
  const parts = isoDate.split("-");
  if (parts.length !== 3) return isoDate;
  return `${parts[2]}/${parts[1]}`;
}

export type TrendBarDatum = {
  point: DailyTrendPoint;
  shortLabel: string;
  totalRatio: number;
  highRiskRatio: number;
};

export type TrendBarSeriesModel = {
  maxScans: number;
  hasActivity: boolean;
  bars: TrendBarDatum[];
};

/** Tính tỷ lệ chiều cao cột [0..1] cho biểu đồ xu hướng theo ngày. */
export function computeTrendBarHeights(series: readonly DailyTrendPoint[]): TrendBarSeriesModel {
  let maxScans = 0;
  let hasActivity = false;
  for (const point of series) {
    if (point.totalScans > maxScans) maxScans = point.totalScans;
    if (point.totalScans > 0 || point.reportsCreated > 0 || point.reportsVerified > 0 || point.newThreats > 0) {
      hasActivity = true;
    }
  }
  const denominator = maxScans > 0 ? maxScans : 1;
  const bars: TrendBarDatum[] = series.map((point) => ({
    point,
    shortLabel: formatTrendDayLabel(point.date),
    totalRatio: maxScans > 0 ? Math.min(1, Math.max(0, point.totalScans / denominator)) : 0,
    highRiskRatio: maxScans > 0 ? Math.min(1, Math.max(0, point.highRiskScans / denominator)) : 0
  }));
  return { maxScans, hasActivity, bars };
}

export type RiskBreakdownSegment = {
  level: RiskLevel;
  label: string;
  count: number;
  percent: number;
};

const riskOrder: readonly { level: RiskLevel; label: string }[] = [
  { level: "LOW", label: "Thấp" },
  { level: "MEDIUM", label: "Trung bình" },
  { level: "HIGH", label: "Cao" },
  { level: "CRITICAL", label: "Rất cao" }
];

export function computeRiskBreakdownSegments(breakdown: RiskBreakdown): {
  total: number;
  segments: RiskBreakdownSegment[];
} {
  const total = riskOrder.reduce((sum, item) => sum + Math.max(0, breakdown[item.level] ?? 0), 0);
  const segments = riskOrder.map(({ level, label }) => {
    const count = Math.max(0, breakdown[level] ?? 0);
    const percent = total > 0 ? Math.round((count / total) * 1000) / 10 : 0;
    return { level, label, count, percent };
  });
  return { total, segments };
}
