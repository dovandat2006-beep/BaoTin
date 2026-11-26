import type { ApiClient } from "./client";
import type {
  AnalyticsTrends,
  DailyTrendPoint,
  PageResponse,
  RiskBreakdown,
  TrendTotals
} from "./contracts";
import type { ReportStatus } from "./reports";
import type { RiskLevel } from "../lib/risk";

export type { AnalyticsTrends, DailyTrendPoint, RiskBreakdown, TrendTotals };
export type TrendDays = 7 | 14 | 30;

export type AdminSummary = {
  pendingReview: number;
  needMoreInfo: number;
  resolvedToday: number;
  highRisk: number;
  scansToday: number;
};

export type AnalyticsQueueItem = {
  id: string;
  targetType: string;
  target: string;
  category: string;
  title: string;
  status: ReportStatus;
  riskLevel: RiskLevel | null;
  priority: string;
  submittedAt: string;
  evidenceCount: number;
  moderatorNote: string | null;
};

export type AnalyticsQueuePage = PageResponse<AnalyticsQueueItem>;
export type AnalyticsCounts = { byStatus: Record<string, number>; byPriority: Record<string, number> };

export type QueueQuery = {
  status?: ReportStatus;
  priority?: "LOW" | "MEDIUM" | "HIGH";
  page?: number;
  size?: number;
  sort?: "submittedAt,asc" | "submittedAt,desc";
};

/** Client for the role-protected admin analytics dashboard. */
export function createAnalyticsApi(client: ApiClient) {
  const request = client.request.bind(client);
  return {
    summary: () => request<AdminSummary>("/api/v1/analytics/admin/summary"),
    queue: (query: QueueQuery = {}) => {
      const params = new URLSearchParams({
        page: String(query.page ?? 0),
        size: String(query.size ?? 20),
        sort: query.sort ?? "submittedAt,desc"
      });
      if (query.status) params.set("status", query.status);
      if (query.priority) params.set("priority", query.priority);
      return request<AnalyticsQueuePage>(`/api/v1/analytics/admin/queue?${params.toString()}`);
    },
    counts: () => request<AnalyticsCounts>("/api/v1/analytics/admin/counts"),
    trends: (days: TrendDays = 14) =>
      request<AnalyticsTrends>(`/api/v1/analytics/admin/trends?days=${days}`)
  };
}

export type AnalyticsApi = ReturnType<typeof createAnalyticsApi>;
