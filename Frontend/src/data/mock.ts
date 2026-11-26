import type { RiskLevel, ScanType } from "../lib/risk";

export type HistoryItem = {
  id: string;
  type: ScanType;
  target: string;
  date: string;
  snapshotScore: number;
  snapshotLevel: RiskLevel;
  currentScore: number | null;
  currentLevel: RiskLevel | null;
  ephemeral: boolean;
  modelVersion: string | null;
};

export const historyItems: HistoryItem[] = [
  { id: "scan-01", type: "URL", target: "quatang-vip-free-gift.site", date: "Hôm nay, 09:42", snapshotScore: 78, snapshotLevel: "CRITICAL", currentScore: 91, currentLevel: "CRITICAL", ephemeral: false, modelVersion: "baotin-url-canine 1.0.0" },
  { id: "scan-02", type: "SMS", target: "Tài khoản cần xác minh ngay...", date: "Hôm qua, 21:18", snapshotScore: 76, snapshotLevel: "CRITICAL", currentScore: null, currentLevel: null, ephemeral: true, modelVersion: "baotin-sms-phobert 1.0.0" },
  { id: "scan-03", type: "URL", target: "microsoft.com", date: "24/09, 16:03", snapshotScore: 18, snapshotLevel: "LOW", currentScore: 18, currentLevel: "LOW", ephemeral: false, modelVersion: "baotin-url-canine 1.0.0" },
  { id: "scan-04", type: "PHONE", target: "0909 *** 456", date: "23/09, 11:26", snapshotScore: 43, snapshotLevel: "MEDIUM", currentScore: 61, currentLevel: "HIGH", ephemeral: false, modelVersion: null },
  { id: "scan-05", type: "BANK_ACCOUNT", target: "**** **** 8821", date: "22/09, 08:14", snapshotScore: 66, snapshotLevel: "HIGH", currentScore: 72, currentLevel: "HIGH", ephemeral: false, modelVersion: null }
];

export type ReportStatus = "SUBMITTED" | "PENDING_REVIEW" | "NEED_MORE_INFO" | "VERIFIED" | "REJECTED";
export type ReportRecord = {
  id: string;
  target: string;
  targetType: ScanType;
  category: string;
  title: string;
  status: ReportStatus;
  priority: string;
  updated: string;
  evidenceCount: number;
  moderatorNote: string | null;
};

export const myReports: ReportRecord[] = [
  { id: "BT-240927-018", target: "0909 *** 456", targetType: "PHONE" as ScanType, category: "BANK_IMPERSONATION", title: "Mạo danh nhân viên ngân hàng", status: "VERIFIED" as ReportStatus, priority: "HIGH", updated: "12 phút trước", evidenceCount: 3, moderatorNote: "Bằng chứng phù hợp với các báo cáo liên quan." },
  { id: "BT-240926-011", target: "hotro-dinhdanh.click", targetType: "URL" as ScanType, category: "ACCOUNT_THEFT", title: "Trang yêu cầu đăng nhập bất thường", status: "NEED_MORE_INFO" as ReportStatus, priority: "MEDIUM", updated: "46 phút trước", evidenceCount: 1, moderatorNote: "Vui lòng bổ sung ảnh chụp nội dung tin nhắn dẫn tới trang này." },
  { id: "BT-240925-007", target: "**** **** 8821", targetType: "BANK_ACCOUNT" as ScanType, category: "MARKETPLACE_SCAM", title: "Tài khoản nhận tiền mua hàng", status: "PENDING_REVIEW" as ReportStatus, priority: "MEDIUM", updated: "2 giờ trước", evidenceCount: 2, moderatorNote: null }
];

export type WatchItem = { id: string; target: string; score: number | null; previousScore: number | null; level: RiskLevel | null; label: string; type: ScanType; updated: string };

export const watchItems: WatchItem[] = [
  { id: "watch-01", target: "shop-deal-vn.live", score: 82, previousScore: 65, level: "CRITICAL" as RiskLevel, label: "Nguy cơ tăng", type: "URL" as ScanType, updated: "8 phút trước" },
  { id: "watch-02", target: "0888 *** 219", score: 61, previousScore: 57, level: "HIGH" as RiskLevel, label: "Đang theo dõi", type: "PHONE" as ScanType, updated: "1 giờ trước" }
];

export const notifications = [
  { id: "notification-01", type: "RISK_CHANGED", title: "Rủi ro tăng mạnh", body: "shop-deal-vn.live tăng từ 65 lên 82.", time: "8 phút trước", read: false },
  { id: "notification-02", type: "REPORT_REVIEWED", title: "Báo cáo đã được xác minh", body: "BT-240927-018 đã được kiểm duyệt.", time: "12 phút trước", read: false },
  { id: "notification-03", type: "REPORT_INFO_REQUIRED", title: "Cần bổ sung bằng chứng", body: "BT-240926-011 đang chờ ảnh chụp tin nhắn.", time: "46 phút trước", read: true }
];

export const searchResults = [
  { id: "search-01", type: "URL" as ScanType, value: "shop-deal-vn.live", display: "shop-deal-vn.live", score: 82, level: "CRITICAL" as RiskLevel, status: "ACTIVE", reportCount: 14, updated: "8 phút trước" },
  { id: "search-02", type: "PHONE" as ScanType, value: "0888442219", display: "0888 *** 219", score: 61, level: "HIGH" as RiskLevel, status: "ACTIVE", reportCount: 6, updated: "1 giờ trước" },
  { id: "search-03", type: "BANK_ACCOUNT" as ScanType, value: "8821", display: "**** **** 8821", score: 72, level: "HIGH" as RiskLevel, status: "ACTIVE", reportCount: 4, updated: "Hôm qua" },
  { id: "search-04", type: "EMAIL" as ScanType, value: "support@example.com", display: "su***@example.com", score: 21, level: "LOW" as RiskLevel, status: "ACTIVE", reportCount: 0, updated: "3 ngày trước" }
];

export const reportCategories = [
  "FAKE_SHIPPER",
  "BANK_IMPERSONATION",
  "GOV_IMPERSONATION",
  "PHISHING",
  "INVESTMENT_SCAM",
  "JOB_SCAM",
  "PRIZE_SCAM",
  "ACCOUNT_THEFT",
  "SOCIAL_IMPERSONATION",
  "MARKETPLACE_SCAM",
  "OTHER"
] as const;






