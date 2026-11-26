import { restoreVerifiedSession } from "./verifiedSession";
import type { ApiProfile, WebTokens } from "../api/auth";

const tokens: WebTokens = { accessToken: "fixture", csrfToken: "", tokenType: "Bearer", expiresIn: 300 };
const profile: ApiProfile = { id: "fixture", username: "fixture", email: null, displayName: "fixture", role: "USER", emailVerified: true, mfaEnabled: true };

test("server demotion overrides the previous ADMIN role", async () => {
  const auth = { refresh: jest.fn().mockResolvedValue(tokens), profile: jest.fn().mockResolvedValue(profile) };
  expect(await restoreVerifiedSession(auth, () => true)).toMatchObject({ session: { role: "USER" } });
  expect(auth.profile).toHaveBeenCalledWith("fixture");
});
test("profile failure never accepts refreshed credentials or stale admin authority", async () => {
  const auth = { refresh: jest.fn().mockResolvedValue(tokens), profile: jest.fn().mockRejectedValue(new Error("revoked")) };
  await expect(restoreVerifiedSession(auth, () => true)).rejects.toThrow("revoked");
});
test("logout during refresh prevents the profile call and session resurrection", async () => {
  let current = true;
  const auth = { refresh: async () => { current = false; return tokens; }, profile: jest.fn() };
  expect(await restoreVerifiedSession(auth, () => current)).toBeNull();
  expect(auth.profile).not.toHaveBeenCalled();
});
test("logout while profile is in flight discards an ADMIN response", async () => {
  let current = true;
  const auth = { refresh: async () => tokens, profile: async () => { current = false; return { ...profile, role: "ADMIN" as const }; } };
  expect(await restoreVerifiedSession(auth, () => current)).toBeNull();
});
test("no session publishes no identity", async () => {
  const auth = { refresh: async () => null, profile: jest.fn() };
  expect(await restoreVerifiedSession(auth, () => true)).toBeNull();
  expect(auth.profile).not.toHaveBeenCalled();
});

test("shared-cookie refresh cannot silently change the verified account", async () => {
  const auth = { refresh: jest.fn().mockResolvedValue(tokens), profile: jest.fn().mockResolvedValue({ ...profile, id: "other-fixture", role: "ADMIN" }) };
  await expect(restoreVerifiedSession(auth, () => true, profile.id)).rejects.toThrow("Tài khoản đăng nhập đã thay đổi.");
});

test("same-account refresh retains its verified identity while roles may change", async () => {
  const auth = { refresh: jest.fn().mockResolvedValue(tokens), profile: jest.fn().mockResolvedValue(profile) };
  expect(await restoreVerifiedSession(auth, () => true, profile.id)).toMatchObject({ userId: profile.id, session: { role: "USER" } });
});
