import type { BaoTinApi } from "./baotin";
import { ApiProblem } from "./client";
import type { ScanApiResponse } from "./contracts";
import type { ScanResult } from "../lib/risk";

export type ScanApi = BaoTinApi;

export const ruleLabels: Record<string, string> = {
  SENSITIVE_CREDENTIAL_REQUEST: "Nội dung nhắc đến OTP, mật khẩu hoặc mã xác thực",
  URGENCY_OR_ACCOUNT_THREAT: "Thúc giục hành động hoặc đe dọa khóa tài khoản",
  PRIZE_OR_PROFIT_PROMISE: "Hứa hẹn phần thưởng hoặc lợi nhuận",
  PAYMENT_REQUEST: "Yêu cầu chuyển tiền hoặc đóng phí",
  LINK_ACTION_REQUEST: "Yêu cầu truy cập đường dẫn",
  IP_HOST: "Đường dẫn dùng địa chỉ IP",
  PUNYCODE_HOST: "Tên miền có ký tự được mã hóa",
  USERINFO_IN_URL: "Đường dẫn chứa thông tin đăng nhập",
  LONG_URL: "Đường dẫn dài bất thường",
  COMPLEX_HOST: "Tên miền dài hoặc nhiều cấp",
  MANY_HOST_HYPHENS: "Tên miền chứa nhiều dấu gạch nối",
  HIGH_HOST_DIGIT_RATIO: "Tên miền chứa nhiều chữ số",
  CREDENTIAL_PATH: "Đường dẫn có phần đăng nhập hoặc xác minh"
};

/** Presentation only: scores and risk levels are always the server snapshot. */
export function presentScan(snapshot: ScanApiResponse): ScanResult {
  const type = snapshot.type === "TEXT" ? "SMS" : "URL";
  const model = snapshot.textModel ?? snapshot.urlModel;
  const risky = snapshot.risk.level === "HIGH" || snapshot.risk.level === "CRITICAL";
  const title = risky ? `Phát hiện ${type === "SMS" ? "tin nhắn" : "URL"} có dấu hiệu lừa đảo`
    : snapshot.degraded ? "Chưa đủ thông tin để đánh giá đầy đủ"
    : snapshot.risk.level === "MEDIUM" ? "Nội dung có dấu hiệu đáng ngờ" : "Chưa phát hiện dấu hiệu lừa đảo";
  return {
    id: snapshot.scanId, type, score: snapshot.risk.score, level: snapshot.risk.level, title,
    summary: snapshot.degraded ? "Phân tích chưa đầy đủ. Hãy xác minh qua kênh chính thức trước khi mở liên kết hoặc chuyển tiền."
      : risky ? "Không mở liên kết, chia sẻ OTP hoặc chuyển tiền. Xác minh qua kênh chính thức." : "Kết quả hỗ trợ tham khảo. Tiếp tục cảnh giác với yêu cầu bất thường.",
    indicators: snapshot.rules.signals.map((signal) => ruleLabels[signal] ?? signal), actions: [],
    signals: { ai: model ? model.riskProbability * 100 : null, rules: snapshot.rules.score,
      reputation: snapshot.type === "URL" ? snapshot.urls[0]?.reputationScore ?? null : null, campaign: null },
    confidence: model?.confidence ?? 0, threatTargetId: snapshot.targetId, ephemeral: snapshot.type === "TEXT",
    model: model ? { name: model.modelName, version: model.modelVersion } : null, scannedAt: snapshot.scannedAt,
    snapshot
  };
}

export function scanErrorMessage(error: unknown): string {
  if (error instanceof ApiProblem) {
    if (error.problem.status === 400) return "Nội dung hoặc URL chưa hợp lệ. Kiểm tra lại rồi thử lại.";
    if (error.problem.status === 401) return "Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.";
    if (error.problem.status === 413) return "Nội dung quá dài. Hãy rút ngắn rồi thử lại.";
    if (error.problem.status === 429) return "Bạn đã quét quá nhiều lần hoặc hệ thống đang bận. Vui lòng thử lại sau ít phút.";
    if (error.problem.status === 404) return "Kết quả không còn trong lịch sử của bạn. Hãy tải lại danh sách.";
  }
  return "Chưa kết nối được máy chủ. Kiểm tra kết nối và thử lại.";
}
