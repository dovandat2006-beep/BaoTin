import type { BaoTinApi } from "./baotin";
import type { PageResponse, ScanApiResponse } from "./contracts";

export type ScanHistoryFilter = { type?: "TEXT" | "URL" };

/** Data source used by the History screen; API and mock implementations share it. */
export type ScanHistoryClient = {
  list(page: number, size: number, filters?: ScanHistoryFilter): Promise<PageResponse<ScanApiResponse>>;
  remove(id: string): Promise<void>;
};

/** Real adapter: only reached when the history capability is explicitly verified. */
export function createApiScanHistoryClient(api: BaoTinApi): ScanHistoryClient {
  return {
    list: (page, size, filters = {}) => api.scanHistory(page, size, filters),
    remove: (id) => api.deleteScanHistory(id)
  };
}

export type ScanHistorySource = {
  /** Null means unavailable, never fabricated data in normal API mode. */
  client: ScanHistoryClient | null;
  /** True only when the screen is backed by demo data. */
  demo: boolean;
};

/**
 * Callers must explicitly opt into demo mode. Missing live capability/client
 * stays unavailable; an existing API client's failures are never caught here.
 */
export function selectScanHistorySource({ enabled, api, mock, demo = false }: {
  enabled: boolean;
  api: BaoTinApi | null;
  mock: ScanHistoryClient;
  demo?: boolean;
}): ScanHistorySource {
  if (demo) return { client: mock, demo: true };
  if (enabled && api) return { client: createApiScanHistoryClient(api), demo: false };
  return { client: null, demo: false };
}
