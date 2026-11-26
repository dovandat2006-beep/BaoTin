import {test,expect} from '@playwright/test';
test('draft continuity, account context, branding and private draft cleanup',async({page},info)=>{
 await page.route('**/downloads/android.json',r=>r.fulfill({status:404}));
 await page.route('**/api/v1/**',route=>{
 const path=new URL(route.request().url()).pathname;
 const json=path.endsWith('/web/login')?{accessToken:'fixture',csrfToken:'fixture'}:path.endsWith('/auth/me')?{id:'00000000-0000-4000-8000-000000000001',username:'tester',displayName:'tester',role:'USER',emailVerified:true,mfaEnabled:false}:path.endsWith('/unread-count')?{unreadCount:0}:path.endsWith('/preferences')?{riskChangeEnabled:true,reportStatusEnabled:true,pushEnabled:false,inAppEnabled:true}:{items:[],totalItems:0,totalPages:0,page:0,size:20};
 return route.fulfill({status:path.endsWith('/web/refresh')?401:200,json});});
 await page.goto('/');await page.getByLabel('Tùy chọn ứng dụng').click();const toggle=page.getByLabel(info.project.name.endsWith('dark')?'Chuyển sang giao diện tối':'Chuyển sang giao diện sáng');if(await toggle.isVisible())await toggle.click();await page.getByLabel('Đóng tùy chọn',{exact:true}).click();
 await expect(page.getByLabel('Đăng nhập hoặc đăng ký',{exact:true})).toBeVisible();await expect(page.getByTestId('top-bar').getByText(/^(Khách|Cá nhân)$/)).toHaveCount(0);
 const mobile=page.viewportSize()!.width<600;
 const nav=(name:string)=>page.getByRole(mobile?'tab':'button',{name,exact:true});
 const sms=page.getByPlaceholder('Dán nội dung tin nhắn');await sms.fill('Bản nháp riêng tư chưa gửi');await page.getByRole('tab',{name:'Đường dẫn',exact:true}).click();await page.getByPlaceholder('https://example.com').fill('https://example.com/draft');
 await nav('Tra cứu').click();await expect(page.getByLabel('Đối tượng cần tra cứu',{exact:true})).toBeVisible();await nav('Kiểm tra').click();await expect(sms).toHaveValue('Bản nháp riêng tư chưa gửi');await page.getByRole('tab',{name:'Đường dẫn',exact:true}).click();await expect(page.getByPlaceholder('https://example.com')).toHaveValue('https://example.com/draft');
 await expect(page.getByRole('img',{name:'BaoTin',exact:true})).toBeVisible();
 if(!mobile){await page.getByLabel(page.viewportSize()!.width<1200?'Mở rộng thanh bên':'Thu gọn thanh bên').click();await expect(page.getByRole('img',{name:'BaoTin',exact:true})).toHaveCount(1);}
 await nav('Lịch sử').click();await expect(page.getByText('Đăng nhập để xem lịch sử kiểm tra.',{exact:true})).toBeVisible();
 await page.waitForTimeout(200);await page.screenshot({path:`.redesign-artifacts/shell-polish-20261113/${info.project.name}.png`});
 await page.getByLabel('Email, số điện thoại hoặc username',{exact:true}).fill('tester');await page.getByLabel('Mật khẩu',{exact:true}).fill('not-a-real-password');await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();await expect(page.getByText('Lịch sử kiểm tra',{exact:true})).toBeVisible();await expect(page.getByLabel('Tài khoản của tester',{exact:true})).toBeVisible();await expect(page.getByTestId('top-bar').getByText('Cá nhân',{exact:true})).toHaveCount(0);
 await page.getByLabel('Tài khoản của tester').click();await page.getByRole('button',{name:'Đăng xuất',exact:true}).click();await expect(sms).toHaveValue('');await page.getByRole('tab',{name:'Đường dẫn',exact:true}).click();await expect(page.getByPlaceholder('https://example.com')).toHaveValue('');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 const stored=await page.evaluate(()=>JSON.stringify(localStorage));expect(stored).not.toContain('Bản nháp riêng tư');
});
