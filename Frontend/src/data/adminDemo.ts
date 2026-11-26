import type { AnalyticsTrends, DailyTrendPoint, RiskBreakdown, TrendDays, TrendTotals } from "../api/analytics";
import type { AdminQueueItem } from "../lib/admin";
import type { ReportStatus } from "./mock";
import type { RiskLevel, ScanType } from "../lib/risk";

/**
 * Dữ liệu MINH HỌA cho màn hình Quản trị (Admin Dashboard).
 * Không phải số liệu thật, không chứa dữ liệu cá nhân thật.
 * Chỉ dùng khi chưa cấu hình `EXPO_PUBLIC_API_URL` (mock E2E/preview). API mode
 * đọc Analytics-Service qua `src/api/analytics.ts`.
 */

/** Tổng số lượt kiểm tra trong ngày — chỉ là số minh họa. */
export const demoScansToday = 248;

const status = (value: ReportStatus): ReportStatus => value;
const level = (value: RiskLevel): RiskLevel => value;
const type = (value: ScanType): ScanType => value;

export const adminQueueDemo: AdminQueueItem[] = [
  {
    id: "BT-260115-031",
    targetType: type("URL"),
    target: "shop-deal-vn.live",
    category: "MARKETPLACE_SCAM",
    title: "Sàn rao bán giá rẻ, yêu cầu chuyển khoản trực tiếp",
    status: status("PENDING_REVIEW"),
    riskLevel: level("CRITICAL"),
    submitted: "8 phút trước",
    evidenceCount: 4,
    moderatorNote: null
  },
  {
    id: "BT-260115-030",
    targetType: type("PHONE"),
    target: "0888 *** 219",
    category: "BANK_IMPERSONATION",
    title: "Gọi tự xưng nhân viên ngân hàng, yêu cầu cung cấp OTP",
    status: status("PENDING_REVIEW"),
    riskLevel: level("HIGH"),
    submitted: "24 phút trước",
    evidenceCount: 2,
    moderatorNote: null
  },
  {
    id: "BT-260115-029",
    targetType: type("SMS"),
    target: "Trúng thưởng… xác minh ngay tại bit.ly/…",
    category: "PRIZE_SCAM",
    title: "Tin nhắn trúng thưởng kèm đường dẫn rút gọn",
    status: status("SUBMITTED"),
    riskLevel: level("HIGH"),
    submitted: "41 phút trước",
    evidenceCount: 1,
    moderatorNote: null
  },
  {
    id: "BT-260115-028",
    targetType: type("BANK_ACCOUNT"),
    target: "**** **** 8821",
    category: "MARKETPLACE_SCAM",
    title: "Tài khoản nhận tiền mua hàng, nhiều khiếu nại",
    status: status("NEED_MORE_INFO"),
    riskLevel: level("MEDIUM"),
    submitted: "1 giờ trước",
    evidenceCount: 2,
    moderatorNote: "Cần ảnh chụp nội dung thỏa thuận và biên lai chuyển tiền."
  },
  {
    id: "BT-260115-027",
    targetType: type("URL"),
    target: "hotro-dinhdanh.click",
    category: "ACCOUNT_THEFT",
    title: "Trang giả mạo trang đăng nhập, thu thập mật khẩu",
    status: status("PENDING_REVIEW"),
    riskLevel: level("MEDIUM"),
    submitted: "2 giờ trước",
    evidenceCount: 3,
    moderatorNote: null
  },
  {
    id: "BT-260115-026",
    targetType: type("SOCIAL_ACCOUNT"),
    target: "nguoi***.official",
    category: "SOCIAL_IMPERSONATION",
    title: "Tài khoản tự xưng nghệ sĩ, kêu gọi đầu tư",
    status: status("SUBMITTED"),
    riskLevel: level("LOW"),
    submitted: "3 giờ trước",
    evidenceCount: 1,
    moderatorNote: null
  },
  {
    id: "BT-260115-025",
    targetType: type("EMAIL"),
    target: "su***@example.com",
    category: "PHISHING",
    title: "Email giả mạo nhà cung cấp dịch vụ, đính kèm tệp lạ",
    status: status("NEED_MORE_INFO"),
    riskLevel: level("HIGH"),
    submitted: "Hôm qua, 17:20",
    evidenceCount: 2,
    moderatorNote: "Cần ảnh chụp toàn bộ header email."
  },
  {
    id: "BT-260114-024",
    targetType: type("URL"),
    target: "quatang-vip-free-gift.site",
    category: "PRIZE_SCAM",
    title: "Trang tặng quà giả, đòi phí vận chuyển",
    status: status("VERIFIED"),
    riskLevel: level("CRITICAL"),
    submitted: "Hôm qua, 09:15",
    evidenceCount: 5,
    moderatorNote: "Đã xác minh và gửi cảnh báo cộng đồng."
  }
];

