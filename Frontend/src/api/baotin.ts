import type { ApiClient } from "./client";
import {
  documentedEndpoints,
  type DeviceApiResource,
  type DeviceRegisterRequest,
  type NotificationApiResource,
  type NotificationPreferences,
  type NotificationPreferencesPatch,
  type PageResponse,
  type ReportApiResource,
  type ReportCreateRequest,
  type ScanApiResponse,
  type ScanRequest,
  type SearchDocument,
  type WatchlistApiResource
} from "./contracts";

function queryString(params: Record<string, string | number | boolean | undefined>) {
  const query = Object.entries(params).filter(([, value]) => value !== undefined).map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`).join("&");
  return query ? `?${query}` : "";
}

export function createBaoTinApi(client: ApiClient) {
  return {
    scan: (request: ScanRequest, signal?: AbortSignal) => client.request<ScanApiResponse>(`${documentedEndpoints.scans}/${request.type === "URL" ? "url" : "text"}`, {
      method: "POST", body: JSON.stringify(request.type === "URL" ? { url: request.content } : { content: request.content }), signal
    }),
    scanHistory: (page = 0, size = 20, filters: { type?: "TEXT" | "URL"; riskLevel?: string } = {}) => client.request<PageResponse<ScanApiResponse>>(`${documentedEndpoints.scanHistory}${queryString({ page, size, sort: "createdAt,desc", ...filters })}`),
    scanHistoryDetail: (id: string) => client.request<ScanApiResponse>(`${documentedEndpoints.scanHistory}/${encodeURIComponent(id)}`),
    deleteScanHistory: (id: string) => client.request<void>(`${documentedEndpoints.scanHistory}/${encodeURIComponent(id)}`, { method: "DELETE" }),
    deleteAllScanHistory: () => client.request<void>(documentedEndpoints.scanHistory, { method: "DELETE" }),
    myReports: (page = 0, size = 20) => client.request<PageResponse<ReportApiResource>>(`${documentedEndpoints.myReports}${queryString({ page, size, sort: "createdAt,desc" })}`),
    createReport: (request: ReportCreateRequest, idempotencyKey: string) => client.request<ReportApiResource>(documentedEndpoints.reports, { method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: JSON.stringify(request) }),
    search: (q: string, type?: string, riskLevel?: string, page = 0, size = 20) => client.request<PageResponse<SearchDocument>>(`${documentedEndpoints.search}${queryString({ q, type, riskLevel, page, size })}`),

    notifications: (page = 0, size = 50, unread = false) =>
      client.request<PageResponse<NotificationApiResource>>(`${documentedEndpoints.notifications}${queryString({ page, size, unread })}`),
    notificationUnreadCount: () => client.request<{ unreadCount: number }>(documentedEndpoints.notificationUnreadCount),
    markNotificationRead: (id: string) => client.request<void>(`${documentedEndpoints.notifications}/${encodeURIComponent(id)}/read`, { method: "PATCH" }),
    markAllNotificationsRead: () => client.request<{ updated: number }>(documentedEndpoints.notificationReadAll, { method: "POST" }),
    deleteNotification: (id: string) => client.request<void>(`${documentedEndpoints.notifications}/${encodeURIComponent(id)}`, { method: "DELETE" }),
    notificationPreferences: () => client.request<NotificationPreferences>(documentedEndpoints.notificationPreferences),
    updateNotificationPreferences: (patch: NotificationPreferencesPatch) =>
      client.request<NotificationPreferences>(documentedEndpoints.notificationPreferences, { method: "PATCH", body: JSON.stringify(patch) }),
    devices: () => client.request<{ items: DeviceApiResource[] }>(documentedEndpoints.notificationDevices),
    registerDevice: (request: DeviceRegisterRequest) =>
      client.request<DeviceApiResource>(documentedEndpoints.notificationDevices, { method: "POST", body: JSON.stringify(request) }),
    deleteDevice: (id: string) =>
      client.request<void>(`${documentedEndpoints.notificationDevices}/${encodeURIComponent(id)}`, { method: "DELETE" }),

    watchlist: (page = 0, size = 50) => client.request<PageResponse<WatchlistApiResource>>(`${documentedEndpoints.watchlist}${queryString({ page, size })}`),
    addWatch: (url: string) => client.request<WatchlistApiResource>(documentedEndpoints.watchlist, { method: "POST", body: JSON.stringify({ url }) }),
    removeWatch: (id: string) => client.request<void>(`${documentedEndpoints.watchlist}/${encodeURIComponent(id)}`, { method: "DELETE" })
  };
}

export type BaoTinApi = ReturnType<typeof createBaoTinApi>;
