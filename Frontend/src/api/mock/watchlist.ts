import { watchItems, type WatchItem } from "../../data/mock";

export type MockWatchlistClient = {
  snapshot(): WatchItem[];
  list(): Promise<WatchItem[]>;
  add(url: string): Promise<WatchItem>;
  remove(id: string): Promise<void>;
};

/**
 * Watchlist demo source. Adds are local-only and carry no risk score until a
 * real analysis exists; nothing here should be read as a live watchlist record.
 */
export function createMockWatchlist(seed: WatchItem[] = watchItems): MockWatchlistClient {
  let items = seed.map((item) => ({ ...item }));
  let nextId = items.length + 1;

  const snapshot = () => items.map((item) => ({ ...item }));

  return {
    snapshot,

    async list(): Promise<WatchItem[]> {
      return snapshot();
    },

    async add(url: string): Promise<WatchItem> {
      const item: WatchItem = {
        id: `watch-mock-${nextId++}`,
        target: url,
        score: null,
        previousScore: null,
        level: null,
        label: "Đang theo dõi",
        type: "URL",
        updated: "Vừa xong"
      };
      items = [item, ...items];
      return { ...item };
    },

    async remove(id: string): Promise<void> {
      items = items.filter((item) => item.id !== id);
    }
  };
}
