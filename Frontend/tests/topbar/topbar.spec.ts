import {test,expect} from '@playwright/test';

test('options, theme persistence, notifications, account and verified download',async({page},info)=>{
 await page.route('**/api/**',r=>r.fulfill({status:401,json:{status:401}}));
 const sha='a'.repeat(64);
 await page.route('**/downloads/android.json',r=>r.fulfill({json:{schemaVersion:1,version:'0.1.0',versionCode:1,bytes:4,sha256:sha,url:`/downloads/baotin-${sha}.apk`,sourceSnapshotSha256:'b'.repeat(64),gatewayOrigin:'https://baotin.rimiprod.me',signingCertificateSha256:'c'.repeat(64),publishedAt:'2026-11-13T00:00:00Z'}}));
 await page.goto('/');
 await expect(page.getByLabel('Thông báo, 0 chưa đọc')).toBeVisible();
 await expect(page.getByTestId('notification-count')).toHaveCount(0);
 await expect(page.getByTestId('top-bar').getByLabel('Tải APK Android')).toHaveCount(0);
 await page.getByLabel('Tùy chọn ứng dụng').click();
 const desired=info.project.name.endsWith('dark');
 const toggle=page.getByLabel(desired?'Chuyển sang giao diện tối':'Chuyển sang giao diện sáng');
 if(await toggle.isVisible())await toggle.click();
 await expect(page.locator('html')).toHaveClass(desired?/dark/:/^(?!.*dark)/);
 await page.waitForTimeout(250);await page.screenshot({path:`.redesign-artifacts/topbar-20261113/${info.project.name}-options.png`});
 await page.getByLabel('Tải APK Android',{exact:true}).click();
 await expect(page.getByText('Tải ứng dụng Android',{exact:true})).toBeVisible();
 await expect(page.getByText(sha,{exact:true})).toBeVisible();
 await page.getByLabel('Đóng tải APK',{exact:true}).click();
 await expect(page.getByText('Tải ứng dụng Android',{exact:true})).toBeHidden();
 await page.waitForTimeout(400); // Existing compact Sheet completes its dismissal before opening another modal.
 await page.getByLabel('Tùy chọn ứng dụng').click();
 await expect(page.getByTestId('topbar-options')).toBeVisible();
 await page.waitForTimeout(100);
 await page.keyboard.press('Escape');
 await expect(page.getByLabel('Tùy chọn ứng dụng')).toBeFocused();
 await page.waitForTimeout(250);await page.screenshot({path:`.redesign-artifacts/topbar-20261113/${info.project.name}.png`});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.reload();await expect(page.locator('html')).toHaveClass(desired?/dark/:/^(?!.*dark)/);
 await page.getByLabel('Thông báo, 0 chưa đọc').click();await expect(page.getByText('Chưa có thông báo',{exact:true})).toBeVisible();await page.getByLabel('Đóng bảng thông báo').click();
 await page.getByLabel('Đăng nhập hoặc đăng ký').click();await expect(page.getByText('Đăng nhập BaoTin',{exact:true})).toBeVisible();
});

test('large unread count keeps exact accessible total; invalid APK stays absent',async({page})=>{
 await page.route('**/downloads/android.json',r=>r.fulfill({json:{schemaVersion:1,url:'https://other.example/file.apk'}}));
 await page.route('**/api/v1/**',route=>{
  const path=new URL(route.request().url()).pathname;
  const body=path.endsWith('/web/login')?{accessToken:'fixture',csrfToken:'fixture'}:path.endsWith('/auth/me')?{id:'00000000-0000-4000-8000-000000000001',displayName:'tester',role:'USER'}:path.endsWith('/unread-count')?{unreadCount:125}:path.endsWith('/preferences')?{riskChangeEnabled:true,reportStatusEnabled:true,pushEnabled:false,inAppEnabled:true}:{items:[],totalItems:0,totalPages:0,page:0,size:20};
  return route.fulfill({status:path.endsWith('/web/refresh')?401:200,json:body});
 });
 await page.goto('/');await page.getByLabel('Đăng nhập hoặc đăng ký').click();await page.getByLabel('Email, số điện thoại hoặc username',{exact:true}).fill('tester');await page.getByLabel('Mật khẩu',{exact:true}).fill('not-a-real-password');await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();
 await expect(page.getByLabel('Thông báo, 125 chưa đọc')).toBeVisible();await expect(page.getByTestId('notification-count')).toHaveText('99+');
 await page.getByLabel('Tùy chọn ứng dụng').click();await expect(page.getByLabel('Tải APK Android',{exact:true})).toHaveCount(0);await page.getByLabel('Đóng tùy chọn',{exact:true}).click();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
