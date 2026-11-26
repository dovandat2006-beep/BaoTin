/* Hallmark · pre-emit critique: P4 H4 E4 S5 R5 V4
 * application shell · BaoTin Material 3 · calm-practical
 * Existing auth, adapters, routing and screen ownership retained.
 */
import "./global.css";

import { faCircleExclamation } from "@fortawesome/free-solid-svg-icons/faCircleExclamation";
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider, SafeAreaView, useSafeAreaInsets } from "react-native-safe-area-context";
import { ActivityIndicator, AppState, BackHandler, KeyboardAvoidingView, LayoutAnimation, Platform, ScrollView, Text, View, useWindowDimensions } from "react-native";
import { useFonts } from "expo-font";
import { Roboto_400Regular } from "@expo-google-fonts/roboto/400Regular";
import { Roboto_500Medium } from "@expo-google-fonts/roboto/500Medium";
import { Roboto_600SemiBold } from "@expo-google-fonts/roboto/600SemiBold";
import { Roboto_700Bold } from "@expo-google-fonts/roboto/700Bold";

import { AppNav, type AppSection } from "./src/components/AppNav";
import { compactNavigationLayout } from "./src/theme/navigation";
import { NotificationSheet, type NotificationRecord } from "./src/components/NotificationSheet";
import { TopBar } from "./src/components/TopBar";
import { useReducedMotion } from "./src/components/Motion";
import type { NewReport } from "./src/components/ReportComposer";
import { myReports as initialReports, notifications as initialNotifications, watchItems as initialWatchlist, type ReportRecord, type WatchItem } from "./src/data/mock";
import { ScanScreen } from "./src/screens/ScanScreen";
import { AuthScreen } from "./src/screens/AuthScreen";
import type { WatchlistPreferences } from "./src/screens/WatchlistScreen";
import { canAccessAdmin, resolveVisibleSection, type AuthSession } from "./src/lib/auth";
import { restoreVerifiedSession } from "./src/lib/verifiedSession";
import { AppThemeProvider, useAppTheme } from "./src/theme/AppTheme";
import { Alert, Skeleton, Sheet, Button } from "./src/components/ui";
import { ReportWorkspace, type ReportTarget } from "./src/screens/ReportWorkspace";
import { createReportApi } from "./src/api/reports";
import { ServiceUnavailableScreen } from "./src/screens/ServiceUnavailableScreen";
import { createAnalyticsApi } from "./src/api/analytics";
import { apiBaseUrl, apiEnabled } from "./src/lib/apiConfig";
import { ApiProblem, apiErrorMessage, createApiClient } from "./src/api/client";
import { createAuthApi, toAuthSession, type ApiAuthOutcome, type ApiProfile, type RegistrationInput, type WebTokens } from "./src/api/auth";
import { createAccountApi, createRecoveryApi } from "./src/api/account";
import { useOAuthFlow } from "./src/lib/useOAuthFlow";
import { OAuthCallbackBridge } from "./src/screens/OAuthCallbackBridge";
import { createBaoTinApi, type BaoTinApi } from "./src/api/baotin";
import type { NotificationApiResource, NotificationPreferences, WatchlistApiResource } from "./src/api/contracts";
import { browserNotificationState, requestBrowserNotificationPermission, showBrowserNotification, type BrowserNotificationState } from "./src/lib/browserPush";
import { relativeTime } from "./src/lib/time";
const AdminDashboardScreen = lazy(() => import("./src/screens/AdminDashboardScreen").then((module) => ({ default: module.AdminDashboardScreen })));
const AdminWorkspace = lazy(() => import("./src/screens/AdminWorkspace").then(module => ({ default: module.AdminWorkspace })));
const AccountWorkspace = lazy(() => import("./src/screens/AccountWorkspace").then(module => ({ default: module.AccountWorkspace })));
const ScanHistoryScreen = lazy(() => import("./src/screens/ScanHistoryScreen").then((module) => ({ default: module.ScanHistoryScreen })));
const ReportsScreen = lazy(() => import("./src/screens/ReportsScreen").then((module) => ({ default: module.ReportsScreen })));
const LiveSearchScreen = lazy(() => import("./src/screens/LiveSearchScreen").then((module) => ({ default: module.LiveSearchScreen })));
const SearchScreen = lazy(() => import("./src/screens/SearchScreen").then((module) => ({ default: module.SearchScreen })));
const WatchlistScreen = lazy(() => import("./src/screens/WatchlistScreen").then((module) => ({ default: module.WatchlistScreen })));

