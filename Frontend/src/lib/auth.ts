export type AuthRole = "USER" | "MODERATOR" | "ADMIN";

export type AuthSession = {
  displayName: string;
  method: "password" | "google" | "facebook";
  role: AuthRole;
};

/** Chỉ ADMIN được mở Quản trị; MODERATOR kiểm duyệt tại Báo cáo. Server kiểm tra mọi request. */
export function canAccessAdmin(session: Pick<AuthSession, "role"> | null | undefined): boolean {
  return session?.role === "ADMIN";
}

/**
 * Lớp chặn cuối tại điểm render: nếu section Quản trị bị đặt trực tiếp mà phiên
 * không có vai trò phù hợp, hạ xuống `scan` (đã đăng nhập) hoặc `auth` (chưa).
 */
export function resolveVisibleSection<S extends string>(
  section: S,
  session: Pick<AuthSession, "role"> | null | undefined
): S | "auth" | "scan" {
  if (section !== "admin" || canAccessAdmin(session)) return section;
  return session ? "scan" : "auth";
}
