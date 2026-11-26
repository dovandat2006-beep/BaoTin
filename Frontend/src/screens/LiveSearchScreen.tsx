/* Hallmark · lookup workbench · DESIGN.md Material3 · Roboto/FontAwesome
 * Query, filters, verdict mapping and Gateway transport preserved.
 */
import { faMagnifyingGlass } from "@fortawesome/free-solid-svg-icons/faMagnifyingGlass";
import { useEffect, useState } from "react";
import { ActivityIndicator, Text, View } from "react-native";
import type { BaoTinApi } from "../api/baotin";
import type { PageResponse, SearchDocument, SearchDocumentType } from "../api/contracts";
import { RiskBadge } from "../components/RiskBadge";
import { Button, EmptyState, FilterBar, Input } from "../components/ui";
import { searchVerdict } from "../lib/searchVerdict";
import type { RiskLevel } from "../lib/risk";

const documentLabels: Record<SearchDocumentType, string> = {
  URL: "Đường dẫn", PHONE: "Điện thoại", BANK_ACCOUNT: "Tài khoản ngân hàng",
  EMAIL: "Email", CAMPAIGN: "Chiến dịch", OTHER: "Đối tượng khác"
};
const typeOptions = [
  { value: "ALL", label: "Tất cả" }, { value: "URL", label: "Đường dẫn" },
  { value: "PHONE", label: "Điện thoại" }, { value: "BANK_ACCOUNT", label: "Tài khoản ngân hàng" },
  { value: "EMAIL", label: "Email" }, { value: "CAMPAIGN", label: "Chiến dịch" },
  { value: "OTHER", label: "Khác" }
] as const;