const defaultPreferences: WatchlistPreferences = { risk: true, report: true, push: true, inApp: true };

function adminDeniedMessage(session: AuthSession) {
  return `Tài khoản ${session.displayName} không có quyền quản trị. Đăng xuất và đăng nhập bằng tài khoản ADMIN nếu bạn được cấp quyền.`;
}

function toNotificationRecord(item: NotificationApiResource): NotificationRecord {
  return {
    id: item.id,
    type: item.type,
    title: item.title,
    body: item.body,
    time: relativeTime(item.createdAt),
    read: item.read,
    data: item.data
  };
}

function toWatchItem(item: WatchlistApiResource): WatchItem {
  return {
    id: item.id,
    target: item.url,
    score: item.score,
    previousScore: item.previousScore,
    level: item.level,
    label: item.label ?? "Đang theo dõi",
    type: "URL",
    updated: relativeTime(item.updatedAt)
  };
}

function toUiPreferences(item: NotificationPreferences): WatchlistPreferences {
  return { risk: item.riskChangeEnabled, report: item.reportStatusEnabled, push: item.pushEnabled, inApp: item.inAppEnabled };
}

function toApiPreferencePatch(key: keyof WatchlistPreferences, value: boolean): Partial<NotificationPreferences> {
  if (key === "risk") return { riskChangeEnabled: value };
  if (key === "report") return { reportStatusEnabled: value };
  if (key === "push") return { pushEnabled: value };
  return { inAppEnabled: value };
}

