import type { ProblemDetails } from "./contracts";

export type ApiRequestInit = RequestInit & { credentialAction?: boolean; timeoutMs?: number };
export type ApiClient = {
  request<T>(path: string, init?: ApiRequestInit, responseType?: "json" | "blob" | "empty"): Promise<T>;
};

function fallbackProblem(status: number, detail: string): ProblemDetails {
  return {
    type: "about:blank",
    title: "Request failed",
    status,
    detail,
    instance: "",
    code: "REQUEST_FAILED",
    correlationId: ""
  };
}

export async function problemFromResponse(response: Response, fallbackDetail = "Request failed"): Promise<ProblemDetails> {
  try {
    return await response.json() as ProblemDetails;
  } catch {
    return fallbackProblem(response.status, response.statusText || fallbackDetail);
  }
}

export function createApiClient(
  baseUrl: string,
  getAccessToken?: () => Promise<string | null>,
  refreshAccessToken?: () => Promise<string | null>,
  onUnauthorized?: () => void,
  onForbidden?: () => Promise<void>,
  getSessionVersion?: () => number
): ApiClient {
  const normalizedBase = baseUrl.replace(/\/$/, "");
  return {
    async request<T>(path: string, init: ApiRequestInit = {}, responseType: "json" | "blob" | "empty" = "json") {
      const { credentialAction, timeoutMs = 10000, ...fetchInit } = init;
      if (!Number.isFinite(timeoutMs) || timeoutMs < 1000 || timeoutMs > 120000) throw new Error("Thời gian chờ yêu cầu không hợp lệ.");
      const sessionVersion = getSessionVersion?.();
      const requireCurrentSession = () => {
        if (getSessionVersion && getSessionVersion() !== sessionVersion) throw new Error("Phiên đăng nhập đã thay đổi.");
      };
      const controller = new AbortController();
      let timedOut = false;
      const abort = () => controller.abort();
      if (init.signal?.aborted) controller.abort();
      init.signal?.addEventListener("abort", abort, { once: true });
      const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
      try {
      for (let attempt = 0; attempt < 2; attempt += 1) {
        const token = await getAccessToken?.();
        requireCurrentSession();
        const headers = new Headers(init.headers);
        headers.set("Accept", "application/json");
        if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
        if (token) headers.set("Authorization", `Bearer ${token}`);
        const response = await fetch(`${normalizedBase}${path}`, { ...fetchInit, headers, signal: controller.signal });
        requireCurrentSession();
        if (response.status === 401 && credentialAction) {
          // A wrong password/one-time factor also uses 401. Never replay it.
          // Recheck identity independently before discarding any valid session.
          if (onForbidden) await onForbidden(); else onUnauthorized?.();
          throw new ApiProblem(await problemFromResponse(response));
        }
        if (response.status === 401 && attempt === 0 && refreshAccessToken) {
          let refreshed: string | null;
          try {
            refreshed = await refreshAccessToken();
            requireCurrentSession();
          } catch (error) {
            // An obsolete request must not invalidate a newer account's session.
            requireCurrentSession();
            onUnauthorized?.();
            throw error;
          }
          if (refreshed) continue;
        }
        if (response.status === 401) onUnauthorized?.();
        if (response.status === 403) await onForbidden?.();
        if (!response.ok) throw new ApiProblem(await problemFromResponse(response));
        if (responseType === "empty" || response.status === 204) return undefined as T;
        const result = await (responseType === "blob" ? response.blob() : response.json()) as T;
        requireCurrentSession();
        return result;
      }
      throw new Error("Request could not be completed");
      } catch (error) {
        if (timedOut) throw new Error("Kết nối quá thời gian chờ. Hãy kiểm tra danh sách trước khi thử lại để tránh gửi trùng.");
        if (controller.signal.aborted) throw new Error("Yêu cầu đã bị hủy. Hãy thử lại khi kết nối ổn định.");
        throw error;
      } finally {
        clearTimeout(timeout);
        init.signal?.removeEventListener("abort", abort);
      }
    }
  };
}

export class ApiProblem extends Error {
  constructor(public readonly problem: ProblemDetails) {
    super(problem.detail || problem.title);
    this.name = "ApiProblem";
  }
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiProblem) return error.problem.detail || error.problem.title || fallback;
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}