export function LiveSearchScreen({ api }: { api: Pick<BaoTinApi, "search"> }) {
  const [query, setQuery] = useState("");
  const [type, setType] = useState<"ALL" | SearchDocumentType>("ALL");
  const [risk, setRisk] = useState<"ALL" | RiskLevel>("ALL");
  const [page, setPage] = useState(0);
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<PageResponse<SearchDocument> | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const term = query.trim();
  useEffect(() => {
    let active = true;
    setResult(null); setError(false);
    if (term.length < 2 || term.length > 254) { setLoading(false); return; }
    setLoading(true);
    const timer = setTimeout(() => {
      api.search(term, type === "ALL" ? undefined : type, risk === "ALL" ? undefined : risk, page, 20)
        .then((data) => { if (active) setResult(data); })
        .catch(() => { if (active) setError(true); })
        .finally(() => { if (active) setLoading(false); });
    }, 350);
    return () => { active = false; clearTimeout(timer); };
  }, [api, term, type, risk, page, retry]);

  return (
    <View className="min-w-0 w-full">
      <Text accessibilityRole="header" className="font-display text-[28px] leading-9 text-md-on-surface dark:text-dark-md-on-surface md:text-[32px] md:leading-10">Tra cứu</Text>
      <Text className="mt-2 max-w-[640px] font-sans text-base leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Tìm đối tượng hoặc chiến dịch và xem thông tin đã công khai.</Text>
      <View className="mt-6 gap-4">
        <Input appearance="material" label="Đối tượng cần tra cứu" accessibilityLabel="Đối tượng cần tra cứu" value={query} maxLength={254} onChangeText={(value) => { setQuery(value); setPage(0); }} placeholder="Tên miền, số điện thoại, email hoặc từ khóa…" autoCapitalize="none" autoCorrect={false} />
        <FilterBar appearance="material" label="Loại kết quả" options={typeOptions} value={type} onChange={(value) => { setType(value); setPage(0); }} />
        <FilterBar appearance="material" label="Mức rủi ro" options={([
          { value: "ALL", label: "Mọi mức" }, { value: "LOW", label: "Thấp" },
          { value: "MEDIUM", label: "Trung bình" }, { value: "HIGH", label: "Cao" },
          { value: "CRITICAL", label: "Rất cao" }
        ] as const)} value={risk} onChange={(value) => { setRisk(value); setPage(0); }} />
      </View>
      <View className="mt-6" accessibilityLiveRegion="polite">
        {term.length < 2 ? <EmptyState compact align="start" icon={faMagnifyingGlass} title="Bạn muốn tra cứu gì?" description="Nhập ít nhất 2 ký tự để bắt đầu." /> : loading ? (
          <View className="flex-row items-center gap-3 py-8"><ActivityIndicator accessibilityLabel="Đang tìm kiếm" /><Text className="font-sans text-muted dark:text-dark-muted">Đang tìm kiếm…</Text></View>
        ) : error ? <EmptyState compact align="start" icon={faMagnifyingGlass} title="Tra cứu đang gián đoạn" description="Hãy thử lại sau ít phút." action={{ appearance: "material", variant: "outline", label: "Thử lại", onPress: () => setRetry((value) => value + 1) }} /> : result ? (
          <View>
            <Text className="mb-4 font-sans text-sm text-muted dark:text-dark-muted">{result.totalItems} kết quả</Text>
            {result.items.map((item) => (
              <View key={item.id} className="min-w-0 flex-col-reverse gap-4 border-b border-md-outline-variant py-5 dark:border-dark-md-outline-variant md:flex-row md:items-start">
                <View className="min-w-0 flex-1">
                  <Text accessibilityRole="header" aria-level={2} className="font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface" selectable>{item.displayValue}</Text>
                  <Text className="mt-1 font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">{documentLabels[item.documentType] || "Đối tượng"}</Text>
                  {item.content ? <Text selectable className="mt-2 font-sans text-sm leading-6 text-ink dark:text-dark-ink">{item.content}</Text> : null}
                  {item.title !== item.displayValue && !(item.content && item.content.startsWith(item.title)) ? <Text className="mt-2 font-sans text-sm text-ink dark:text-dark-ink">{item.title}</Text> : null}
                  {typeof item.metadata.bankCode === "string" ? <Text className="mt-1 font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Ngân hàng: {item.metadata.bankCode}</Text> : null}
                  <Text className="mt-2 font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Cập nhật {Number.isFinite(new Date(item.updatedAt).getTime()) ? new Date(item.updatedAt).toLocaleString("vi-VN") : "chưa rõ thời điểm"}</Text>
                </View>
                <View className="min-w-0 gap-2 md:max-w-[240px] md:items-end">
                  <Text className={searchVerdict(item) === "Có rủi ro" ? "font-medium text-base leading-6 text-md-error dark:text-dark-md-error" : "font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface"}>{searchVerdict(item)}</Text>
                  {item.riskLevel !== null && item.riskScore !== null ? <View className="flex-row items-center gap-3"><Text className="font-medium text-xl text-ink dark:text-dark-ink">{item.riskScore}/100</Text><RiskBadge level={item.riskLevel} /></View> : null}
                </View>
              </View>
            ))}
            {result.items.length === 0 ? <EmptyState compact align="start" icon={faMagnifyingGlass} title="Chưa tìm thấy kết quả" description="Thử từ khóa khác hoặc bỏ bớt bộ lọc. Không có kết quả không có nghĩa là an toàn." /> : null}
            {result.totalPages > 1 ? <View className="mt-6 flex-row flex-wrap items-center gap-3">
              <Button appearance="material" label="Trang trước" variant="outline" disabled={page === 0 || loading} onPress={() => setPage((value) => value - 1)} />
              <Text className="font-sans text-sm text-muted dark:text-dark-muted">{page + 1}/{result.totalPages}</Text>
              <Button appearance="material" label="Trang sau" variant="outline" disabled={page + 1 >= result.totalPages || loading} onPress={() => setPage((value) => value + 1)} />
            </View> : null}
          </View>
        ) : null}
      </View>
    </View>
  );
}
