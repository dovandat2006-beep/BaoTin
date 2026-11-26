export type BrowserNotificationState = "unsupported" | "default" | "granted" | "denied";

function notificationApi(): typeof Notification | null {
  if (typeof window === "undefined" || !("Notification" in window)) return null;
  return window.Notification;
}

export function browserNotificationState(): BrowserNotificationState {
  const api = notificationApi();
  if (!api) return "unsupported";
  return api.permission;
}

/** Must be called from a user gesture; returns whether browser notifications can be shown. */
export async function requestBrowserNotificationPermission(): Promise<boolean> {
  const api = notificationApi();
  if (!api) return false;
  if (api.permission === "granted") return true;
  if (api.permission === "denied") return false;
  try {
    return (await api.requestPermission()) === "granted";
  } catch {
    return false;
  }
}

export function showBrowserNotification(id: string, title: string, body: string): void {
  const api = notificationApi();
  if (!api || api.permission !== "granted") return;
  try {
    const notification = new api(title, { body, tag: id });
    notification.onclick = () => {
      if (typeof window !== "undefined") window.focus();
      notification.close();
    };
  } catch {
    // Some browsers require a service worker for constructor notifications; in-app state still updates.
  }
}
