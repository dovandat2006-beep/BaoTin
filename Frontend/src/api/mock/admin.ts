import { adminQueueDemo, demoScansToday } from "../../data/adminDemo";
import {
  computeAdminQueueMetrics,
  filterAdminQueue,
  type AdminQueueFilter,
  type AdminQueueItem,
  type AdminQueueMetrics
} from "../../lib/admin";

export type AdminDemoData = {
  items: AdminQueueItem[];
  scansToday: number;
  metrics: AdminQueueMetrics;
};

/**
 * Explicit demo-only admin source for `EXPO_PUBLIC_DEMO_MODE=1` UI regression.
 * Live mode uses the Gateway Analytics adapter and never falls back here.
 */
export function getAdminDemo(): AdminDemoData {
  return {
    items: adminQueueDemo,
    scansToday: demoScansToday,
    metrics: computeAdminQueueMetrics(adminQueueDemo)
  };
}

export function filterAdminDemo(filter: AdminQueueFilter, items: AdminQueueItem[] = adminQueueDemo): AdminQueueItem[] {
  return filterAdminQueue(items, filter);
}
