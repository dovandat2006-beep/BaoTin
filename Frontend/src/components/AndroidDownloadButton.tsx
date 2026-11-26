import { useEffect, useState, type ReactNode } from "react";
import { Platform, Text, View } from "react-native";
import { faDownload } from "@fortawesome/free-solid-svg-icons/faDownload";
import { validateAndroidDownload, type AndroidDownload } from "../lib/androidDownload";
import { Button, Sheet } from "./ui";

/** No install link is exposed until the publisher supplies a verified APK manifest. */
export function AndroidDownloadButton({ compact, appearance = "default", renderTrigger, onOpen }: { compact: boolean; appearance?: "default" | "material"; renderTrigger?: (trigger: ReactNode) => ReactNode; onOpen?: () => void }) {
  const [manifest, setManifest] = useState<AndroidDownload | null>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (Platform.OS !== "web") return;
    let current = true;
    const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 5000);
    void fetch("/downloads/android.json", { cache: "no-store", credentials: "omit", signal: controller.signal }).then(async response => response.ok ? validateAndroidDownload(await response.json()) : null).then(result => { if (current) setManifest(result); }).catch(() => undefined).finally(() => clearTimeout(timer));
    return () => { current = false; clearTimeout(timer); controller.abort(); };
  }, []);
  if (Platform.OS !== "web" || !manifest) return renderTrigger ? <>{renderTrigger(null)}</> : null;
  const trigger = <Button appearance={appearance} label={compact ? undefined : "Tải APK"} accessibilityLabel="Tải APK Android" icon={faDownload} variant={appearance === "material" ? "ghost" : "outline"} size={compact ? "icon" : "default"} onPress={() => { onOpen?.(); setOpen(true); }} />;
  return <>
    {renderTrigger ? renderTrigger(trigger) : <View className="ml-1">{trigger}</View>}
    <Sheet visible={open} title="Tải ứng dụng Android" description={`Phiên bản ${manifest.version} · ${(manifest.bytes / 1024 / 1024).toFixed(1)} MiB`} closeLabel="Đóng tải APK" onClose={() => setOpen(false)} footer={<Button label="Tải file APK" icon={faDownload} onPress={() => { window.location.assign(manifest.url); setOpen(false); }} />}>
      <View className="gap-4 p-5"><Text className="font-sans text-sm leading-6 text-ink dark:text-dark-ink">File APK cài đặt độc lập, đã ký. Đây là khóa nghiệm thu riêng, không phải bản phát hành Google Play.</Text><Text className="font-sans text-xs leading-5 text-muted dark:text-dark-muted">SHA-256 để đối chiếu file:</Text><Text selectable className="break-all font-mono text-[10px] leading-5 text-ink dark:text-dark-ink">{manifest.sha256}</Text><Text className="font-sans text-xs leading-5 text-muted dark:text-dark-muted">Nếu Android hỏi, cho phép cài từ nguồn tải này theo lựa chọn của bạn. BaoTin không tự thay đổi cài đặt hệ thống hoặc tự cài ứng dụng.</Text></View>
    </Sheet>
  </>;
}
