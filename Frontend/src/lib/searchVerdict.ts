import type { SearchDocument } from "../api/contracts";

export type SearchVerdict = "Có rủi ro" | "Không có rủi ro" | "Chưa có đánh giá";

/** Known warning/positive risk wins; missing numeric score does not erase a label. */
export function searchVerdict(item: Pick<SearchDocument, "metadata" | "riskScore" | "riskLevel">): SearchVerdict {
  const scored = typeof item.riskScore === "number" && item.riskLevel !== null;
  if (item.metadata.contentWarning === "SUSPICIOUS" || (scored && item.riskLevel !== "LOW")) return "Có rủi ro";
  if (item.metadata.contentWarning === "BENIGN") return "Không có rủi ro";
  if (scored) return item.riskScore! > 0 ? "Có rủi ro" : "Không có rủi ro";
  return "Chưa có đánh giá";
}
