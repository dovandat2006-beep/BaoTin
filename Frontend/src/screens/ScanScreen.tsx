/* Hallmark · pre-emit critique: P4 H4 E4 S5 R5 V4
 * Workbench · BaoTin Material 3 · calm-practical · designed-as-app
 * Audience: everyday Vietnamese users · task: URL/SMS checking · no enrichment
 */
import { faClockRotateLeft } from "@fortawesome/free-solid-svg-icons/faClockRotateLeft";
import { useEffect, useRef, useState, type ComponentRef } from "react";
import { AccessibilityInfo, Keyboard, Platform, Text, View, useWindowDimensions } from "react-native";
import { RiskResult } from "../components/RiskResult";
import { ScanComposer } from "../components/ScanComposer";
import { Button, LoadingPanel } from "../components/ui";
import { type ScanResult } from "../lib/risk";
import type { SupportedScanType } from "../api/contracts";
import { presentScan, scanErrorMessage, type ScanApi } from "../api/scan";

export function ScanScreen({ draftStore, api, onOpenHistory, onOpenReports, onOpenWatchlist }: { draftStore?: { current: Record<SupportedScanType, string> }; api: ScanApi; onOpenHistory: () => void; onOpenReports: (type: SupportedScanType, value: string, scanId?: string) => void; onOpenWatchlist: () => void }) {
  const { width } = useWindowDimensions();
  const twoColumn = width >= 1080;
  const resultRegion = useRef<ComponentRef<typeof View>>(null);
  const localDrafts = useRef<Record<SupportedScanType, string>>({ SMS: "", URL: "" });
  const drafts = draftStore ?? localDrafts;
  const [kind, setKind] = useState<SupportedScanType>("SMS");
  const [value, setValue] = useState(() => drafts.current.SMS);
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState<ScanResult | null>(null);
  const [scanError, setScanError] = useState<string | null>(null);
  const requestId = useRef(0);
  const pending = useRef<AbortController | null>(null);

  useEffect(() => () => { requestId.current += 1; pending.current?.abort(); }, []);

  useEffect(() => {
    if (!result && !scanError) return;
    if (Platform.OS === "web") {
      // RN Web exposes the host view; focus reveals the result without changing routes.
      const region = resultRegion.current as unknown as { focus?: () => void; scrollIntoView?: (options: { block: "start"; behavior: "auto" }) => void };
      region?.focus?.();
      region?.scrollIntoView?.({ block: "start", behavior: "auto" });
    } else AccessibilityInfo.announceForAccessibility(scanError ?? result!.title);
  }, [result, scanError]);

  const clearResult = () => {
    requestId.current += 1;
    pending.current?.abort();
    setScanning(false);
    setResult(null);
    setScanError(null);
  };

  const scan = async () => {
    if (scanning || !value.trim()) return;
    Keyboard.dismiss();
    const currentRequest = ++requestId.current;
    setScanning(true);
    setResult(null);
    setScanError(null);
    pending.current?.abort();
    const controller = new AbortController(); pending.current = controller;
    try {
      const response = await api.scan({ type: kind, content: value }, controller.signal);
      if (currentRequest === requestId.current) setResult(presentScan(response));
    } catch (error) {
      if (currentRequest === requestId.current) setScanError(scanErrorMessage(error));
    } finally {
      if (currentRequest === requestId.current) setScanning(false);
    }
  };

  return (
    <View className="min-w-0 w-full">
      <Text accessibilityRole="header" className="font-display text-[28px] leading-9 text-md-on-surface dark:text-dark-md-on-surface md:text-[32px] md:leading-10">Kiểm tra lừa đảo</Text>
      <Text className="mt-2 max-w-[640px] font-sans text-base leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Kiểm tra đường dẫn hoặc tin nhắn đáng ngờ trước khi làm theo yêu cầu.</Text>
      <View className={`mt-8 items-start gap-8 ${twoColumn ? "flex-row" : "flex-col"}`}>
        <View className={twoColumn ? "min-w-0 w-[58%]" : "w-full"}>
          <ScanComposer kind={kind} value={value} scanning={scanning}
            onKindChange={(next) => { drafts.current[kind] = value; clearResult(); setKind(next); setValue(drafts.current[next]); }}
            onValueChange={(next) => { drafts.current[kind] = next; clearResult(); setValue(next); }} onScan={scan} />
          <View className="mt-4 self-start"><Button appearance="material" label="Xem lịch sử" icon={faClockRotateLeft} variant="ghost" size="sm" onPress={onOpenHistory} /></View>
        </View>
        <View ref={resultRegion} tabIndex={-1} testID="scan-result-region" className={`${twoColumn ? "min-w-0 flex-1" : "w-full"} rounded-2xl bg-md-surface-low p-5 dark:bg-dark-md-surface-low focus-visible:ring-2 focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary`}>
          {scanning ? <View accessibilityLiveRegion="polite"><LoadingPanel label="Đang kiểm tra" /><Text className="font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Bạn có thể sửa nội dung để bắt đầu lại.</Text></View>
            : scanError ? <View accessibilityRole="alert" className="gap-4"><Text accessibilityRole="header" className="font-display text-xl leading-7 text-md-error dark:text-dark-md-error">Chưa thể kiểm tra</Text><Text className="font-sans text-sm leading-6 text-md-on-surface dark:text-dark-md-on-surface">{scanError}</Text><Button appearance="material" label="Thử lại" variant="outline" onPress={scan} /></View>
            : result ? <RiskResult key={result.id} result={result} onReport={() => onOpenReports(kind, value, result.id)} onWatch={onOpenWatchlist} />
            : <View className="gap-2 py-3"><Text accessibilityRole="header" className="font-medium text-base leading-6 text-md-on-surface dark:text-dark-md-on-surface">Kết quả sẽ hiển thị tại đây</Text><Text className="font-sans text-sm leading-6 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Nhập nội dung rồi chọn “Kiểm tra ngay” để xem đánh giá và hướng dẫn tiếp theo.</Text></View>}
        </View>
      </View>
    </View>
  );
}
