import * as DocumentPicker from "expo-document-picker";
import * as Crypto from "expo-crypto";
import { File, Paths } from "expo-file-system";
import * as Sharing from "expo-sharing";
import { Platform } from "react-native";
import type { ApiClient } from "./client";
import type { PageResponse } from "./contracts";

export type ReportStatus = "SUBMITTED" | "PENDING_REVIEW" | "NEED_MORE_INFO" | "VERIFIED" | "REJECTED";
export type ReportCategory = "SUSPICIOUS_MESSAGE" | "PHISHING_URL" | "SPAM" | "OTHER";
export type Evidence = { id: string; originalFilename: string; mimeType: string; sizeBytes: number; sha256: string; createdAt: string };
export type History = { fromStatus: ReportStatus | null; toStatus: ReportStatus; changedByUserId: string; note: string | null; createdAt: string };
export type Report = {
  id: string; scanId?: string | null; reporterUserId: string; threatTargetId: string; targetTypeSnapshot: "URL" | "MESSAGE_TEMPLATE";
  targetDisplaySnapshot: string; category: ReportCategory; title: string; description: string;
  status: ReportStatus; priority: string; moderatorNote: string | null; version: number;
  createdAt: string; updatedAt: string; evidence: Evidence[]; history: History[];
};
export type ReportDraft = { scanId?: string; targetType: "URL" | "MESSAGE_TEMPLATE"; targetValue: string; category: ReportCategory; title: string; description: string };
export type EvidenceFile = DocumentPicker.DocumentPickerAsset;
export const newIdempotencyKey = () => Crypto.randomUUID();
export function createReportApi(client: ApiClient) {
const authenticatedRequest = client.request.bind(client);
return {
  download: (report: Report, evidence: Evidence, review: boolean) => downloadEvidence(client, report, evidence, review),
  mine: (page = 0) => authenticatedRequest<PageResponse<Report>>(`/api/v1/reports/me?page=${page}&size=20`),
  queue: (status: ReportStatus, page = 0) => authenticatedRequest<PageResponse<Report>>(`/api/v1/moderation/reports?status=${status}&page=${page}&size=20`),
  create: (draft: ReportDraft, key: string) => authenticatedRequest<Report>("/api/v1/reports", { method: "POST", headers: { "Idempotency-Key": key }, body: JSON.stringify(draft) }),
  detail: (id: string, review = false) => authenticatedRequest<Report>(`/api/v1/${review ? "moderation/reports" : "reports"}/${encodeURIComponent(id)}`),
  decide: (report: Report, action: "start-review" | "verify" | "reject" | "request-info", note: string) => authenticatedRequest<Report>(`/api/v1/moderation/reports/${report.id}/${action}`, { method: "POST", body: JSON.stringify({ version: report.version, note }) }),
  resubmit: (report: Report, note: string) => authenticatedRequest<Report>(`/api/v1/reports/${report.id}/resubmit`, { method: "POST", body: JSON.stringify({ version: report.version, note }) }),
  upload: async (id: string, asset: EvidenceFile) => {
    try {
      const form = new FormData();
      if (Platform.OS === "web") form.append("file", asset.file ?? await (await fetch(asset.uri)).blob(), asset.name);
      else form.append("file", { uri: asset.uri, name: asset.name, type: asset.mimeType } as unknown as Blob);
      return await authenticatedRequest<Evidence>(`/api/v1/reports/${id}/evidence`, { method: "POST", body: form, timeoutMs: 120000 });
    } finally { releaseEvidenceAsset(asset); }
  },
  remove: (id: string, evidenceId: string) => authenticatedRequest<void>(`/api/v1/reports/${id}/evidence/${evidenceId}`, { method: "DELETE" })
};
}
export type ReportApi = ReturnType<typeof createReportApi>;
export async function pickEvidence() {
  const result = await DocumentPicker.getDocumentAsync({ type: ["image/jpeg", "image/png", "image/webp", "application/pdf"], copyToCacheDirectory: true });
  if (result.canceled) return null;
  const asset = result.assets[0];
  if (!asset || !asset.size || asset.size > 10 * 1024 * 1024) {
    if (asset) releaseEvidenceAsset(asset);
    throw new Error("Mỗi file phải từ 1 byte đến 10 MB.");
  }
  return asset;
}
function releaseEvidenceAsset(asset: EvidenceFile) {
  if (Platform.OS === "web") { if (asset.uri.startsWith("blob:")) URL.revokeObjectURL(asset.uri); }
  else { const file = new File(asset.uri); if (file.exists) file.delete(); }
}
export async function downloadEvidence(client: ApiClient, report: Report, evidence: Evidence, review: boolean) {
  const path = `/api/v1/${review ? "moderation/reports" : "reports"}/${report.id}/evidence/${evidence.id}`;
  const blob = await client.request<Blob>(path, { timeoutMs: 120000 }, "blob");
  if (Platform.OS === "web") {
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a"); link.href = url; link.download = evidence.originalFilename; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } else {
    const file = new File(Paths.cache, `${evidence.id}.evidence`);
    const base64 = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error("Unable to read evidence download"));
      reader.onload = () => { const result = String(reader.result); resolve(result.slice(result.indexOf(",") + 1)); };
      reader.readAsDataURL(blob);
    });
    file.create({ overwrite: true });
    try {
    file.write(base64, { encoding: "base64" });
    if (await Sharing.isAvailableAsync()) await Sharing.shareAsync(file.uri, { mimeType: evidence.mimeType }); else throw new Error("Thiết bị chưa hỗ trợ mở bằng chứng."); }
    finally { if (file.exists) file.delete(); }
  }
}
