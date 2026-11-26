export type AuthErrors = Partial<Record<"identifier" | "username" | "contact" | "password" | "confirmPassword" | "terms", string>>;

export function validateLogin(identifier: string, password: string): AuthErrors {
  const errors: AuthErrors = {};
  if (!identifier.trim()) errors.identifier = "Nhập email, số điện thoại hoặc username.";
  if (!password) errors.password = "Nhập mật khẩu.";
  return errors;
}

export function validateRegistration(input: { username: string; contact: string; password: string; confirmPassword: string; terms: boolean }, minPasswordLength = 8): AuthErrors {
  const errors: AuthErrors = {};
  if (!/^[a-zA-Z0-9._]{3,30}$/.test(input.username.trim())) errors.username = "Username cần 3–30 ký tự, chỉ gồm chữ, số, dấu chấm hoặc gạch dưới.";
  const contact = input.contact.trim();
  const email = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contact);
  const phone = /^\+?[0-9]{9,15}$/.test(contact.replace(/[\s()-]/g, ""));
  if (!email && !phone) errors.contact = "Nhập email hoặc số điện thoại hợp lệ.";
  if (input.password.length < minPasswordLength) errors.password = `Mật khẩu cần ít nhất ${minPasswordLength} ký tự.`;
  if (input.confirmPassword !== input.password) errors.confirmPassword = "Mật khẩu xác nhận chưa khớp.";
  if (!input.terms) errors.terms = "Bạn cần đồng ý điều khoản để đăng ký.";
  return errors;
}

export function displayNameFromIdentifier(value: string) {
  const normalized = value.trim();
  if (normalized.includes("@")) return normalized.split("@")[0] || "Người dùng";
  return normalized || "Người dùng";
}