function formatUtcDateOffset(anchorIso: string, offsetDays: number): string {
  const [year, month, day] = anchorIso.split("-").map(Number);
  const utc = new Date(Date.UTC(year ?? 2026, (month ?? 11) - 1, (day ?? 16) + offsetDays));
  const y = String(utc.getUTCFullYear()).padStart(4, "0");
  const m = String(utc.getUTCMonth() + 1).padStart(2, "0");
  const d = String(utc.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

/** Chuỗi xu hướng minh họa nhất quán cho chế độ xem trước khi chưa cấu hình API. */
export function getDemoAnalyticsTrends(days: TrendDays = 14): AnalyticsTrends {
  const toDate = "2026-11-16";
  const fromDate = formatUtcDateOffset(toDate, -(days - 1));
  const series: DailyTrendPoint[] = [];
  const riskBreakdown: RiskBreakdown = { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 };
  const totals: TrendTotals = {
    totalScans: 0,
    textScans: 0,
    urlScans: 0,
    highRiskScans: 0,
    reportsCreated: 0,
    reportsVerified: 0,
    reportsRejected: 0,
    reportsNeedMoreInfo: 0,
    newThreats: 0
  };

  for (let index = 0; index < days; index++) {
    const date = formatUtcDateOffset(fromDate, index);
    const wave = (index * 7 + 3) % 11;
    const textScans = 14 + wave * 2;
    const urlScans = 9 + ((index * 5 + 2) % 9);
    const totalScans = textScans + urlScans;
    const criticalScans = 1 + (index % 3);
    const highScans = 3 + (index % 4);
    const mediumScans = 6 + (index % 5);
    const lowScans = Math.max(0, totalScans - criticalScans - highScans - mediumScans);
    const highRiskScans = highScans + criticalScans;
    const reportsCreated = 2 + (index % 4);
    const reportsVerified = Math.max(1, reportsCreated - 1);
    const reportsRejected = index % 3 === 0 ? 1 : 0;
    const reportsNeedMoreInfo = index % 4 === 0 ? 1 : 0;
    const newThreats = 1 + (index % 3);

    series.push({
      date,
      totalScans,
      textScans,
      urlScans,
      highRiskScans,
      reportsCreated,
      reportsVerified,
      reportsRejected,
      reportsNeedMoreInfo,
      newThreats
    });

    totals.totalScans += totalScans;
    totals.textScans += textScans;
    totals.urlScans += urlScans;
    totals.highRiskScans += highRiskScans;
    totals.reportsCreated += reportsCreated;
    totals.reportsVerified += reportsVerified;
    totals.reportsRejected += reportsRejected;
    totals.reportsNeedMoreInfo += reportsNeedMoreInfo;
    totals.newThreats += newThreats;

    riskBreakdown.LOW += lowScans;
    riskBreakdown.MEDIUM += mediumScans;
    riskBreakdown.HIGH += highScans;
    riskBreakdown.CRITICAL += criticalScans;
  }

  return {
    days,
    fromDate,
    toDate,
    totals,
    riskBreakdown,
    series
  };
}
