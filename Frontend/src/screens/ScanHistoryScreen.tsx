/* Hallmark · personal history list · DESIGN.md Material3 · Roboto/FontAwesome
 * Server snapshots, masking, paging and delete endpoints preserved.
 */
import { faClockRotateLeft } from "@fortawesome/free-solid-svg-icons/faClockRotateLeft";
import { faCircleExclamation } from "@fortawesome/free-solid-svg-icons/faCircleExclamation";
import { useCallback, useEffect, useState } from "react";
import { Text, View } from "react-native";
import { RiskBadge } from "../components/RiskBadge";
import { Alert, Button, EmptyState, ErrorPanel, FilterBar, LoadingPanel } from "../components/ui";
import { presentScan, scanErrorMessage, type ScanApi } from "../api/scan";
import type { PageResponse, ScanApiResponse } from "../api/contracts";
import { RiskResult } from "../components/RiskResult";

function historyPreview(value: string): string {
  return value === "[Nội dung tin nhắn đã ẩn]" ? "Nội dung bản ghi cũ không còn được lưu." : value;
}

export function ScanHistoryScreen({ api, authenticated, onSignIn, onReport, onWatch }: { api: ScanApi; authenticated: boolean; onSignIn: () => void; onReport: () => void; onWatch: () => void }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResponse<ScanApiResponse> | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const [confirmClear, setConfirmClear] = useState(false);
  const [revision, setRevision] = useState(0);
  const [filter, setFilter] = useState<"ALL" | "TEXT" | "URL">("ALL");
  const retry = useCallback(() => setRevision((value) => value + 1), []);

  useEffect(() => {
    if (!authenticated) { setData(null); return; }
    let current = true; setData(null); setExpanded(null); setConfirmRemove(null); setLoading(true); setError(null);
    api.scanHistory(page, 20, { type: filter === "ALL" ? undefined : filter }).then((result) => { if (current) setData(result); })
      .catch((failure) => { if (current) setError(scanErrorMessage(failure)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [api, authenticated, filter, page, revision]);

  const remove = async (id: string) => {
    if (removing) return;
    setRemoving(id); setError(null);
    try {
      await api.deleteScanHistory(id);
      setExpanded(null); setConfirmRemove(null);
      if (data?.items.length === 1 && page > 0) setPage((value) => value - 1); else retry();
    } catch (failure) { setError(scanErrorMessage(failure)); }
    finally { setRemoving(null); }
  };
  const clearAll = async () => {
    if (removing || !authenticated) return;
    setRemoving("all"); setError(null);
    try {
      await api.deleteAllScanHistory(); setData(null); setExpanded(null); setConfirmClear(false); setPage(0); retry();
    } catch (failure) { setError(scanErrorMessage(failure)); }
    finally { setRemoving(null); }
  };

  return <View className="min-w-0 w-full">
    <Text accessibilityRole="header" className="font-display text-[28px] leading-9 text-md-on-surface dark:text-dark-md-on-surface md:text-[32px] md:leading-10">Lịch sử kiểm tra</Text>
    <Text className="mt-2 max-w-[640px] font-sans text-base leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Xem lại kết quả tại thời điểm bạn kiểm tra.</Text>
    {!authenticated ? <View className="mt-6 gap-4"><Alert icon={faCircleExclamation} tone="warning" title="Cần tài khoản đã xác thực" description="Phiên xem thử không có lịch sử trên máy chủ. Quét với tư cách khách vẫn hoạt động và không lưu lịch sử cá nhân." /><Button appearance="material" label="Mở đăng nhập" onPress={onSignIn} variant="outline" /></View>
      : <View className="mt-6">

        <FilterBar appearance="material" label="Loại nội dung" options={[{ value: "ALL", label: "Tất cả" }, { value: "TEXT", label: "Tin nhắn" }, { value: "URL", label: "Đường dẫn" }]} value={filter} onChange={(value) => { setPage(0); setFilter(value); }} />
        {loading ? <LoadingPanel label="Đang tải lịch sử" /> : null}
        {error ? <ErrorPanel description={error} onRetry={retry} /> : null}
        {!loading && data?.items.map((item) => <View key={item.scanId} className="mt-5 border-t border-line pt-4 dark:border-dark-line">
          <View className="flex-row flex-wrap items-start gap-3"><View className="min-w-0 flex-1"><Text className="mb-1 font-medium text-xs text-md-on-surface-variant dark:text-dark-md-on-surface-variant">{item.type === "TEXT" ? "Tin nhắn" : "Đường dẫn"}</Text><Text numberOfLines={2} className="font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface">{historyPreview(item.preview)}</Text><Text className="mt-1 font-sans text-sm text-muted dark:text-dark-muted">{Number.isFinite(new Date(item.scannedAt).getTime()) ? new Date(item.scannedAt).toLocaleString("vi-VN") : "Chưa rõ thời điểm"}</Text></View><RiskBadge level={item.risk.level} /></View>
          <View className="mt-3 flex-row flex-wrap gap-3"><Button appearance="material" aria-expanded={expanded === item.scanId} aria-controls={`history-result-${item.scanId}`} accessibilityState={{ expanded: expanded === item.scanId }} label={expanded === item.scanId ? "Ẩn kết quả" : "Xem kết quả lúc kiểm tra"} variant="ghost" onPress={() => setExpanded(expanded === item.scanId ? null : item.scanId)} /><Button appearance="material" label="Xóa khỏi lịch sử" variant="ghost" disabled={Boolean(removing)} loading={removing === item.scanId} onPress={() => setConfirmRemove(item.scanId)} /></View>
          {confirmRemove === item.scanId ? <View accessibilityRole="alert" className="mt-3 gap-3 rounded-xl bg-md-surface-low p-4 dark:bg-dark-md-surface-low"><Text className="font-sans text-sm leading-6 text-md-on-surface dark:text-dark-md-on-surface">Xóa kết quả kiểm tra này khỏi lịch sử?</Text><View className="flex-row flex-wrap gap-3"><Button appearance="material" label="Xác nhận xóa mục này" variant="destructive" loading={removing === item.scanId} disabled={Boolean(removing)} onPress={() => void remove(item.scanId)} /><Button appearance="material" label="Giữ lại" variant="ghost" disabled={Boolean(removing)} onPress={() => setConfirmRemove(null)} /></View></View> : null}
          {expanded === item.scanId ? <View nativeID={`history-result-${item.scanId}`} className="mt-4 gap-4 rounded-2xl bg-md-surface-low p-4 dark:bg-dark-md-surface-low">
            <RiskResult result={presentScan(item)} onReport={onReport} onWatch={onWatch} />
            {item.type === "TEXT" ? <View className="gap-2 py-3"><Text className="font-medium text-sm text-ink dark:text-dark-ink">Nội dung tin nhắn</Text><Text selectable className="font-sans text-sm leading-6 text-ink dark:text-dark-ink">{historyPreview(item.preview)}</Text><Text className="font-sans text-xs text-muted dark:text-dark-muted">{item.preview === "[Nội dung tin nhắn đã ẩn]" ? "Bản ghi này được tạo trước khi lưu nội dung đã che thông tin nhạy cảm. Quét lại tin nhắn nếu bạn còn bản gốc." : "Thông tin nhạy cảm được thay bằng ***."}</Text></View> : null}
          </View> : null}
        </View>)}
        {!loading && !error && data?.items.length === 0 ? <EmptyState icon={faClockRotateLeft} title={filter === "ALL" ? "Chưa có lịch sử" : "Chưa có kết quả thuộc loại này"} description={filter === "ALL" ? "Kết quả kiểm tra khi đăng nhập sẽ xuất hiện ở đây. Chọn Kiểm tra để bắt đầu." : "Chọn Tất cả để xem các lần kiểm tra thuộc loại khác."} compact align="start" /> : null}
        {data && data.totalPages > 1 ? <View className="mt-6 flex-row flex-wrap items-center gap-3"><Button appearance="material" label="Trang trước" variant="outline" disabled={loading || page === 0} onPress={() => setPage((value) => value - 1)} /><Text className="font-sans text-sm text-muted dark:text-dark-muted">{page + 1} / {data.totalPages}</Text><Button appearance="material" label="Trang sau" variant="outline" disabled={loading || page + 1 >= data.totalPages} onPress={() => setPage((value) => value + 1)} /></View> : null}
        <View className="mt-8 items-start gap-2"><Button appearance="material" label="Xóa toàn bộ lịch sử" variant="ghost" disabled={loading || Boolean(removing)} onPress={() => setConfirmClear(true)} />{confirmClear ? <View className="gap-2"><Text className="font-sans text-sm leading-6 text-ink dark:text-dark-ink">Xóa toàn bộ lịch sử của tài khoản trên máy chủ, không chỉ trang hoặc bộ lọc hiện tại?</Text><Button appearance="material" label="Xác nhận xóa toàn bộ lịch sử" variant="destructive" disabled={Boolean(removing)} onPress={() => void clearAll()} /><Button appearance="material" label="Hủy xóa lịch sử" variant="ghost" disabled={Boolean(removing)} onPress={() => setConfirmClear(false)} /></View> : null}</View>
      </View>}
  </View>;
}
