import type { ScanApiResponse } from "../api/contracts";

export const scanTypes = ["URL", "SMS", "PHONE", "BANK_ACCOUNT", "EMAIL", "SOCIAL_ACCOUNT"] as const;

export type ScanType = (typeof scanTypes)[number];
export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export type RiskSignals = {
  ai: number | null;
  rules: number;
  reputation: number | null;
  campaign: number | null;
};

export type ScanResult = {
  id: string;
  type: ScanType;
  score: number;
  level: RiskLevel;
  title: string;
  summary: string;
  indicators: string[];
  actions: string[];
  signals: RiskSignals;
  confidence: number;
  threatTargetId: string | null;
  ephemeral: boolean;
  model: { name: string; version: string } | null;
  scannedAt: string;
  snapshot?: ScanApiResponse;
};

const riskyTokens = ["trúng thưởng", "chuyển khoản", "otp", "xác minh ngay", "bit.ly", "free-gift", "khẩn cấp"];

export const scanTypeLabels: Record<ScanType, string> = {
  URL: "Đường dẫn",
  SMS: "Tin nhắn",
  PHONE: "Số điện thoại",
  BANK_ACCOUNT: "Tài khoản ngân hàng",
  EMAIL: "Email",
  SOCIAL_ACCOUNT: "Tài khoản mạng xã hội"
};

/** Display known scan types in Vietnamese; preserve contract values we do not know yet. */
export function scanTypeLabel(value: string): string {
  const labels = scanTypeLabels as Record<string, string>;
  return Object.prototype.hasOwnProperty.call(labels, value) ? labels[value] ?? value : value;
}

export const scanPlaceholders: Record<ScanType, string> = {
  URL: "https://example.com",
  SMS: "Dán nội dung tin nhắn",
  PHONE: "Ví dụ: 0909 123 456",
  BANK_ACCOUNT: "Nhập số tài khoản",
  EMAIL: "name@example.com",
  SOCIAL_ACCOUNT: "Dán đường dẫn hoặc username"
};

export function riskLevelForScore(score: number): RiskLevel {
  if (score >= 75) return "CRITICAL";
  if (score >= 50) return "HIGH";
  if (score >= 25) return "MEDIUM";
  return "LOW";
}

function buildSummary(type: ScanType, level: RiskLevel): [string, string] {
  const object = type === "URL" ? "URL" : type === "SMS" ? "tin nhắn" : scanTypeLabels[type].toLowerCase();
  if (level === "CRITICAL" || level === "HIGH") {
    const advice = type === "SMS"
      ? "Không mở URL, chia sẻ OTP hoặc chuyển tiền."
      : `Không tương tác hoặc chuyển tiền.`;
    return [`Phát hiện ${object} có dấu hiệu lừa đảo`, advice];
  }
  if (level === "MEDIUM") return [`${scanTypeLabels[type]} có dấu hiệu đáng ngờ`, "Xác minh qua kênh chính thức."];
  return [`Chưa phát hiện dấu hiệu lừa đảo`, `Tiếp tục cảnh giác.`];
}

export function createMockScanResult(type: ScanType, input: string): ScanResult {
  const normalized = input.trim().toLowerCase();
  const hits = riskyTokens.filter((token) => normalized.includes(token));
  const suspiciousUrl = /https?:\/\//.test(normalized) && !/https?:\/\/(www\.)?(google|microsoft|apple)\./.test(normalized);
  const suspiciousIdentity = type !== "SMS" && /(?:vip|support|hotro|official|admin)/.test(normalized);
  const aiApplicable = type === "URL" || type === "SMS";
  const hasReputation = type !== "SMS";
  const ai = aiApplicable ? Math.min(96, 22 + hits.length * 18 + (suspiciousUrl ? 20 : 0)) : null;
  const rules = Math.min(96, 14 + hits.length * 15 + (suspiciousUrl ? 24 : 0) + (suspiciousIdentity ? 18 : 0));
  const reputation = hasReputation ? Math.min(96, 28 + (suspiciousUrl ? 31 : 0) + (suspiciousIdentity ? 20 : 0)) : null;
  const campaign = hasReputation ? Math.min(90, 12 + (suspiciousIdentity ? 38 : 0)) : null;
  const score = type === "SMS"
    ? Math.round((ai ?? 0) * 0.7 + rules * 0.3)
    : type === "URL"
      ? Math.round((reputation ?? 0) * 0.35 + rules * 0.25 + (ai ?? 0) * 0.25 + (campaign ?? 0) * 0.15)
      : Math.round((reputation ?? 0) * 0.55 + rules * 0.2 + (campaign ?? 0) * 0.25);
  const level = riskLevelForScore(score);
  const [title, summary] = buildSummary(type, level);
  const risky = level === "HIGH" || level === "CRITICAL";

  return {
    id: `scan-${Date.now()}`,
    type,
    score,
    level,
    title,
    summary,
    indicators: risky
      ? ["Nguồn chưa được xác minh", "Mẫu hành vi tương đồng báo cáo trước", aiApplicable ? "Mô hình phát hiện nội dung bất thường" : "Uy tín đối tượng đang giảm"]
      : ["Không có yêu cầu dữ liệu nhạy cảm", "Chưa ghi nhận cụm báo cáo đáng kể"],
    actions: risky
      ? ["Không mở liên kết hoặc chuyển tiền", "Liên hệ tổ chức qua kênh chính thức", "Lưu bằng chứng nếu đã tương tác"]
      : ["Tiếp tục cảnh giác với yêu cầu bất thường", "Kiểm tra lại nếu nội dung thay đổi"],
    signals: { ai, rules, reputation, campaign },
    confidence: Math.min(0.97, 0.58 + Math.abs(score - 50) / 150),
    threatTargetId: hasReputation ? `threat-${type.toLowerCase()}-demo` : null,
    ephemeral: type === "SMS",
    model: aiApplicable ? { name: type === "SMS" ? "baotin-sms-phobert" : "baotin-url-canine", version: "1.0.0-demo" } : null,
    scannedAt: new Date().toISOString()
  };
}






