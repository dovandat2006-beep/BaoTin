/* Hallmark · reports operational list and focused sheets · DESIGN.md Material3
 * Live creation/evidence/moderation handlers and state transitions retained.
 */
import { useEffect, useRef, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, Text, View } from "react-native";
import { faCircleInfo } from "@fortawesome/free-solid-svg-icons/faCircleInfo";
import { faFileCircleCheck } from "@fortawesome/free-solid-svg-icons/faFileCircleCheck";
import { Alert, Badge, Button, EmptyState, FilterBar, Input, Sheet } from "../components/ui";
import type { AuthSession } from "../lib/auth";
import { newIdempotencyKey, pickEvidence, type ReportApi, type Report, type ReportCategory, type ReportDraft, type ReportStatus } from "../api/reports";
import type { PageResponse } from "../api/contracts";

const statuses: Record<ReportStatus, string> = { SUBMITTED: "Đã gửi", PENDING_REVIEW: "Đang duyệt", NEED_MORE_INFO: "Cần bổ sung", VERIFIED: "Đã xác minh", REJECTED: "Không chấp nhận" };
const categories: Record<ReportCategory, string> = { SUSPICIOUS_MESSAGE: "Tin nhắn đáng ngờ", PHISHING_URL: "Đường dẫn giả mạo", SPAM: "Tin nhắn rác", OTHER: "Khác" };
const blankDraft: ReportDraft = { targetType: "URL", targetValue: "", category: "PHISHING_URL", title: "", description: "" };
const date = (value: string) => Number.isFinite(new Date(value).getTime()) ? new Date(value).toLocaleString("vi-VN") : "Chưa rõ thời điểm";

