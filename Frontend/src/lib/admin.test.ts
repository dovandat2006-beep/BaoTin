import {
  computeAdminQueueMetrics,
  computeRiskBreakdownSegments,
  computeTrendBarHeights,
  countAdminQueueFilters,
  filterAdminQueue,
  formatTrendDayLabel,
  fromAnalyticsQueueItem
} from "./admin";
import { adminQueueDemo, getDemoAnalyticsTrends } from "../data/adminDemo";

describe("computeAdminQueueMetrics", () => {
  it("tổng hợp số liệu từ danh sách báo cáo", () => {
    expect(computeAdminQueueMetrics(adminQueueDemo)).toEqual({
      pendingReview: 5,
      needInfo: 2,
      resolved: 1,
      highRisk: 5
    });
  });

  it("trả về số 0 với danh sách rỗng", () => {
    expect(computeAdminQueueMetrics([])).toEqual({ pendingReview: 0, needInfo: 0, resolved: 0, highRisk: 0 });
  });

  it("không coi mục chưa có điểm uy tín là nguy cơ cao", () => {
    const item = fromAnalyticsQueueItem({
      id: "00000000-0000-0000-0000-000000000001",
      targetType: "MESSAGE_TEMPLATE",
      target: "Tin nhắn",
      category: "SUSPICIOUS_MESSAGE",
      title: "Báo cáo",
      status: "SUBMITTED",
      riskLevel: null,
      priority: "LOW",
      submittedAt: new Date().toISOString(),
      evidenceCount: 0,
      moderatorNote: null
    });
    expect(computeAdminQueueMetrics([item])).toEqual({ pendingReview: 1, needInfo: 0, resolved: 0, highRisk: 0 });
  });
});

describe("fromAnalyticsQueueItem", () => {
  it("chuyển dữ liệu API sang view model và giữ điểm rủi ro", () => {
    const item = fromAnalyticsQueueItem({
      id: "00000000-0000-0000-0000-000000000002",
      targetType: "URL",
      target: "example.com",
      category: "PHISHING_URL",
      title: "Trang giả",
      status: "PENDING_REVIEW",
      riskLevel: "HIGH",
      priority: "HIGH",
      submittedAt: new Date().toISOString(),
      evidenceCount: 3,
      moderatorNote: "Đang xem"
    });
    expect(item).toMatchObject({
      id: "00000000-0000-0000-0000-000000000002",
      targetType: "URL",
      target: "example.com",
      status: "PENDING_REVIEW",
      riskLevel: "HIGH",
      evidenceCount: 3,
      moderatorNote: "Đang xem"
    });
    expect(item.submitted).toBe("Vừa xong");
  });
});

describe("countAdminQueueFilters", () => {
  it("tính số lượng cho từng tab từ counts của service", () => {
    expect(countAdminQueueFilters({
      byStatus: { SUBMITTED: 2, PENDING_REVIEW: 3, NEED_MORE_INFO: 1, VERIFIED: 4, REJECTED: 1 },
      byPriority: { HIGH: 2 }
    })).toEqual({ ALL: 11, REVIEWABLE: 5, NEED_MORE_INFO: 1, RESOLVED: 5 });
  });
});

describe("filterAdminQueue", () => {
  it("lọc đúng theo từng bộ lọc", () => {
    expect(filterAdminQueue(adminQueueDemo, "ALL")).toHaveLength(8);
    expect(filterAdminQueue(adminQueueDemo, "REVIEWABLE")).toHaveLength(5);
    expect(filterAdminQueue(adminQueueDemo, "NEED_MORE_INFO")).toHaveLength(2);
    expect(filterAdminQueue(adminQueueDemo, "RESOLVED")).toHaveLength(1);
  });
});

describe("trend chart helpers", () => {
  it("định dạng ngày YYYY-MM-DD sang DD/MM không lệch múi giờ", () => {
    expect(formatTrendDayLabel("2026-11-03")).toBe("03/11");
    expect(formatTrendDayLabel("2026-11-16")).toBe("16/11");
  });

  it("tính tỷ lệ chiều cao cột và xử lý chuỗi toàn số 0", () => {
    const zeroBars = computeTrendBarHeights([
      {
        date: "2026-11-15",
        totalScans: 0,
        textScans: 0,
        urlScans: 0,
        highRiskScans: 0,
        reportsCreated: 0,
        reportsVerified: 0,
        reportsRejected: 0,
        reportsNeedMoreInfo: 0,
        newThreats: 0
      }
    ]);
    expect(zeroBars.maxScans).toBe(0);
    expect(zeroBars.hasActivity).toBe(false);
    expect(zeroBars.bars[0]?.totalRatio).toBe(0);
    expect(zeroBars.bars[0]?.highRiskRatio).toBe(0);

    const activeBars = computeTrendBarHeights([
      {
        date: "2026-11-15",
        totalScans: 20,
        textScans: 12,
        urlScans: 8,
        highRiskScans: 5,
        reportsCreated: 2,
        reportsVerified: 1,
        reportsRejected: 0,
        reportsNeedMoreInfo: 0,
        newThreats: 1
      },
      {
        date: "2026-11-16",
        totalScans: 40,
        textScans: 25,
        urlScans: 15,
        highRiskScans: 10,
        reportsCreated: 4,
        reportsVerified: 3,
        reportsRejected: 1,
        reportsNeedMoreInfo: 0,
        newThreats: 2
      }
    ]);
    expect(activeBars.maxScans).toBe(40);
    expect(activeBars.hasActivity).toBe(true);
    expect(activeBars.bars[0]).toMatchObject({ totalRatio: 0.5, highRiskRatio: 0.125 });
    expect(activeBars.bars[1]).toMatchObject({ totalRatio: 1, highRiskRatio: 0.25 });
  });

  it("tính phân bố mức rủi ro và tạo dữ liệu demo nhất quán cho 7/14/30 ngày", () => {
    const emptySegments = computeRiskBreakdownSegments({ LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 });
    expect(emptySegments.total).toBe(0);
    expect(emptySegments.segments.every((segment) => segment.percent === 0)).toBe(true);

    for (const days of [7, 14, 30] as const) {
      const demo = getDemoAnalyticsTrends(days);
      expect(demo.days).toBe(days);
      expect(demo.series).toHaveLength(days);
      const sumTotal = demo.series.reduce((sum, point) => sum + point.totalScans, 0);
      const sumHighRisk = demo.series.reduce((sum, point) => sum + point.highRiskScans, 0);
      expect(demo.totals.totalScans).toBe(sumTotal);
      expect(demo.totals.highRiskScans).toBe(sumHighRisk);
      expect(demo.riskBreakdown.HIGH + demo.riskBreakdown.CRITICAL).toBe(demo.totals.highRiskScans);
    }
  });
});