function AppShell() {
  const { isDark } = useAppTheme();
  const reduced = useReducedMotion();
  const { width, fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const compact = width < 600;
  const pageGutter = compact ? 16 : width < 840 ? 24 : width < 1200 ? 32 : 40;
  const scanDrafts = useRef({ SMS: "", URL: "" });
  const [navCollapsed, setNavCollapsed] = useState(width < 1200);
  const [section, setSection] = useState<AppSection>("scan");
  const [reports, setReports] = useState(() => apiEnabled ? [] : [...initialReports]);
  const [reportTarget, setReportTarget] = useState<ReportTarget | null>(null);
  const consumeReportTarget = useCallback(() => setReportTarget(null), []);
  const [watchlist, setWatchlist] = useState<WatchItem[]>(() => apiEnabled ? [] : [...initialWatchlist]);
  const [notificationItems, setNotificationItems] = useState<NotificationRecord[]>(() => apiEnabled ? [] : [...initialNotifications]);
  const [unreadTotal, setUnreadTotal] = useState<number | null>(null);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [securityReceipt, setSecurityReceipt] = useState<string[] | null>(null);
  const [pendingSection, setPendingSection] = useState<AppSection | null>(null);
  const [accessNotice, setAccessNotice] = useState<string | null>(null);
  const [notificationsLoading, setNotificationsLoading] = useState(false);
  const [notificationsError, setNotificationsError] = useState<string | null>(null);
  const [watchlistLoading, setWatchlistLoading] = useState(false);
  const [watchlistError, setWatchlistError] = useState<string | null>(null);
  const [notificationPreferences, setNotificationPreferences] = useState<WatchlistPreferences | undefined>();
  const [preferencesLoading, setPreferencesLoading] = useState(false);
  const [preferencesError, setPreferencesError] = useState<string | null>(null);
  const [browserPushState, setBrowserPushState] = useState<BrowserNotificationState>(() => browserNotificationState());
  const tokenRef = useRef<string | null>(null);
  const identityRef = useRef<string | null>(null);
  const sessionGeneration = useRef(0);
  const refreshInFlight = useRef<Promise<string | null> | null>(null);
  const seenNotifications = useRef<Set<string> | null>(null);
  const unreadCount = apiEnabled ? unreadTotal ?? 0 : notificationItems.filter((item) => !item.read).length;
  const authenticated = Boolean(session);

  const authApi = useMemo(() => apiEnabled ? createAuthApi(apiBaseUrl) : null, []);

  const expireSession = useCallback(() => {
    sessionGeneration.current += 1;
    refreshInFlight.current = null;
    tokenRef.current = null;
    identityRef.current = null;
    seenNotifications.current = null;
    setReportTarget(null);
    scanDrafts.current = { SMS: "", URL: "" };
    setSession(null);
    setPendingSection(null);
    setAccessNotice("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
    setSection("auth");
    setNotificationsOpen(false);
    setNotificationItems([]);
    setWatchlist([]);
    setNotificationPreferences(undefined);
  }, []);

  const refreshAccessToken = useCallback(async (): Promise<string | null> => {
    if (!authApi) return null;
    if (refreshInFlight.current) return refreshInFlight.current;
    const generation = sessionGeneration.current;
    const hadToken = tokenRef.current !== null;
    const expectedUserId = identityRef.current;
    const task = (async () => {
      try {
        const restored = await restoreVerifiedSession(authApi, () => sessionGeneration.current === generation, expectedUserId);
        if (sessionGeneration.current !== generation) return null;
        if (!restored) {
          if (hadToken) expireSession();
          return null;
        }
        tokenRef.current = restored.token;
        identityRef.current = restored.userId;
        setSession(restored.session);
        return restored.token;
      } catch {
        if (sessionGeneration.current === generation) {
          tokenRef.current = null;
          identityRef.current = null;
          if (hadToken) expireSession();
          else setSession(null);
        }
        return null;
      } finally {
        if (sessionGeneration.current === generation) refreshInFlight.current = null;
      }
    })();
    refreshInFlight.current = task;
    return task;
  }, [authApi, expireSession]);

  const revalidateProfile = useCallback(async () => {
    const token = tokenRef.current;
    const generation = sessionGeneration.current;
    if (!authApi || !token) return;
    try {
      const profile = await authApi.profile(token);
      if (sessionGeneration.current === generation && tokenRef.current === token) {
        const verified = toAuthSession(profile);
        setSession(current => current?.displayName === verified.displayName && current.role === verified.role && current.method === verified.method ? current : verified);
      }
    } catch (error) {
      if (sessionGeneration.current === generation && tokenRef.current === token) {
        // Normal access-token expiry uses rotation; revoked sessions still fail closed.
        if (error instanceof ApiProblem && error.problem.status === 401) await refreshAccessToken();
        else expireSession();
      }
    }
  }, [authApi, expireSession, refreshAccessToken]);

  const client = useMemo(() => apiEnabled && authApi ? createApiClient(apiBaseUrl, async () => tokenRef.current, refreshAccessToken, expireSession, revalidateProfile, () => sessionGeneration.current) : null, [authApi, refreshAccessToken, expireSession, revalidateProfile]);
  const accountApi = useMemo(() => client ? createAccountApi(client) : null, [client]);
  const recoveryApi = useMemo(() => createRecoveryApi(createApiClient(apiBaseUrl)), []);
  const profileUpdated = useCallback((profile: ApiProfile) => setSession(toAuthSession(profile)), []);
  const credentialInvalidated = useCallback((codes?: string[]) => { setSecurityReceipt(codes?.length ? codes : null); expireSession(); }, [expireSession]);
  useEffect(() => {
    const clearReceipt = () => { if (typeof document === "undefined" || document.visibilityState === "hidden") setSecurityReceipt(null); };
    const sub = AppState.addEventListener("change", state => { if (state !== "active") setSecurityReceipt(null); });
    if (typeof document !== "undefined") document.addEventListener("visibilitychange", clearReceipt);
    return () => { sub.remove(); if (typeof document !== "undefined") document.removeEventListener("visibilitychange", clearReceipt); };
  }, []);
  const api = useMemo<BaoTinApi | null>(() => client ? createBaoTinApi(client) : null, [client]);
  const reportApi = useMemo(() => client ? createReportApi(client) : null, [client]);
  const analyticsApi = useMemo(() => client ? createAnalyticsApi(client) : null, [client]);
  // Guest scans use the same Gateway origin; authenticated scans reuse the existing in-memory token/refresh client.
  const guestScanApi = useMemo(() => createBaoTinApi(createApiClient(apiBaseUrl)), []);
  const scanApi = api ?? guestScanApi;

  const loadNotifications = useCallback(async (silent = false) => {
    if (!api || !authenticated) return;
    if (!silent) setNotificationsLoading(true);
    try {
      const [page, unread] = await Promise.all([api.notifications(0, 100), api.notificationUnreadCount()]);
      const records = page.items.map(toNotificationRecord);
      setNotificationItems(records);
      setUnreadTotal(unread.unreadCount);
      setNotificationsError(null);
      const seen = seenNotifications.current;
      if (seen === null) {
        seenNotifications.current = new Set(records.map((item) => item.id));
      } else {
        const fresh = records.filter((item) => !item.read && !seen.has(item.id));
        for (const item of fresh) showBrowserNotification(item.id, item.title, item.body);
        for (const item of records) seen.add(item.id);
      }
    } catch (error) {
      if (error instanceof ApiProblem && error.problem.status === 401) {
        expireSession();
      } else if (!silent) {
        setNotificationsError(apiErrorMessage(error, "Chưa thể tải thông báo."));
      }
    } finally {
      if (!silent) setNotificationsLoading(false);
    }
  }, [api, authenticated, expireSession]);

  const loadWatchlist = useCallback(async () => {
    if (!api || !authenticated) return;
    setWatchlistLoading(true);
    try {
      const page = await api.watchlist(0, 100);
      setWatchlist(page.items.map(toWatchItem));
      setWatchlistError(null);
    } catch (error) {
      if (error instanceof ApiProblem && error.problem.status === 401) {
        expireSession();
      } else {
        setWatchlistError(apiErrorMessage(error, "Chưa thể tải danh sách theo dõi."));
      }
    } finally {
      setWatchlistLoading(false);
    }
  }, [api, authenticated, expireSession]);

  const loadPreferences = useCallback(async () => {
    if (!api || !authenticated) return;
    setPreferencesLoading(true);
    try {
      const preferences = await api.notificationPreferences();
      setNotificationPreferences(toUiPreferences(preferences));
      setPreferencesError(null);
    } catch (error) {
      if (error instanceof ApiProblem && error.problem.status === 401) {
        expireSession();
      } else {
        setPreferencesError(apiErrorMessage(error, "Chưa thể tải tùy chọn thông báo."));
      }
    } finally {
      setPreferencesLoading(false);
    }
  }, [api, authenticated, expireSession]);

  useEffect(() => {
    if (!apiEnabled) return;
    if (!authenticated) {
      setUnreadTotal(null);
      seenNotifications.current = null;
      setNotificationItems([]);
      setWatchlist([]);
      setNotificationPreferences(undefined);
      setNotificationsError(null);
      setWatchlistError(null);
      setPreferencesError(null);
      return;
    }
    void loadNotifications();
    void loadWatchlist();
    void loadPreferences();
  }, [authenticated, loadNotifications, loadPreferences, loadWatchlist]);

  useEffect(() => {
    if (!apiEnabled || !authenticated || !api) return;
    const timer = setInterval(() => {
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      void loadNotifications(true);
    }, 30_000);
    return () => clearInterval(timer);
  }, [api, authenticated, loadNotifications]);

  useEffect(() => {
    if (apiEnabled && authApi) void refreshAccessToken().catch(() => undefined);
  }, [apiEnabled, authApi, refreshAccessToken]);

  // Role changes/revocation remove privileged views on focus/resume and while active.
  // This is UI freshness only: Gateway and services authorize every actual operation.
  useEffect(() => {
    if (!apiEnabled || !authenticated) return;
    const timer = setInterval(() => {
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      void revalidateProfile();
    }, 30_000);
    const resume = () => { if (typeof document === "undefined" || document.visibilityState === "visible") void revalidateProfile(); };
    if (typeof document !== "undefined") document.addEventListener("visibilitychange", resume);
    const subscription = AppState.addEventListener("change", state => { if (state === "active") resume(); });
    return () => {
      clearInterval(timer);
      if (typeof document !== "undefined") document.removeEventListener("visibilitychange", resume);
      subscription.remove();
    };
  }, [authenticated, revalidateProfile]);

  const finishApiSession = useCallback(async (tokens: WebTokens): Promise<AuthSession> => {
    if (!authApi) throw new Error("Auth API is not configured");
    const generation = ++sessionGeneration.current;
    refreshInFlight.current = null;
    try {
      const profile = await authApi.profile(tokens.accessToken);
      if (generation !== sessionGeneration.current) throw new Error("Phiên đăng nhập đã thay đổi.");
      tokenRef.current = tokens.accessToken;
      identityRef.current = profile.id;
      return toAuthSession(profile);
    } catch (error) {
      if (generation === sessionGeneration.current) { tokenRef.current = null; identityRef.current = null; }
      throw error;
    }
  }, [authApi]);

  const handleApiLogin = useCallback(async (identifier: string, password: string): Promise<ApiAuthOutcome> => {
    if (!authApi) throw new Error("Auth API is not configured");
    const result = await authApi.login(identifier, password);
    if (result.kind === "mfa") return { kind: "mfa", challenge: result.challenge, method: result.method };
    return { kind: "session", session: await finishApiSession(result.tokens) };
  }, [authApi, finishApiSession]);

  const getCurrentToken = useCallback(() => tokenRef.current, []);
  const oauth = useOAuthFlow(authApi, finishApiSession, getCurrentToken);
  const socialContact = useCallback((email: string) => oauth.exchange(email), [oauth.exchange]);
  const socialTicket = useCallback((ticket: string) => oauth.exchange(undefined, ticket), [oauth.exchange]);
  const linkProvider = useCallback((provider: "GOOGLE" | "FACEBOOK") => oauth.start(provider, true), [oauth.start]);

  const handleApiRegister = useCallback(async (input: RegistrationInput): Promise<ApiAuthOutcome> => {
    if (!authApi) throw new Error("Auth API is not configured");
    await authApi.register(input);
    const result = await authApi.login(input.username, input.password);
    if (result.kind === "mfa") return { kind: "mfa", challenge: result.challenge, method: result.method };
    return { kind: "session", session: await finishApiSession(result.tokens) };
  }, [authApi, finishApiSession]);

  const handleApiMfa = useCallback(async (challenge: string, code: string): Promise<AuthSession> => {
    if (!authApi) throw new Error("Auth API is not configured");
    const result = await authApi.completeMfa(challenge, code);
    if (result.kind !== "tokens") throw new Error("Mã xác thực chưa hoàn tất đăng nhập.");
    return finishApiSession(result.tokens);
  }, [authApi, finishApiSession]);

  const adminAllowed = canAccessAdmin(session);
  // Không bao giờ render Quản trị khi phiên không phải ADMIN, kể cả khi section bị đặt trực tiếp.
  const visibleSection: AppSection = resolveVisibleSection(section, session);
  const blockedAdminNotice = section === "admin" && !adminAllowed
    ? session
      ? adminDeniedMessage(session)
      : "Đăng nhập bằng tài khoản quản trị để mở mục này."
    : null;
  const notice = accessNotice ?? blockedAdminNotice;
  const contentWidth = visibleSection === "admin" ? 1360 : visibleSection === "scan" ? 1280 : 1120;

  const navigate = useCallback((next: AppSection) => {
    if (next === "admin" && !canAccessAdmin(session)) {
      if (!session) {
        setPendingSection("admin");
        setAccessNotice("Đăng nhập bằng tài khoản quản trị để mở mục này.");
        setSection("auth");
      } else {
        setAccessNotice(adminDeniedMessage(session));
      }
      return;
    }
    const protectedSections: AppSection[] = ["history", "reports", "watchlist"];
    if (!session && protectedSections.includes(next)) {
      setPendingSection(next);
      setAccessNotice(`Đăng nhập để ${next === "history" ? "xem lịch sử kiểm tra" : next === "reports" ? "gửi và theo dõi báo cáo" : "quản lý danh sách theo dõi"}.`);
      setSection("auth");
      return;
    }
    setPendingSection(null);
    setAccessNotice(null);
    setSection(next);
  }, [session]);

  const handleAuthenticated = useCallback((next: AuthSession) => {
    setSession(next);
    setPendingSection(null);
    if (pendingSection === "admin" && !canAccessAdmin(next)) {
      setAccessNotice(adminDeniedMessage(next));
      setSection("scan");
      return;
    }
    setAccessNotice(null);
    setSection(pendingSection ?? "scan");
  }, [pendingSection]);

  const handleLogout = useCallback(() => {
    oauth.cancel();
    setSecurityReceipt(null);
    sessionGeneration.current += 1;
    refreshInFlight.current = null;
    const token = tokenRef.current;
    tokenRef.current = null;
    identityRef.current = null;
    seenNotifications.current = null;
    if (authApi) void authApi.logout(token).catch(() => undefined);
    if (apiEnabled) {
      setNotificationItems([]);
      setWatchlist([]);
      setNotificationPreferences(undefined);
      setNotificationsError(null);
      setWatchlistError(null);
      setPreferencesError(null);
    }
    setReportTarget(null);
    scanDrafts.current = { SMS: "", URL: "" };
    setSession(null);
    setPendingSection(null);
    setAccessNotice(null);
    setNotificationsOpen(false);
    setSection("scan");
  }, [authApi, oauth.cancel]);

  const handleContinue = useCallback(() => {
    setAccessNotice(null);
    setSection("scan");
  }, []);

  useEffect(() => {
    if (Platform.OS !== "android" || visibleSection === "scan") return;
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => { navigate("scan"); return true; });
    return () => subscription.remove();
  }, [navigate, visibleSection]);

  const createReport = (draft: NewReport) => {
    if (!reduced) LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    const report: ReportRecord = { id: `BT-DEMO-${String(reports.length + 1).padStart(3, "0")}`, target: draft.target, targetType: draft.targetType, category: draft.category, title: draft.title, status: "SUBMITTED", priority: "MEDIUM", updated: "Vừa xong", evidenceCount: draft.evidenceCount, moderatorNote: null };
    setReports((current) => [report, ...current]);
  };

  const removeWatch = useCallback(async (id: string) => {
    if (!api) {
      if (!reduced) LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      setWatchlist((current) => current.filter((item) => item.id !== id));
      return;
    }
    try {
      await api.removeWatch(id);
      setWatchlist((current) => current.filter((item) => item.id !== id));
    } catch (error) {
      if (error instanceof ApiProblem && error.problem.status === 401) expireSession();
      throw error;
    }
  }, [api, expireSession, reduced]);

  const addWatch = useCallback(async (url: string) => {
    if (!api) return;
    try {
      const created = await api.addWatch(url);
      const item = toWatchItem(created);
      setWatchlist((current) => [item, ...current.filter((watch) => watch.id !== item.id)]);
      void loadNotifications(true);
    } catch (error) {
      if (error instanceof ApiProblem && error.problem.status === 401) expireSession();
      throw error;
    }
  }, [api, expireSession, loadNotifications]);

  const updatePreference = useCallback(async (key: keyof WatchlistPreferences, value: boolean) => {
    if (!api) return;
    const previous = notificationPreferences;
    setNotificationPreferences({ ...(previous ?? defaultPreferences), [key]: value });
    try {
      const updated = await api.updateNotificationPreferences(toApiPreferencePatch(key, value));
      setNotificationPreferences(toUiPreferences(updated));
      setPreferencesError(null);
    } catch (error) {
      setNotificationPreferences(previous);
      if (error instanceof ApiProblem && error.problem.status === 401) expireSession();
      throw error;
    }
  }, [api, expireSession, notificationPreferences]);

  const openNotifications = useCallback(() => {
    setNotificationsOpen(true);
    setBrowserPushState(browserNotificationState());
    if (apiEnabled) void loadNotifications();
  }, [loadNotifications]);

  const markNotificationRead = useCallback(async (id: string) => {
    setNotificationItems((current) => current.map((item) => item.id === id ? { ...item, read: true } : item));
    if (!api) return;
    try {
      await api.markNotificationRead(id);
      setUnreadTotal((await api.notificationUnreadCount()).unreadCount);
    } catch (error) {
      if (error instanceof ApiProblem && error.problem.status === 401) expireSession();
      else setNotificationsError(apiErrorMessage(error, "Chưa thể đánh dấu thông báo đã đọc."));
      void loadNotifications(true);
    }
  }, [api, expireSession, loadNotifications]);

  const deleteNotification = useCallback(async (id: string) => {
    if (!api) return;
    await api.deleteNotification(id);
    setNotificationItems(current => current.filter(item => item.id !== id));
    setUnreadTotal((await api.notificationUnreadCount()).unreadCount);
  }, [api]);

  const markAllNotificationsRead = useCallback(async () => {
    setNotificationItems((current) => current.map((item) => ({ ...item, read: true })));
    if (!api) return;
    try {
      await api.markAllNotificationsRead();
      setUnreadTotal(0);
    } catch (error) {
      if (error instanceof ApiProblem && error.problem.status === 401) expireSession();
      else setNotificationsError(apiErrorMessage(error, "Chưa thể đánh dấu tất cả đã đọc."));
      void loadNotifications(true);
    }
  }, [api, expireSession, loadNotifications]);

  const activateNotification = useCallback((item: NotificationRecord) => {
    if (!item.read) void markNotificationRead(item.id);
    setNotificationsOpen(false);
    if (item.type.includes("WATCHLIST") || item.type === "RISK_CHANGED") navigate("watchlist");
    else if (item.type.includes("REPORT")) navigate("reports");
  }, [markNotificationRead, navigate]);

  const enableBrowserPush = useCallback(async () => {
    await requestBrowserNotificationPermission();
    setBrowserPushState(browserNotificationState());
  }, []);

  const screen = useMemo(() => {
    if (visibleSection === "auth") {
      if (session && accountApi) return <AccountWorkspace key={session.role} api={accountApi} recovery={recoveryApi} notifications={api} providerLinks={{ onLink: linkProvider, onTicket: socialTicket, onCancel: oauth.cancel }} onProfile={profileUpdated} onInvalidated={credentialInvalidated} onContinue={handleContinue} onLogout={handleLogout} />;
      return <AuthScreen session={session} onAuthenticated={handleAuthenticated} onContinue={handleContinue} onLogout={handleLogout} apiEnabled={apiEnabled} onApiLogin={handleApiLogin} onApiRegister={handleApiRegister} onApiMfa={handleApiMfa} onApiMfaResend={authApi?.resendMfa} recoveryApi={recoveryApi} onApiSocial={oauth.start} onApiSocialContact={socialContact} onApiSocialTicket={socialTicket} onCancelSocial={oauth.cancel} />;
    }
    if (visibleSection === "search") {
      if (apiEnabled) return <LiveSearchScreen api={guestScanApi} />;
      return <SearchScreen demo onScan={() => navigate("scan")} onWatch={() => navigate("watchlist")} />;
    }
    if (visibleSection === "history") return <ScanHistoryScreen api={scanApi} authenticated={apiEnabled && authenticated} onSignIn={() => navigate("auth")} onReport={() => navigate("reports")} onWatch={() => navigate("watchlist")} />;
    if (visibleSection === "reports") {
      if (reportApi && session) return <ReportWorkspace key={`${session.displayName}:${session.role}`} session={session} api={reportApi} initialTarget={reportTarget} onTargetConsumed={consumeReportTarget} />;
      if (apiEnabled) return <ServiceUnavailableScreen service="Reports" onContinue={() => navigate("auth")} continueLabel="Đăng nhập" />;
      return <ReportsScreen demo items={reports} onCreate={createReport} />;
    }
    if (visibleSection === "watchlist") return <WatchlistScreen demo={!apiEnabled} items={watchlist} onRemove={removeWatch} onOpenSearch={() => navigate("search")} onAdd={apiEnabled ? addWatch : undefined} preferences={apiEnabled ? notificationPreferences : undefined} preferencesLoading={apiEnabled && preferencesLoading} onRetryPreferences={apiEnabled ? () => void loadPreferences() : undefined} preferenceError={preferencesError} onUpdatePreference={apiEnabled ? updatePreference : undefined} loading={apiEnabled && watchlistLoading} error={watchlistError} onRetry={apiEnabled ? () => void loadWatchlist() : undefined} />;
    if (visibleSection === "admin") {
      if (!canAccessAdmin(session)) return null;
      if (accountApi && session && analyticsApi) return <AdminWorkspace key={`${session.displayName}:${session.role}`} session={session} accountApi={accountApi} analyticsApi={analyticsApi} reportApi={reportApi ?? undefined} />;
      if (apiEnabled) return <ServiceUnavailableScreen service="Analytics" onContinue={() => navigate("reports")} continueLabel="Mở báo cáo và kiểm duyệt thật" />;
      return <AdminDashboardScreen />;
    }
    return <ScanScreen draftStore={scanDrafts} key={session ? "signed-in" : "guest"} api={scanApi} onOpenHistory={() => navigate("history")} onOpenReports={(type, value, scanId) => { const source = authenticated && scanId && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(scanId) ? scanId : undefined; setReportTarget(type === "URL" || type === "SMS" ? { targetType: type === "URL" ? "URL" : "MESSAGE_TEMPLATE", targetValue: value, scanId: source } : null); navigate("reports"); }} onOpenWatchlist={() => navigate("watchlist")} />;
  }, [guestScanApi, analyticsApi, linkProvider, oauth.start, oauth.cancel, socialContact, socialTicket, accountApi, authApi, credentialInvalidated, profileUpdated, recoveryApi, consumeReportTarget, reportTarget, reportApi, addWatch, apiEnabled, authenticated, scanApi, handleApiLogin, handleApiMfa, handleApiRegister, handleAuthenticated, handleContinue, handleLogout, loadWatchlist, loadPreferences, navigate, notificationPreferences, preferencesError, preferencesLoading, removeWatch, reports, session, updatePreference, visibleSection, watchlist, watchlistError, watchlistLoading]);

  return (
    <SafeAreaView testID="app-shell" className="w-full max-w-full flex-1 overflow-hidden bg-md-surface dark:bg-dark-md-surface" edges={["top", "left", "right"]}>
      <StatusBar style={isDark ? "light" : "dark"} />
      <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={{ flex: 1, width: "100%" }}>
      <View className="w-full max-w-full flex-1 flex-row overflow-hidden">
        {!compact ? <AppNav onCollapsedChange={setNavCollapsed} active={visibleSection} onChange={navigate} authenticated={Boolean(session)} adminAllowed={adminAllowed} /> : null}
        <View className="min-w-0 max-w-full flex-1 overflow-hidden">
          <TopBar authenticated={authenticated} showBrand={compact || navCollapsed} horizontalPadding={pageGutter} compact={compact} unreadCount={unreadCount} accountLabel={session ? `Tài khoản của ${session.displayName}` : "Đăng nhập hoặc đăng ký"} onOpenNotifications={openNotifications} onOpenAccount={() => navigate("auth")} />
          <ScrollView
            className="min-w-0 max-w-full flex-1"
            style={{ width: "100%", maxWidth: "100%" }}
            contentContainerClassName="grow"
            contentContainerStyle={{ paddingBottom: compact ? compactNavigationLayout(insets.bottom, fontScale).contentPaddingBottom : Math.max(insets.bottom, 24), width: "100%", maxWidth: "100%" }}
            keyboardShouldPersistTaps="handled"
            keyboardDismissMode={Platform.OS === "web" ? "none" : "on-drag"}
            showsVerticalScrollIndicator={false}
          >
            <View testID="app-content" accessibilityRole={Platform.OS === "web" ? "main" as never : undefined} style={{ maxWidth: contentWidth, paddingHorizontal: pageGutter, paddingTop: compact ? 24 : 32, paddingBottom: 24 }} className="mx-auto min-w-0 w-full">
              {notice ? <View className="mb-4"><Alert icon={faCircleExclamation} tone="warning" title={section === "admin" || blockedAdminNotice ? "Quyền truy cập Quản trị" : "Thông báo tài khoản"} description={notice} /></View> : null}
              <Suspense fallback={<View accessibilityRole="progressbar" accessibilityLabel="Đang mở màn hình" className="gap-4"><Text className="font-medium text-base text-md-on-surface dark:text-dark-md-on-surface">Đang mở màn hình</Text><Skeleton className="h-8 w-2/3" /><Skeleton className="h-4 w-1/2" /><Skeleton className="h-40 w-full" /></View>}>{screen}</Suspense>
            </View>
          </ScrollView>
        </View>
      </View>
      </KeyboardAvoidingView>
      {compact ? <AppNav active={visibleSection} onChange={navigate} authenticated={Boolean(session)} adminAllowed={adminAllowed} compact /> : null}
      <NotificationSheet
        demo={!apiEnabled}
        visible={notificationsOpen}
        items={notificationItems}
        onClose={() => setNotificationsOpen(false)}
        onReadAll={() => void markAllNotificationsRead()}
        onDelete={api ? deleteNotification : undefined}
        onActivate={activateNotification}
        loading={apiEnabled && notificationsLoading}
        error={apiEnabled ? notificationsError : null}
        onRetry={apiEnabled ? () => void loadNotifications() : undefined}
        browserPushState={apiEnabled ? browserPushState : undefined}
        onEnableBrowserPush={apiEnabled ? () => void enableBrowserPush() : undefined}
      />
      <Sheet visible={Boolean(securityReceipt)} title="Mã dự phòng mới" description="Mọi phiên cũ đã bị thu hồi. Lưu các mã này ở nơi riêng tư; chúng chỉ hiển thị một lần và sẽ bị xóa khỏi màn hình khi đóng hoặc chuyển app sang nền." closeLabel="Đóng mã dự phòng" onClose={() => setSecurityReceipt(null)} footer={<Button label="Đã lưu mã, tiếp tục đăng nhập" onPress={() => setSecurityReceipt(null)} />}>
        <ScrollView contentContainerStyle={{ padding: 20, gap: 12 }}>{securityReceipt?.map(code => <Text key={code} selectable className="font-mono text-sm text-ink dark:text-dark-ink">{code}</Text>)}</ScrollView>
      </Sheet>
    </SafeAreaView>
  );
}

export default function App() {
  const [loaded, fontError] = useFonts({
    Roboto_400Regular,
    Roboto_500Medium,
    Roboto_600SemiBold,
    Roboto_700Bold,
  });

  if (!loaded && !fontError && Platform.OS !== "web") {
    return <View accessibilityRole="progressbar" accessibilityLabel="Đang mở BaoTin" className="flex-1 items-center justify-center gap-4 bg-md-surface dark:bg-dark-md-surface"><ActivityIndicator /><Text className="text-base text-md-on-surface dark:text-dark-md-on-surface">Đang mở BaoTin</Text></View>;
  }

  return (
    <SafeAreaProvider>
      <AppThemeProvider>{Platform.OS === "web" && ["/auth/oauth", "/auth/callback", "/auth/native-callback"].includes(window.location.pathname) ? <OAuthCallbackBridge /> : <AppShell />}</AppThemeProvider>
    </SafeAreaProvider>
  );
}
