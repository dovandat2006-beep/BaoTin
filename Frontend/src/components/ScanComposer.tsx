import { useRef, type ComponentRef } from "react";
import { faMagnifyingGlass } from "@fortawesome/free-solid-svg-icons/faMagnifyingGlass";
import { Platform, Pressable, Text, View } from "react-native";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { faCheck } from "@fortawesome/free-solid-svg-icons/faCheck";
import { useAppTheme } from "../theme/AppTheme";
import { scanPlaceholders, type ScanType } from "../lib/risk";
import type { SupportedScanType } from "../api/contracts";
import { Button, Input } from "./ui";

type Props = { kind: SupportedScanType; value: string; scanning: boolean; onKindChange: (kind: SupportedScanType) => void; onValueChange: (value: string) => void; onScan: () => void };

const options = [
  { value: "URL" as const, label: "Đường dẫn" },
  { value: "SMS" as const, label: "Tin nhắn" }
];

export function ScanComposer({ kind, value, scanning, onKindChange, onValueChange, onScan }: Props) {
  const tabRefs = useRef<Array<ComponentRef<typeof Pressable> | null>>([]);
  const { colors } = useAppTheme();
  const maximum = kind === "SMS" ? 10000 : 4096;
  const valid = value.trim().length > 0 && value.length <= maximum;
  const inputLabels: Record<ScanType, string> = {
    URL: "Nhập URL:",
    SMS: "Nhập tin nhắn:",
    PHONE: "Nhập số điện thoại:",
    BANK_ACCOUNT: "Nhập số tài khoản ngân hàng:",
    EMAIL: "Nhập email:",
    SOCIAL_ACCOUNT: "Nhập tài khoản mạng xã hội:"
  };
  return (
    <View testID="scan-composer" className="min-w-0 gap-6">
        <View>
          <Text className="mb-3 font-medium text-sm text-md-on-surface dark:text-dark-md-on-surface">Bạn muốn kiểm tra gì?</Text>
          <View accessibilityRole="tablist" accessibilityLabel="Loại nội dung" className="flex-row overflow-hidden rounded-xl border border-md-outline dark:border-dark-md-outline">
            {options.map((option, index) => {
              const selected = kind === option.value;
              return <Pressable key={option.value} ref={node => { tabRefs.current[index] = node; }} nativeID={`scan-type-${option.value}`} accessibilityRole="tab" aria-selected={selected} aria-controls="scan-type-panel" tabIndex={selected ? 0 : -1} {...(Platform.OS === "web" ? { onKeyDown: (raw: unknown) => { const event = raw as { key?: string; nativeEvent?: { key?: string }; preventDefault: () => void }; const key = event.key ?? event.nativeEvent?.key ?? ""; if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(key)) return; event.preventDefault(); const next = key === "Home" ? 0 : key === "End" ? 1 : 1 - index; const option = options[next]!; onKindChange(option.value); (tabRefs.current[next] as unknown as { focus?: () => void })?.focus?.(); } } : {})} accessibilityLabel={option.label} accessibilityState={{ selected }} onPress={() => { if (!selected) onKindChange(option.value); }} className={`min-h-14 min-w-0 flex-1 flex-row items-center justify-center gap-2 px-3 active:opacity-75 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-md-primary dark:focus-visible:ring-dark-md-primary ${index ? "border-l border-md-outline dark:border-dark-md-outline" : ""} ${selected ? "bg-md-secondary-container dark:bg-dark-md-secondary-container" : "hover:bg-md-surface-low dark:hover:bg-dark-md-surface-low"}`}>
                {selected ? <FontAwesomeIcon icon={faCheck} size={16} color={colors.mdOnSecondaryContainer} /> : null}
                <Text numberOfLines={1} className={`font-medium text-sm ${selected ? "text-md-on-secondary-container dark:text-dark-md-on-secondary-container" : "text-md-on-surface-variant dark:text-dark-md-on-surface-variant"}`}>{option.value === "URL" ? "Đường dẫn" : "Tin nhắn"}</Text>
              </Pressable>;
            })}
          </View>
        </View>
        <View nativeID="scan-type-panel" accessibilityRole={Platform.OS === "web" ? "tabpanel" as never : undefined} aria-labelledby={`scan-type-${kind}`}><Input
          appearance="material"
          label={inputLabels[kind]}
          value={value}
          onChangeText={onValueChange}
          multiline={kind === "SMS"}
          numberOfLines={kind === "SMS" ? 6 : 1}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType={kind === "URL" ? "url" : "default"}
          returnKeyType={kind === "URL" ? "go" : "default"}
          onSubmitEditing={kind === "URL" ? () => { if (valid && !scanning) onScan(); } : undefined}
          placeholder={scanPlaceholders[kind]}
          description={kind === "SMS" ? "Chỉ phân tích tin nhắn và đường dẫn trong nội dung." : "Chỉ phân tích đường dẫn, không truy cập trang web."}
          error={value.length > maximum ? `Nhập tối đa ${maximum.toLocaleString("vi-VN")} ký tự.` : undefined}
        /></View>
        <Text className="font-sans text-sm leading-5 text-md-on-surface-variant dark:text-dark-md-on-surface-variant">Chỉ gửi nội dung cần kiểm tra. Tránh đưa mật khẩu hoặc mã OTP thật vào tin nhắn.</Text>
        <Button appearance="material" accessibilityLabel={scanning ? "Đang phân tích" : "Kiểm tra ngay"} label={scanning ? "Đang phân tích" : "Kiểm tra ngay"} icon={faMagnifyingGlass} loading={scanning} disabled={!valid} onPress={onScan} />
    </View>
  );
}
