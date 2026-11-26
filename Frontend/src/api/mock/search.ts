import { searchResults } from "../../data/mock";
import type { RiskLevel, ScanType } from "../../lib/risk";

export type MockSearchItem = (typeof searchResults)[number];

export type MockSearchFilter = {
  query: string;
  type: "ALL" | ScanType;
  risk: "ALL" | RiskLevel;
};

/**
 * Local-only search over the existing demo documents. This mirrors the filters
 * currently exposed by SearchScreen; it does not call Search-Service.
 */
export function filterMockSearch(
  { query, type, risk }: MockSearchFilter,
  items: MockSearchItem[] = searchResults
): MockSearchItem[] {
  const term = query.trim().toLowerCase();
  return items.filter((item) => {
    const matchesText = !term
      || item.value.toLowerCase().includes(term)
      || item.display.toLowerCase().includes(term);
    return matchesText
      && (type === "ALL" || item.type === type)
      && (risk === "ALL" || item.level === risk);
  });
}
