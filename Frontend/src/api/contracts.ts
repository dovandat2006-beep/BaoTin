import type { RiskLevel, ScanType } from "../lib/risk";

export type ProblemDetails = {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  code: string;
  correlationId: string;
  /** Normalized from the Retry-After response header when present. */
  retryAfterSeconds?: number;
};

export type PageResponse<T> = {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export type SupportedScanType = "SMS" | "URL";
export type ScanRequest = { type: SupportedScanType; content: string };
export type ModelSignal = {
  modelName: string;
  modelVersion: string;
  modelChecksum: string;
  label: string;
  confidence: number;
  riskProbability: number;
  probabilities: Record<string, number>;
  latencyMs: number;
};
export type RuleSignal = { score: number; signals: string[] };
export type UrlScanResult = {
  url: string;
  targetId: string;
  model: ModelSignal | null;
  rules: RuleSignal;
  reputationScore: number | null;
  risk: { score: number; level: RiskLevel };
  aiAvailable: boolean;
};
export type ScanApiResponse = {
  scanId: string;
  type: "TEXT" | "URL";
  targetId: string | null;
  preview: string;
  risk: { score: number; level: RiskLevel };
  textModel: ModelSignal | null;
  urlModel: ModelSignal | null;
  rules: RuleSignal;
  urls: UrlScanResult[];
  aiAvailable: boolean;
  degraded: boolean;
  warnings: string[];
  scannedAt: string;
};

export type ReportCreateRequest = {
  targetType: ScanType;
  targetValue: string;
  category: string;
  title: string;
  description: string;
  occurredAt?: string;
  amountLost?: number;
  currency?: string;
};

export type ReportApiResource = {
  id: string;
  threatTargetId: string;
  targetTypeSnapshot: ScanType;
  targetDisplaySnapshot: string;
  category: string;
  title: string;
  description: string;
  status: "SUBMITTED" | "PENDING_REVIEW" | "NEED_MORE_INFO" | "VERIFIED" | "REJECTED";
  priority: "LOW" | "MEDIUM" | "HIGH";
  moderatorNote: string | null;
  createdAt: string;
  updatedAt: string;
};

export type SearchDocumentType = "URL" | "PHONE" | "BANK_ACCOUNT" | "EMAIL" | "CAMPAIGN" | "OTHER";

export type SearchDocument = {
  content?: string | null;
  id: string;
  documentType: SearchDocumentType;
  referenceId: string;
  title: string;
  displayValue: string;
  riskScore: number | null;
  riskLevel: RiskLevel | null;
  status: string;
  metadata: Record<string, unknown>;
  updatedAt: string;
};

export type NotificationApiResource = {
  id: string;
  type: string;
  title: string;
  body: string;
  data: Record<string, unknown>;
  createdAt: string;
  readAt: string | null;
  read: boolean;
};

export type NotificationPreferences = {
  riskChangeEnabled: boolean;
  reportStatusEnabled: boolean;
  pushEnabled: boolean;
  inAppEnabled: boolean;
  updatedAt: string | null;
};

export type NotificationPreferencesPatch = Partial<Omit<NotificationPreferences, "updatedAt">>;

export type DeviceApiResource = {
  id: string;
  platform: "WEB" | "ANDROID" | "IOS";
  deviceId: string;
  active: boolean;
  createdAt: string;
  lastSeenAt: string;
};

export type DeviceRegisterRequest = {
  platform: "WEB" | "ANDROID" | "IOS";
  token: string;
  deviceId: string;
};

export type WatchlistApiResource = {
  id: string;
  urlTargetId: string;
  url: string;
  host: string;
  score: number | null;
  previousScore: number | null;
  level: RiskLevel | null;
  previousLevel: RiskLevel | null;
  label: string | null;
  createdAt: string;
  updatedAt: string;
};

export type AnalyticsModerationStatus = "SUBMITTED" | "PENDING_REVIEW" | "NEED_MORE_INFO" | "VERIFIED" | "REJECTED";
export type AnalyticsPriority = "LOW" | "MEDIUM" | "HIGH";

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
  status: AnalyticsModerationStatus;
  riskLevel: RiskLevel | null;
  priority: AnalyticsPriority;
  submittedAt: string;
  evidenceCount: number;
  moderatorNote: string | null;
};

export type AnalyticsQueuePage = {
  items: AnalyticsQueueItem[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export type AnalyticsQueueCounts = {
  byStatus: Record<AnalyticsModerationStatus, number>;
  byPriority: Record<AnalyticsPriority, number>;
};

export type TrendTotals = {
  totalScans: number;
  textScans: number;
  urlScans: number;
  highRiskScans: number;
  reportsCreated: number;
  reportsVerified: number;
  reportsRejected: number;
  reportsNeedMoreInfo: number;
  newThreats: number;
};

export type DailyTrendPoint = TrendTotals & {
  date: string;
};

export type RiskBreakdown = Record<RiskLevel, number>;

export type AnalyticsTrends = {
  days: number;
  fromDate: string;
  toDate: string;
  totals: TrendTotals;
  riskBreakdown: RiskBreakdown;
  series: DailyTrendPoint[];
};

export const documentedEndpoints = {
  scans: "/api/v1/scans",
  scanHistory: "/api/v1/scans/history",
  reports: "/api/v1/reports",
  myReports: "/api/v1/reports/me",
  search: "/api/v1/search",
  notifications: "/api/v1/notifications",
  notificationUnreadCount: "/api/v1/notifications/unread-count",
  notificationReadAll: "/api/v1/notifications/read-all",
  notificationPreferences: "/api/v1/notifications/preferences",
  notificationDevices: "/api/v1/notifications/devices",
  watchlist: "/api/v1/watchlist",
  analyticsSummary: "/api/v1/analytics/admin/summary",
  analyticsQueue: "/api/v1/analytics/admin/queue",
  analyticsCounts: "/api/v1/analytics/admin/counts",
  analyticsTrends: "/api/v1/analytics/admin/trends"
} as const;
