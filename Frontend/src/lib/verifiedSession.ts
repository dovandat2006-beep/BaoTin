import { toAuthSession, type ApiProfile, type WebTokens } from "../api/auth";

/** Publish nothing until both refresh and the current server profile succeed.
 * The generation check discards responses that finish after logout/account change.
 */
export async function restoreVerifiedSession(
  auth: { refresh(): Promise<WebTokens | null>; profile(token: string): Promise<ApiProfile> },
  isCurrent: () => boolean,
  expectedUserId?: string | null
) {
  const tokens = await auth.refresh();
  if (!tokens || !isCurrent()) return null;
  const profile = await auth.profile(tokens.accessToken);
  if (!isCurrent()) return null;
  if (expectedUserId && profile.id !== expectedUserId) throw new Error("Tài khoản đăng nhập đã thay đổi.");
  return { token: tokens.accessToken, userId: profile.id, session: toAuthSession(profile) };
}
