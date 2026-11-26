import { createBaoTinApi } from "./baotin";
import type { ApiClient } from "./client";

describe("BaoTin API adapter", () => {
  it("clears history and deletes notifications/devices through the Gateway", async () => {
    const request = jest.fn().mockResolvedValue(undefined);
    const api = createBaoTinApi({ request } as ApiClient);
    await api.deleteAllScanHistory(); await api.deleteNotification("notification-1"); await api.deleteDevice("device-1");
    expect(request.mock.calls).toEqual([
      ["/api/v1/scans/history", { method: "DELETE" }],
      ["/api/v1/notifications/notification-1", { method: "DELETE" }],
      ["/api/v1/notifications/devices/device-1", { method: "DELETE" }]
    ]);
  });
  it("uses documented pagination and camelCase sort", async () => {
    const request = jest.fn().mockResolvedValue({ items: [] });
    const api = createBaoTinApi({ request } as ApiClient);
    await api.scanHistory(2, 10);
    expect(request).toHaveBeenCalledWith("/api/v1/scans/history?page=2&size=10&sort=createdAt%2Cdesc");
  });

  it("sends Idempotency-Key when creating a report", async () => {
    const request = jest.fn().mockResolvedValue({});
    const api = createBaoTinApi({ request } as ApiClient);
    await api.createReport({ targetType: "URL", targetValue: "https://example.com", category: "PHISHING", title: "Trang giáº£", description: "Ná»™i dung mĂ´ táº£ bĂ¡o cĂ¡o" }, "report-request-uuid");
    expect(request).toHaveBeenCalledWith("/api/v1/reports", expect.objectContaining({ method: "POST", headers: { "Idempotency-Key": "report-request-uuid" } }));
  });

  it("lists notifications with unread filter and marks one read", async () => {
    const request = jest.fn().mockResolvedValue({ items: [] });
    const api = createBaoTinApi({ request } as ApiClient);
    await api.notifications(0, 50, true);
    await api.markNotificationRead("notification-1");
    await api.markAllNotificationsRead();
    expect(request).toHaveBeenNthCalledWith(1, "/api/v1/notifications?page=0&size=50&unread=true");
    expect(request).toHaveBeenNthCalledWith(2, "/api/v1/notifications/notification-1/read", { method: "PATCH" });
    expect(request).toHaveBeenNthCalledWith(3, "/api/v1/notifications/read-all", { method: "POST" });
  });

  it("patches notification preferences and manages watched URLs", async () => {
    const request = jest.fn().mockResolvedValue({});
    const api = createBaoTinApi({ request } as ApiClient);
    await api.updateNotificationPreferences({ riskChangeEnabled: false, pushEnabled: true });
    await api.addWatch("https://example.com");
    await api.removeWatch("watch-1");
    expect(request).toHaveBeenNthCalledWith(1, "/api/v1/notifications/preferences", {
      method: "PATCH",
      body: JSON.stringify({ riskChangeEnabled: false, pushEnabled: true })
    });
    expect(request).toHaveBeenNthCalledWith(2, "/api/v1/watchlist", {
      method: "POST",
      body: JSON.stringify({ url: "https://example.com" })
    });
    expect(request).toHaveBeenNthCalledWith(3, "/api/v1/watchlist/watch-1", { method: "DELETE" });
  });
});







describe("public search", () => {
  it("encodes bounded query and explicit pagination without private endpoints", async () => {
    const request = jest.fn().mockResolvedValue({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 });
    const api = createBaoTinApi({ request });
    await api.search("lừa đảo", "URL", "HIGH", 1, 20);
    expect(request).toHaveBeenCalledWith("/api/v1/search?q=l%E1%BB%ABa%20%C4%91%E1%BA%A3o&type=URL&riskLevel=HIGH&page=1&size=20");
  });
});
