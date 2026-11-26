import { faBell } from "@fortawesome/free-solid-svg-icons/faBell";
import { faCheckDouble } from "@fortawesome/free-solid-svg-icons/faCheckDouble";
import { faCircleExclamation } from "@fortawesome/free-solid-svg-icons/faCircleExclamation";
import { faFileCircleCheck } from "@fortawesome/free-solid-svg-icons/faFileCircleCheck";
import { FontAwesomeIcon } from "@fortawesome/react-native-fontawesome";
import { Pressable, ScrollView, Text, View } from "react-native";
import { useState } from "react";
import { apiErrorMessage } from "../api/client";
import type { BrowserNotificationState } from "../lib/browserPush";
import { useAppTheme } from "../theme/AppTheme";
import { DemoDataNotice } from "./DemoDataNotice";
import { Button, EmptyState, ErrorPanel, LoadingPanel, Sheet } from "./ui";

export type NotificationRecord = { id: string; type: string; title: string; body: string; time: string; read: boolean; data?: Record<string, unknown> };

type Props = {
  visible: boolean;
  items: NotificationRecord[];
  demo?: boolean;
  onClose: () => void;
  onReadAll: () => void;
  onActivate?: (item: NotificationRecord) => void;
  onDelete?: (id: string) => Promise<void>;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  browserPushState?: BrowserNotificationState;
  onEnableBrowserPush?: () => void;
};

export function NotificationSheet({ visible, items, demo = false, onClose, onReadAll, onActivate, onDelete, loading = false, error = null, onRetry, browserPushState, onEnableBrowserPush }: Props) {
  const { colors } = useAppTheme();
  const unread = items.some((item) => !item.read);
  const [confirm, setConfirm] = useState<string | null>(null);
  const [removing, setRemoving] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  async function remove(id: string) {
    if (!onDelete || removing) return;
    setRemoving(true); setDeleteError(null);
    try { await onDelete(id); setConfirm(null); }
    catch (failure) { setDeleteError(apiErrorMessage(failure, "Chưa xóa được thông báo.")); }
    finally { setRemoving(false); }
  }
  return (
    <Sheet
      visible={visible}
      title="Thông báo"
      description="Cập nhật rủi ro và báo cáo"
      closeLabel="Đóng bảng thông báo"
      onClose={onClose}
      footer={
        <View className="gap-2">
          <Button accessibilityLabel="Đánh dấu tất cả đã đọc" label="Đánh dấu tất cả đã đọc" icon={faCheckDouble} variant="outline" disabled={!unread} onPress={onReadAll} />
          {browserPushState === "default" && onEnableBrowserPush ? <Button label="Bật thông báo trình duyệt" variant="ghost" onPress={onEnableBrowserPush} /> : null}
          {browserPushState === "denied" ? <Text className="text-center font-sans text-[11px] leading-5 text-muted dark:text-dark-muted">Thông báo trình duyệt đang bị chặn trong cài đặt trình duyệt.</Text> : null}
        </View>
      }
    >
      <ScrollView showsVerticalScrollIndicator={false}>
        {demo ? <View className="px-5 pb-4"><DemoDataNotice description="Thông báo và trạng thái đã đọc chỉ là dữ liệu mẫu; thay đổi không được đồng bộ lên máy chủ." /></View> : null}
        {deleteError ? <ErrorPanel description={deleteError} /> : null}
        {loading ? <View className="px-5"><LoadingPanel label="Đang tải thông báo" /></View> : error ? <View className="px-5"><ErrorPanel description={error} onRetry={onRetry} /></View> : items.length === 0 ? <EmptyState icon={faBell} title="Chưa có thông báo" description="Các thay đổi quan trọng sẽ xuất hiện tại đây." /> : items.map((item, index) => {
          const report = item.type.startsWith("REPORT");
          return (
            <View key={item.id}>
            <Pressable
              accessibilityRole={onActivate ? "button" : undefined}
              accessibilityLabel={onActivate ? `Mở thông báo: ${item.title}` : undefined}
              disabled={!onActivate}
              onPress={() => onActivate?.(item)}
              className={`flex-row px-5 py-4 ${index < items.length - 1 ? "border-b border-line dark:border-dark-line" : ""} ${item.read ? "bg-transparent" : "bg-accent-soft dark:bg-dark-accent-soft"}`}
            >
              <View className="mt-0.5 w-5"><FontAwesomeIcon icon={report ? faFileCircleCheck : faCircleExclamation} size={14} color={report ? colors.accent : colors.danger} /></View>
              <View className="ml-3 min-w-0 flex-1"><Text className="font-medium text-sm text-ink dark:text-dark-ink">{item.title}</Text><Text className="mt-1 font-sans text-sm leading-5 text-muted dark:text-dark-muted">{item.body}</Text><Text className="mt-2 font-sans text-[11px] text-muted dark:text-dark-muted">{item.time}</Text></View>
              {!item.read ? <View accessibilityLabel="Chưa đọc" className="mt-1.5 h-2 w-2 rounded-full bg-accent dark:bg-dark-accent" /> : null}
            </Pressable>
            {onDelete ? <View className="gap-2 px-5 pb-3"><Button label={`Xóa thông báo ${item.title}`} variant="ghost" disabled={removing} onPress={() => setConfirm(item.id)} />{confirm === item.id ? <><Button label="Xác nhận xóa thông báo" variant="destructive" disabled={removing} onPress={() => void remove(item.id)} /><Button label="Hủy xóa thông báo" variant="ghost" disabled={removing} onPress={() => setConfirm(null)} /></> : null}</View> : null}
            </View>
          );
        })}
      </ScrollView>
    </Sheet>
  );
}
