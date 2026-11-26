import { historyItems, type HistoryItem } from "../../data/mock";
import type { ModelSignal, PageResponse, ScanApiResponse } from "../contracts";
import type { ScanHistoryClient, ScanHistoryFilter } from "../history";

/**
 * Dữ liệu minh họa cho màn hình Lịch sử khi History API chưa được xác minh.
 *
 * Mỗi bản ghi được suy ra từ `historyItems` (giữ nguyên đối tượng, điểm và mức
 * rủi ro) rồi bổ sung các trường contract còn thiếu để màn hình hiện có render
 * được. Đây KHÔNG phải dữ liệu đồng bộ từ backend và không được dùng làm bằng
 * chứng tích hợp History-Service.
 */

const scannedAtById: Record<string, string> = {
  "scan-01": "2026-10-28T02:42:00.000Z",
  "scan-02": "2026-10-27T14:18:00.000Z",
  "scan-03": "2026-09-24T09:03:00.000Z"
};

const signalsById: Record<string, string[]> = {
  "scan-01": ["LINK_ACTION_REQUEST", "MANY_HOST_HYPHENS", "PRIZE_OR_PROFIT_PROMISE"],
  "scan-02": ["SENSITIVE_CREDENTIAL_REQUEST", "URGENCY_OR_ACCOUNT_THREAT"],
  "scan-03": []
};

function modelFor(item: HistoryItem): ModelSignal | null {
  if (!item.modelVersion) return null;
  const [name = "demo-model", version = "1.0.0"] = item.modelVersion.split(" ");
  return {
    modelName: name,
    modelVersion: version,
    // Clearly demo-only checksum; never presented as a backend artifact.
    modelChecksum: `demo-${item.id}`,
    label: item.snapshotLevel === "LOW" ? "SAFE" : item.snapshotLevel === "MEDIUM" ? "SUSPICIOUS" : "SCAM",
    // Derived from the existing demo score instead of inventing a second number.
    confidence: item.snapshotScore / 100,
    riskProbability: item.snapshotScore / 100,
    probabilities: {},
    latencyMs: 0
  };
}

function toSnapshot(item: HistoryItem): ScanApiResponse | null {
  // The scan contract only exposes TEXT/URL; other HistoryItem demo types have
  // no server equivalent yet, so they are intentionally left out.
  if (item.type !== "SMS" && item.type !== "URL") return null;
  const type = item.type === "SMS" ? "TEXT" : "URL";
  const model = modelFor(item);
  const rules = { score: item.snapshotScore, signals: signalsById[item.id] ?? [] };
  const risk = { score: item.snapshotScore, level: item.snapshotLevel };
  return {
    scanId: item.id,
    type,
    targetId: null,
    preview: item.target,
    risk,
    textModel: type === "TEXT" ? model : null,
    urlModel: type === "URL" ? model : null,
    rules,
    urls: type === "URL" ? [{
      url: item.target,
      targetId: `mock-${item.id}`,
      model,
      rules,
      reputationScore: item.currentScore ?? item.snapshotScore,
      risk,
      aiAvailable: Boolean(model)
    }] : [],
    aiAvailable: Boolean(model),
    degraded: false,
    warnings: [],
    scannedAt: scannedAtById[item.id] ?? "2026-10-28T00:00:00.000Z"
  };
}

export const mockScanHistory: ScanApiResponse[] = historyItems
  .map(toSnapshot)
  .filter((item): item is ScanApiResponse => item !== null);

function copySnapshot(item: ScanApiResponse): ScanApiResponse {
  return {
    ...item,
    risk: { ...item.risk },
    rules: { score: item.rules.score, signals: [...item.rules.signals] },
    urls: item.urls.map((url) => ({ ...url }))
  };
}

/**
 * Stateful in-memory client. Each factory owns its own list so tests and Future
 * screens cannot leak deletions between instances.
 */
export function createMockScanHistory(seed: ScanApiResponse[] = mockScanHistory): ScanHistoryClient {
  let items = seed.map(copySnapshot);

  return {
    async list(page, size, filters: ScanHistoryFilter = {}): Promise<PageResponse<ScanApiResponse>> {
      const safePage = Math.max(0, Math.trunc(page) || 0);
      const safeSize = size > 0 ? Math.trunc(size) : 20;
      const filtered = filters.type ? items.filter((item) => item.type === filters.type) : items;
      const totalItems = filtered.length;
      const totalPages = totalItems === 0 ? 0 : Math.ceil(totalItems / safeSize);
      const start = safePage * safeSize;
      return {
        items: filtered.slice(start, start + safeSize).map(copySnapshot),
        page: safePage,
        size: safeSize,
        totalItems,
        totalPages
      };
    },

    async remove(id: string): Promise<void> {
      items = items.filter((item) => item.scanId !== id);
    }
  };
}
