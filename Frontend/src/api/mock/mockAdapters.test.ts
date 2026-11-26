import { presentScan } from "../scan";
import { createApiScanHistoryClient, selectScanHistorySource } from "../history";
import type { BaoTinApi } from "../baotin";
import type { PageResponse, ScanApiResponse } from "../contracts";
import { adminQueueDemo } from "../../data/adminDemo";
import { createMockScanHistory } from "./history";
import { createMockNotifications } from "./notifications";
import { createMockWatchlist } from "./watchlist";
import { createMockReports } from "./reports";
import { filterMockSearch } from "./search";
import { filterAdminDemo, getAdminDemo } from "./admin";

describe("mock scan history", () => {
  it("serves contract-shaped history derived from the existing demo fixtures", async () => {
    const page = await createMockScanHistory().list(0, 20);

    expect(page).toMatchObject({ page: 0, size: 20, totalItems: 3, totalPages: 1 });
    expect(page.items.map((item) => item.scanId)).toEqual(["scan-01", "scan-02", "scan-03"]);
    expect(page.items[0]).toMatchObject({
      type: "URL",
      preview: "quatang-vip-free-gift.site",
      risk: { score: 78, level: "CRITICAL" }
    });
    expect(page.items.every((item) => item.type === "TEXT" || item.type === "URL")).toBe(true);
    expect(page.items.every((item) => typeof item.scannedAt === "string" && item.scannedAt.length > 0)).toBe(true);

    // The existing presentation pipeline must accept the mock snapshots.
    const presented = presentScan(page.items[0]!);
    expect(presented.score).toBe(78);
    expect(presented.level).toBe("CRITICAL");
    expect(presented.indicators.length).toBeGreaterThan(0);
  });

  it("filters by scan type and paginates deterministically", async () => {
    const client = createMockScanHistory();

    const urls = await client.list(0, 20, { type: "URL" });
    expect(urls.items.map((item) => item.scanId)).toEqual(["scan-01", "scan-03"]);

    const secondPage = await client.list(1, 1);
    expect(secondPage).toMatchObject({ page: 1, size: 1, totalItems: 3, totalPages: 3 });
    expect(secondPage.items.map((item) => item.scanId)).toEqual(["scan-02"]);
  });

  it("keeps deletions inside one factory instance", async () => {
    const first = createMockScanHistory();
    const second = createMockScanHistory();

    await first.remove("scan-01");

    expect((await first.list(0, 20)).items.map((item) => item.scanId)).toEqual(["scan-02", "scan-03"]);
    expect((await second.list(0, 20)).items.map((item) => item.scanId)).toEqual(["scan-01", "scan-02", "scan-03"]);
  });

  it("delegates the real history adapter to the existing API client", async () => {
    const page: PageResponse<ScanApiResponse> = { items: [], page: 1, size: 10, totalItems: 0, totalPages: 0 };
    const scanHistory = jest.fn().mockResolvedValue(page);
    const deleteScanHistory = jest.fn().mockResolvedValue(undefined);
    const api = { scanHistory, deleteScanHistory } as unknown as BaoTinApi;
    const client = createApiScanHistoryClient(api);

    await expect(client.list(1, 10, { type: "URL" })).resolves.toBe(page);
    expect(scanHistory).toHaveBeenCalledWith(1, 10, { type: "URL" });

    await client.remove("scan-01");
    expect(deleteScanHistory).toHaveBeenCalledWith("scan-01");
  });

  it("uses the real history adapter only when the capability is enabled", async () => {
    const scanHistory = jest.fn().mockResolvedValue({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    const api = { scanHistory, deleteScanHistory: jest.fn() } as unknown as BaoTinApi;
    const source = selectScanHistorySource({ enabled: true, api, mock: createMockScanHistory() });

    expect(source.demo).toBe(false);
    if (!source.client) throw new Error("Real history adapter is required");
    await source.client.list(0, 20);
    expect(scanHistory).toHaveBeenCalledWith(0, 20, {});
  });

  it("stays unavailable without implicit mock history when a live capability is disabled", () => {
    const scanHistory = jest.fn();
    const api = { scanHistory, deleteScanHistory: jest.fn() } as unknown as BaoTinApi;
    const mock = createMockScanHistory();
    const source = selectScanHistorySource({ enabled: false, api, mock });

    expect(source).toEqual({ client: null, demo: false });
    expect(scanHistory).not.toHaveBeenCalled();
  });

  it("does not invent history when the real API client is absent", () => {
    expect(selectScanHistorySource({ enabled: true, api: null, mock: createMockScanHistory() }))
      .toEqual({ client: null, demo: false });
  });

  it("selects mock history only for explicitly selected demo mode", async () => {
    const mock = createMockScanHistory();
    const source = selectScanHistorySource({ enabled: false, api: null, mock, demo: true });
    expect(source).toEqual({ client: mock, demo: true });
    if (!source.client) throw new Error("Explicit demo history adapter is required");
    await expect(source.client.list(0, 20)).resolves.toMatchObject({ totalItems: 3 });
  });

  it("propagates real API errors rather than replacing them with mock history", async () => {
    const failure = new Error("Live API unavailable");
    const scanHistory = jest.fn().mockRejectedValue(failure);
    const api = { scanHistory, deleteScanHistory: jest.fn() } as unknown as BaoTinApi;
    const mock = createMockScanHistory();
    const mockList = jest.spyOn(mock, "list");
    const source = selectScanHistorySource({ enabled: true, api, mock });
    if (!source.client) throw new Error("Real history adapter is required");
    await expect(source.client.list(0, 20)).rejects.toBe(failure);
    expect(mockList).not.toHaveBeenCalled();
  });
});

describe("mock notifications", () => {
  it("supports read state and preferences per factory", async () => {
    const client = createMockNotifications();

    expect(await client.list()).toHaveLength(3);
    expect((await client.list())[0]).toMatchObject({ id: "notification-01", read: false });

    await client.markRead("notification-01");
    expect((await client.list())[0]!.read).toBe(true);

    await client.markAllRead();
    expect((await client.list()).every((item) => item.read)).toBe(true);

    expect(await client.updatePreference("push", false)).toMatchObject({ push: false });
    expect(await client.preferences()).toMatchObject({ risk: true, report: true, push: false, inApp: true });
  });

  it("does not leak state between factories", async () => {
    const first = createMockNotifications();
    await first.markAllRead();

    const fresh = createMockNotifications();
    expect((await fresh.list()).some((item) => !item.read)).toBe(true);
    expect((await fresh.preferences()).push).toBe(true);
  });
});

describe("mock watchlist", () => {
  it("lists, adds and removes items with per-factory state", async () => {
    const client = createMockWatchlist();
    expect(await client.list()).toHaveLength(2);

    const added = await client.add("https://example.com");
    expect(added).toMatchObject({ target: "https://example.com", score: null, previousScore: null, level: null, type: "URL" });
    expect((await client.list())[0]!.id).toBe(added.id);
    expect(await client.list()).toHaveLength(3);

    await client.remove(added.id);
    expect(await client.list()).toHaveLength(2);

    const fresh = createMockWatchlist();
    expect(await fresh.list()).toHaveLength(2);
  });
});

describe("mock reports", () => {
  it("lists demo reports and prepends a locally created report", async () => {
    const client = createMockReports();
    expect(await client.list()).toHaveLength(3);

    const created = await client.create({
      target: "https://new.example",
      targetType: "URL",
      category: "PHISHING",
      title: "Báo cáo mô phỏng",
      evidenceCount: 2
    });

    expect(created).toMatchObject({
      id: "BT-DEMO-004",
      target: "https://new.example",
      targetType: "URL",
      status: "SUBMITTED",
      priority: "MEDIUM",
      evidenceCount: 2
    });
    expect((await client.list())[0]!.id).toBe("BT-DEMO-004");
    expect((await createMockReports().list())[0]!.id).toBe("BT-240927-018");
  });
});

describe("mock search", () => {
  it("filters by text, type and risk with the same rules as the screen", () => {
    expect(filterMockSearch({ query: "", type: "ALL", risk: "ALL" })).toHaveLength(4);
    expect(filterMockSearch({ query: "SHOP", type: "URL", risk: "CRITICAL" }).map((item) => item.id)).toEqual(["search-01"]);
    expect(filterMockSearch({ query: "", type: "PHONE", risk: "ALL" }).map((item) => item.id)).toEqual(["search-02"]);
    expect(filterMockSearch({ query: "không-tồn-tại", type: "ALL", risk: "ALL" })).toEqual([]);
  });
});

describe("mock admin demo", () => {
  it("returns the existing queue plus derived summary metrics", () => {
    const demo = getAdminDemo();
    expect(demo.items).toBe(adminQueueDemo);
    expect(demo.scansToday).toBe(248);
    expect(demo.metrics).toEqual({ pendingReview: 5, needInfo: 2, resolved: 1, highRisk: 5 });
    expect(filterAdminDemo("REVIEWABLE")).toHaveLength(5);
    expect(filterAdminDemo("NEED_MORE_INFO")).toHaveLength(2);
    expect(filterAdminDemo("RESOLVED")).toHaveLength(1);
  });
});

describe("mock adapter boundary", () => {
  it("never calls fetch", async () => {
    const originalFetch = globalThis.fetch;
    const fetchMock = jest.fn();
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    try {
      await createMockScanHistory().list(0, 20);
      await createMockScanHistory().remove("scan-01");
      const notifications = createMockNotifications();
      await notifications.list();
      await notifications.markRead("notification-01");
      await notifications.markAllRead();
      await notifications.updatePreference("push", false);
      const watchlist = createMockWatchlist();
      await watchlist.list();
      await watchlist.add("https://example.com");
      await watchlist.remove("watch-01");
      const reports = createMockReports();
      await reports.list();
      await reports.create({ target: "https://example.com", targetType: "URL", category: "PHISHING", title: "Demo", evidenceCount: 0 });
      filterMockSearch({ query: "shop", type: "ALL", risk: "ALL" });
      getAdminDemo();
      filterAdminDemo("ALL");
    } finally {
      globalThis.fetch = originalFetch;
    }

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