export type ReportTarget = Pick<ReportDraft, "targetType" | "targetValue" | "scanId">;
export function ReportWorkspace({ session, api: reportApi, initialTarget, onTargetConsumed, initialReview = false, pinnedReview = false, onChanged, refreshToken = 0 }: { session: AuthSession; api: ReportApi; initialTarget?: ReportTarget | null; onTargetConsumed?: () => void; initialReview?: boolean; pinnedReview?: boolean; onChanged?: () => void; refreshToken?: number }) {
  const [review, setReview] = useState(initialReview && (session.role === "ADMIN" || session.role === "MODERATOR"));
  const [status, setStatus] = useState<ReportStatus>("SUBMITTED");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResponse<Report> | null>(null);
  const [selected, setSelected] = useState<Report | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [listRefreshFailed, setListRefreshFailed] = useState(false);
  const [touched, setTouched] = useState({ target: false, title: false, description: false });
  const [notice, setNotice] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const [composer, setComposer] = useState(false);
  const [draft, setDraft] = useState<ReportDraft>(blankDraft);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const submission = useRef<{ signature: string; key: string } | null>(null);
  const generation = useRef(0);
  useEffect(() => {
    if (!initialTarget) return;
    setDraft({ ...blankDraft, ...initialTarget, category: initialTarget.targetType === "URL" ? "PHISHING_URL" : "SUSPICIOUS_MESSAGE" });
    setComposer(true);
    onTargetConsumed?.();
  }, [initialTarget, onTargetConsumed]);
  const isModerator = session.role === "MODERATOR" || session.role === "ADMIN";
  const editable = !review && selected && ["SUBMITTED", "NEED_MORE_INFO"].includes(selected.status);

  async function load() {
    const request = ++generation.current;
    setLoading(true); setError(null);
    try { const result = review ? await reportApi.queue(status, page) : await reportApi.mine(page); if (request === generation.current) { setData(result); setListRefreshFailed(false); } }
    catch (e) { if (request === generation.current) { setData(null); setError(message(e)); } }
    finally { if (request === generation.current) setLoading(false); }
  }
  useEffect(() => { setData(null); setSelected(null); setNote(""); setNotice(null); void load(); return () => { generation.current++; }; }, [review, status, page, session]);
  useEffect(() => { if (refreshToken > 0) void load(); }, [refreshToken]);
  async function act(operation: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError(null); setNotice(null);
    try { await operation(); }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function refreshDetail(id: string) {
    const result = await reportApi.detail(id, review);setSelected(result);
    setData(current => current ? { ...current, items: current.items.map(item => item.id === id ? result : item) } : current);
    return result;
  }
  async function create() {
    const input = { ...draft, title: draft.title.trim(), description: draft.description.trim(), targetValue: draft.targetValue };
    const signature = JSON.stringify(input);
    if (submission.current?.signature !== signature) submission.current = { signature, key: newIdempotencyKey() };
    const result = await reportApi.create(input, submission.current.key);
    submission.current = null; setComposer(false); setDraft(blankDraft); setTouched({ target: false, title: false, description: false }); setSelected(result);
    setNote(""); setNotice("Đã gửi báo cáo. Bạn có thể thêm bằng chứng bên dưới.");
    try { const resultPage = await reportApi.mine(page); setData(resultPage); setListRefreshFailed(false); }
    catch { setData(null); setListRefreshFailed(true); setNotice("Báo cáo đã gửi thành công. Danh sách chưa cập nhật; bạn vẫn có thể xem hồ sơ và thêm bằng chứng tại đây."); }
  }
  async function decide(action: "start-review" | "verify" | "reject" | "request-info") {
    if (!selected) return;
    try { const result = await reportApi.decide(selected, action, note.trim() || "Bắt đầu kiểm duyệt"); setSelected(result); setNote(""); setNotice("Đã cập nhật trạng thái báo cáo."); }
    catch (e) { await refreshDetail(selected.id).catch(() => {}); throw e; }
    await load(); onChanged?.();
  }
  const validDraft = draft.targetValue.trim().length > 0 && draft.title.trim().length >= 6 && draft.description.trim().length >= 16;
  return <View className="min-w-0 w-full">
    <View className="gap-4 md:flex-row md:items-center md:justify-between">
      <View className="min-w-0 flex-1"><Text accessibilityRole="header" className="font-display text-[28px] leading-9 text-md-on-surface dark:text-dark-md-on-surface md:text-[32px] md:leading-10">{review ? "Kiểm duyệt báo cáo" : "Báo cáo của tôi"}</Text><Text className="mt-2 font-sans text-sm leading-6 text-muted dark:text-dark-muted">{review ? "Đọc bằng chứng, ghi nhận lý do và quyết định từng báo cáo." : "Gửi thông tin, bổ sung bằng chứng và theo dõi kết quả xử lý."}</Text></View>
      {!review ? <Button appearance="material" label="Gửi báo cáo" onPress={() => { setError(null); setComposer(true); }} /> : null}
    </View>
    <View className="mt-5 flex-row flex-wrap items-center gap-3"><Button appearance="material" label="Làm mới báo cáo" variant="ghost" disabled={busy || loading} onPress={() => void act(async () => { await load(); onChanged?.(); })} />{data ? <Text accessibilityLiveRegion="polite" className="font-sans text-sm text-muted dark:text-dark-muted">{data.totalItems} báo cáo trong danh sách này</Text> : null}</View>
    {isModerator && !pinnedReview ? <View className="mt-5"><FilterBar appearance="material" label="Không gian báo cáo" value={review ? "review" : "mine"} options={[{ value: "mine", label: "Của tôi" }, { value: "review", label: "Kiểm duyệt" }]} onChange={(v) => { setPage(0); setReview(v === "review"); }} /></View> : null}
    {review ? <View className="mt-5"><FilterBar appearance="material" label="Trạng thái kiểm duyệt" value={status} options={Object.entries(statuses).map(([value, label]) => ({ value: value as ReportStatus, label }))} onChange={(value) => { setPage(0); setStatus(value); }} /></View> : null}
    {error && !selected && !composer ? <View className="mt-4" accessibilityRole="alert"><Alert icon={faCircleInfo} tone="destructive" description={error} /><Button appearance="material" label="Tải lại" variant="ghost" onPress={() => void act(load)} disabled={busy} /></View> : null}
    {notice && !selected ? <View className="mt-4" accessibilityLiveRegion="polite"><Alert icon={faCircleInfo} tone="safe" description={notice} /></View> : null}
    {loading ? <View className="py-8" accessibilityLabel="Đang tải báo cáo"><ActivityIndicator /></View> : null}
    {!loading && data?.items.length === 0 ? <View className="mt-5"><EmptyState compact align="start" icon={faFileCircleCheck} title={review ? "Không có báo cáo trong hàng đợi này" : "Chưa có báo cáo"} description={review ? "Chọn trạng thái khác hoặc tải lại để cập nhật." : "Gửi báo cáo khi bạn gặp URL hoặc tin nhắn đáng ngờ."} /></View> : null}
    <View className="mt-5">
      {data?.items.map((report) => <Pressable key={report.id} accessibilityRole="button" accessibilityLabel={`Xem báo cáo ${report.title}`} accessibilityState={{ selected: selected?.id === report.id, disabled: busy }} disabled={busy} onPress={() => void act(async () => { await refreshDetail(report.id); setNote(""); setConfirmDelete(null); })} className="min-w-0 rounded-xl border-b border-md-outline-variant px-2 py-5 hover:bg-md-surface-low active:opacity-75 focus-visible:ring-2 focus-visible:ring-md-primary dark:border-dark-md-outline-variant dark:hover:bg-dark-md-surface-low dark:focus-visible:ring-dark-md-primary">
        <View className="min-w-0 flex-row flex-wrap items-center gap-2"><Text className="min-w-0 flex-1 font-medium text-base text-ink dark:text-dark-ink">{report.title}</Text><Badge label={statuses[report.status]} variant={report.status === "VERIFIED" ? "safe" : report.status === "NEED_MORE_INFO" ? "destructive" : "neutral"} /></View>
        <Text className="mt-2 font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">{categories[report.category]} · {date(report.updatedAt)} · {report.evidence.length} bằng chứng</Text>
      </Pressable>)}
    </View>
    {data && data.totalPages > 1 ? <View className="mt-4 flex-row flex-wrap items-center justify-between gap-3"><Button appearance="material" label="Trang trước" variant="outline" disabled={page === 0 || busy || loading} onPress={() => setPage(page - 1)} /><Text className="font-sans text-xs text-muted dark:text-dark-muted">{page + 1} / {data.totalPages}</Text><Button appearance="material" label="Trang sau" variant="outline" disabled={page + 1 >= data.totalPages || busy || loading} onPress={() => setPage(page + 1)} /></View> : null}
    <Sheet appearance="material" dismissDisabled={busy} visible={Boolean(selected)} title={review ? "Duyệt báo cáo" : "Chi tiết báo cáo"} onClose={() => { if (!busy) setSelected(null); }}>
      <ScrollView contentContainerStyle={{ padding: 20, gap: 16 }} keyboardShouldPersistTaps="handled">
      {error ? <View accessibilityRole="alert"><Alert icon={faCircleInfo} tone="destructive" description={error} /><Button appearance="material" label="Tải lại chi tiết báo cáo" variant="outline" disabled={busy} onPress={() => void act(async () => { if (selected) await refreshDetail(selected.id); })} /></View> : null}
      {notice ? <View accessibilityLiveRegion="polite"><Alert icon={faCircleInfo} description={notice} tone={listRefreshFailed ? "warning" : "safe"} /></View> : null}
      {listRefreshFailed ? <Button appearance="material" label="Tải lại danh sách báo cáo" variant="outline" disabled={busy} onPress={() => void act(async () => { setData(await reportApi.mine(page)); setListRefreshFailed(false); setNotice("Đã cập nhật danh sách báo cáo."); })} /> : null}
      {busy ? <View accessibilityLabel="Đang xử lý tố giác"><ActivityIndicator /></View> : null}
    {selected ? <View className="min-w-0 gap-3">
      <Text accessibilityRole="header" aria-level={2} className="font-display text-[22px] leading-7 text-md-on-surface dark:text-dark-md-on-surface">{selected.title}</Text>
      <Text selectable className="mt-2 font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">{selected.targetDisplaySnapshot}</Text>
      <View className="mt-3 self-start"><Badge label={statuses[selected.status]} variant={selected.status === "VERIFIED" ? "safe" : "neutral"} /></View>
      <Text selectable className="mt-4 font-sans text-sm leading-6 text-ink dark:text-dark-ink">{selected.description}</Text>
      {selected.moderatorNote ? <View className="mt-4"><Alert icon={faCircleInfo} description={selected.moderatorNote} /></View> : null}
      <Text accessibilityRole="header" aria-level={3} className="mt-6 font-medium text-base text-ink dark:text-dark-ink">Bằng chứng ({selected.evidence.length}/5)</Text>
      <Text className="mt-2 font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">JPG, PNG, WEBP hoặc PDF. Tối đa 10 MB mỗi file. Chỉ chủ báo cáo và kiểm duyệt viên được truy cập.</Text>
      {selected.evidence.length === 0 ? <Text className="mt-3 font-sans text-sm text-muted dark:text-dark-muted">Chưa có bằng chứng đính kèm.</Text> : null}
      {selected.evidence.map((evidence) => <View key={evidence.id} className="mt-3 min-w-0 border-b border-line pb-3 dark:border-dark-line"><Text className="font-sans text-sm text-ink dark:text-dark-ink">{evidence.originalFilename}</Text><Text className="mt-1 font-sans text-xs text-muted dark:text-dark-muted">{Math.ceil(evidence.sizeBytes / 1024)} KB</Text><View className="mt-2 flex-row flex-wrap gap-2"><Button appearance="material" label="Tải bằng chứng" accessibilityLabel={`Tải bằng chứng ${evidence.originalFilename}`} variant="outline" disabled={busy} onPress={() => void act(() => reportApi.download(selected, evidence, review))} />{editable ? <Button appearance="material" accessibilityLabel={`${confirmDelete === evidence.id ? "Xác nhận xóa" : "Xóa"} bằng chứng ${evidence.originalFilename}`} label={confirmDelete === evidence.id ? "Xác nhận xóa" : "Xóa"} variant={confirmDelete === evidence.id ? "destructive" : "ghost"} disabled={busy} onPress={() => confirmDelete !== evidence.id ? setConfirmDelete(evidence.id) : void act(async () => { await reportApi.remove(selected.id, evidence.id); setConfirmDelete(null); await refreshDetail(selected.id); })} /> : null}{confirmDelete === evidence.id ? <Button appearance="material" label="Hủy" variant="ghost" onPress={() => setConfirmDelete(null)} /> : null}</View></View>)}
      {editable ? <Button appearance="material" className="mt-4 self-start" label="Thêm bằng chứng" variant="outline" disabled={busy || selected.evidence.length >= 5} onPress={() => void act(async () => { const file = await pickEvidence(); if (file) { await reportApi.upload(selected.id, file); await refreshDetail(selected.id); setNotice("Đã thêm bằng chứng."); } })} /> : null}
      {!review && !editable ? <Text className="mt-3 font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Chỉ có thể thêm hoặc xóa bằng chứng khi báo cáo ở trạng thái Đã gửi hoặc Cần bổ sung.</Text> : null}
      {(review && selected.status === "PENDING_REVIEW") || (!review && selected.status === "NEED_MORE_INFO") ? <View className="mt-6"><Input appearance="material" label={review ? "Lý do quyết định" : "Thông tin bổ sung"} accessibilityLabel={review ? "Lý do quyết định" : "Thông tin bổ sung"} value={note} onChangeText={setNote} maxLength={2000} multiline numberOfLines={3} placeholder="Ghi rõ nội dung để người nhận có thể xử lý tiếp" /><View className="mt-3 flex-row flex-wrap gap-3">{review ? <><Button appearance="material" label="Xác minh" variant="success" disabled={busy || !note.trim()} onPress={() => void act(() => decide("verify"))} /><Button appearance="material" label="Yêu cầu bổ sung" variant="outline" disabled={busy || !note.trim()} onPress={() => void act(() => decide("request-info"))} /><Button appearance="material" label="Từ chối" variant="destructive" disabled={busy || !note.trim()} onPress={() => void act(() => decide("reject"))} /></> : <Button appearance="material" label="Gửi bổ sung" disabled={busy || !note.trim()} onPress={() => void act(async () => { try { setSelected(await reportApi.resubmit(selected, note.trim())); setNote(""); setNotice("Đã gửi thông tin bổ sung."); } catch (e) { await refreshDetail(selected.id).catch(() => {}); throw e; } await load(); })} />}</View></View> : null}
      {review && selected.status === "SUBMITTED" ? <Button appearance="material" className="mt-6 self-start" label="Bắt đầu kiểm duyệt" disabled={busy} onPress={() => void act(() => decide("start-review"))} /> : null}
      <Text accessibilityRole="header" aria-level={3} className="mt-7 font-medium text-base text-ink dark:text-dark-ink">Lịch sử xử lý</Text>
      {selected.history.map((entry, index) => <View key={`${entry.createdAt}-${index}`} className="mt-3 border-b border-line pb-3 dark:border-dark-line"><Text className="font-medium text-sm text-ink dark:text-dark-ink">{statuses[entry.toStatus]}</Text><Text className="mt-1 font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">{date(entry.createdAt)}{entry.note ? ` · ${entry.note}` : ""}</Text></View>)}
    </View> : null}
    </ScrollView></Sheet>
    <Sheet appearance="material" dismissDisabled={busy} visible={composer} title="Gửi báo cáo" description="Cung cấp thông tin sự việc. Thêm bằng chứng sau khi gửi." closeLabel="Đóng biểu mẫu báo cáo" onClose={() => { if (!busy) setComposer(false); }} footer={<Button appearance="material" label="Gửi báo cáo" loading={busy} disabled={!validDraft} onPress={() => void act(create)} />}>
      <ScrollView contentContainerStyle={{ padding: 20, gap: 20 }} keyboardShouldPersistTaps="handled">
        {error ? <View accessibilityRole="alert"><Alert icon={faCircleInfo} tone="destructive" description={error} /></View> : null}
        <FilterBar appearance="material" label="Loại đối tượng" value={draft.targetType} options={[{ value: "URL", label: "URL" }, { value: "MESSAGE_TEMPLATE", label: "Tin nhắn" }]} onChange={(targetType) => setDraft({ ...draft, targetType, scanId: undefined })} />
        <Input appearance="material" label="Đối tượng" accessibilityLabel="Đối tượng bị báo cáo" value={draft.targetValue} onBlur={() => setTouched(current => ({ ...current, target: true }))} error={touched.target && !draft.targetValue.trim() ? "Nhập đường dẫn hoặc nội dung tin nhắn cần báo cáo." : undefined} onChangeText={(targetValue) => setDraft({ ...draft, targetValue, scanId: undefined })} maxLength={10000} autoCapitalize="none" multiline={draft.targetType === "MESSAGE_TEMPLATE"} placeholder={draft.targetType === "URL" ? "https://..." : "Nội dung tin nhắn đáng ngờ"} />
        <Text className="font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Chọn danh mục phù hợp với sự việc, kể cả khi bạn đổi loại đối tượng.</Text>
        <FilterBar appearance="material" label="Danh mục" value={draft.category} options={Object.entries(categories).map(([value, label]) => ({ value: value as ReportCategory, label }))} onChange={(category) => setDraft({ ...draft, category })} />
        <Input appearance="material" label="Tiêu đề (6–200 ký tự)" accessibilityLabel="Tiêu đề báo cáo" value={draft.title} onBlur={() => setTouched(current => ({ ...current, title: true }))} error={touched.title && draft.title.trim().length < 6 ? "Tiêu đề cần ít nhất 6 ký tự." : undefined} onChangeText={(title) => setDraft({ ...draft, title })} maxLength={200} />
        <Input appearance="material" label="Mô tả (ít nhất 16 ký tự)" accessibilityLabel="Mô tả báo cáo" value={draft.description} onBlur={() => setTouched(current => ({ ...current, description: true }))} error={touched.description && draft.description.trim().length < 16 ? "Mô tả sự việc bằng ít nhất 16 ký tự." : undefined} onChangeText={(description) => setDraft({ ...draft, description })} maxLength={10000} multiline numberOfLines={4} />
        <Text className="font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Không gửi mật khẩu, mã OTP hoặc thông tin cá nhân không cần thiết.</Text>
      </ScrollView>
    </Sheet>
  </View>;
}
function message(error: unknown) { return error instanceof Error ? error.message : "Không xử lý được yêu cầu. Hãy thử lại."; }
