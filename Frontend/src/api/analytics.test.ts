import { createAnalyticsApi } from "./analytics";
import type { ApiClient } from "./client";

describe("Analytics API adapter", () => {
  it("loads the admin summary and counts from the documented paths", async () => {
    const request = jest.fn().mockResolvedValue({});
    const api = createAnalyticsApi({ request } as ApiClient);
    await api.summary();
    await api.counts();
    expect(request).toHaveBeenNthCalledWith(1, "/api/v1/analytics/admin/summary");
    expect(request).toHaveBeenNthCalledWith(2, "/api/v1/analytics/admin/counts");
  });

  it("encodes queue filters and pagination", async () => {
    const request = jest.fn().mockResolvedValue({ items: [] });
    const api = createAnalyticsApi({ request } as ApiClient);
    await api.queue({ status: "SUBMITTED", priority: "HIGH", page: 2, size: 50, sort: "submittedAt,asc" });
    expect(request).toHaveBeenCalledWith(
      "/api/v1/analytics/admin/queue?page=2&size=50&sort=submittedAt%2Casc&status=SUBMITTED&priority=HIGH"
    );
  });

  it("loads the admin trends with default 14 days and explicit window", async () => {
    const request = jest.fn().mockResolvedValue({ series: [] });
    const api = createAnalyticsApi({ request } as ApiClient);
    await api.trends();
    await api.trends(7);
    await api.trends(30);
    expect(request).toHaveBeenNthCalledWith(1, "/api/v1/analytics/admin/trends?days=14");
    expect(request).toHaveBeenNthCalledWith(2, "/api/v1/analytics/admin/trends?days=7");
    expect(request).toHaveBeenNthCalledWith(3, "/api/v1/analytics/admin/trends?days=30");
  });
});
