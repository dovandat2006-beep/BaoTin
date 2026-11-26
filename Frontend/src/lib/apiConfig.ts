import { Platform } from "react-native";

const defaultGateway = "https://baotin.rimiprod.me";

export function resolveApiConfig(origin: string | undefined, demo: string | undefined) {
  if (demo && demo !== "0" && demo !== "1") throw new Error("EXPO_PUBLIC_DEMO_MODE must be 0 or 1");
  if (demo === "1") {
    if (origin?.trim()) throw new Error("Demo test builds must not also configure a real Gateway");
    return { baseUrl: "", enabled: false };
  }
  const value = origin?.trim().replace(/\/$/, "") || defaultGateway;
  const url = new URL(value);
  const localTest = url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  const credentials = Boolean(url.username || url.password);
  const suffix = url.pathname !== "/" || Boolean(url.search || url.hash);
  if ((!localTest && url.protocol !== "https:") || credentials || suffix)
    throw new Error("API URL must be an HTTPS Gateway origin without credentials, path or query");
  return { baseUrl: url.origin, enabled: true };
}

const config = resolveApiConfig(process.env.EXPO_PUBLIC_API_URL, process.env.EXPO_PUBLIC_DEMO_MODE);
// Same-origin Web dev proxy preserves HttpOnly/SameSite refresh cookies on localhost.
// Expo Go always calls the public HTTPS Gateway directly, never the computer's localhost.
const webDevProxy = Platform.OS === "web" && process.env.NODE_ENV === "development" && process.env.EXPO_PUBLIC_WEB_API_PROXY === "1";
export const apiBaseUrl = webDevProxy && config.enabled ? "" : config.baseUrl;
/** Live APIs are the default; demo data requires an explicit test-build switch. */
export const apiEnabled = config.enabled;
