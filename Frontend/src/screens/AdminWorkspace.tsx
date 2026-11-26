/* Hallmark · focused administration workspace · DESIGN.md Material3/Roboto/FontAwesome
 * Existing ADMIN guard and independent, unmounted private panels preserved.
 */
import { lazy, Suspense, useState } from "react";
import { Platform, View } from "react-native";
import type { AccountApi } from "../api/account";
import type { ReportApi } from "../api/reports";
import { AdminModerationScreen } from "./AdminModerationScreen";
import type { AnalyticsApi } from "../api/analytics";
import { canAccessAdmin, type AuthSession } from "../lib/auth";
import { LoadingPanel, Tabs } from "../components/ui";
import { AdminUsersScreen } from "./AdminUsersScreen";

const AdminDashboardScreen = lazy(() => import("./AdminDashboardScreen").then(module => ({ default: module.AdminDashboardScreen })));
/** Keep both independent remote/local features; inactive panels hold no private state. */
export function AdminWorkspace({ session, accountApi, analyticsApi, reportApi }: { session: AuthSession; accountApi: AccountApi; analyticsApi: AnalyticsApi; reportApi?: ReportApi }) {
  const [tab, setTab] = useState<"users" | "analytics" | "overview">("users");
  if (!canAccessAdmin(session)) return null;
  return <View className="mx-auto w-full min-w-0 max-w-[900px] gap-6">
    <View className="w-full max-w-lg"><Tabs appearance="material" label="Quản trị" panelId="admin-workspace-panel" items={reportApi ? [{ value: "users", label: "Người dùng" }, { value: "analytics", label: "Tố giác" }, { value: "overview", label: "Tổng quan" }] : [{ value: "users", label: "Người dùng" }, { value: "analytics", label: "Thống kê" }]} value={tab} onChange={setTab} /></View>
    <View nativeID="admin-workspace-panel" accessibilityRole={Platform.OS === "web" ? "tabpanel" as never : undefined} aria-labelledby={`admin-workspace-panel-${tab}`} className="min-w-0">
    {tab === "users" ? <AdminUsersScreen session={session} api={accountApi} /> : reportApi && tab === "analytics" ? <AdminModerationScreen session={session} api={reportApi} /> : <Suspense fallback={<LoadingPanel label="Đang tải thống kê quản trị" />}><AdminDashboardScreen api={analyticsApi} /></Suspense>}
    </View>
  </View>;
}
