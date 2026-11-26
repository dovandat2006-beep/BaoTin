const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');

test('customer screens omit implementation explanations while retaining account actions', () => {
  const screens = ['AuthScreen', 'AccountProviderLinks', 'AccountSecurityPanel', 'AdminUsersScreen', 'NotificationDevicesPanel', 'OAuthCallbackBridge', 'ServiceUnavailableScreen'];
  const forbidden = ['Đăng nhập qua Gateway', 'có PKCE', 'binding trình duyệt', 'Mỗi API vẫn kiểm tra', 'Notification-Service;', 'chưa có API nghiệp vụ', 'kiểm tra cấu hình callback', 'PKCE đang chờ', 'step-up email cho phiên USER'];
  const copy = screens.map(name => fs.readFileSync(path.join(root, 'src/screens', name + '.tsx'), 'utf8')).join('\n');
  for (const phrase of forbidden) assert.ok(!copy.toLowerCase().includes(phrase.toLowerCase()), phrase);
  for (const action of ['Tiếp tục với Google', 'Tiếp tục với Facebook', 'Liên kết Google', 'Liên kết Facebook', 'Đăng ký thiết bị', 'Mã dự phòng']) assert.ok(copy.includes(action), action);
});
