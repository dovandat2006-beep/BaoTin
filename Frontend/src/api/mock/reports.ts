import { myReports, type ReportRecord } from "../../data/mock";
import type { ScanType } from "../../lib/risk";

export type MockReportInput = {
  target: string;
  targetType: ScanType;
  category: string;
  title: string;
  evidenceCount: number;
};

export type MockReportsClient = {
  snapshot(): ReportRecord[];
  list(): Promise<ReportRecord[]>;
  create(input: MockReportInput): Promise<ReportRecord>;
};

/**
 * Reports demo source. New records stay in this factory's in-memory list and
 * are labelled as submitted; no Report-Service call exists.
 */
export function createMockReports(seed: ReportRecord[] = myReports): MockReportsClient {
  let items = seed.map((item) => ({ ...item }));

  const snapshot = () => items.map((item) => ({ ...item }));

  return {
    snapshot,

    async list(): Promise<ReportRecord[]> {
      return snapshot();
    },

    async create(input: MockReportInput): Promise<ReportRecord> {
      const record: ReportRecord = {
        id: `BT-DEMO-${String(items.length + 1).padStart(3, "0")}`,
        target: input.target,
        targetType: input.targetType,
        category: input.category,
        title: input.title,
        status: "SUBMITTED",
        priority: "MEDIUM",
        updated: "Vừa xong",
        evidenceCount: input.evidenceCount,
        moderatorNote: null
      };
      items = [record, ...items];
      return { ...record };
    }
  };
}
