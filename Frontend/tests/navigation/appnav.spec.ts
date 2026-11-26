import { expect, test } from '@playwright/test';

test('adaptive navigation preserves guest and ADMIN boundaries',async({page},info)=>{
 let role='USER';
 await page.route('**/api/v1/**',route=>{
  const path=new URL(route.request().url()).pathname;
  const json=path.endsWith('/web/login')?{accessToken:'fixture',csrfToken:'fixture'}:path.endsWith('/auth/me')?{id:'00000000-0000-4000-8000-000000000001',username:'tester',displayName:'tester',role,emailVerified:true,mfaEnabled:true}:path.endsWith('/preferences')?{riskChangeEnabled:true,reportStatusEnabled:true,pushEnabled:false,inAppEnabled:true}:path.endsWith('/unread-count')?{unreadCount:0}:{items:[],page:0,size:20,totalItems:0,totalPages:0};
  return route.fulfill({status:path.endsWith('/web/refresh')?401:200,json});
 });
 await page.goto('/');
 await expect(page.getByText('Kiểm tra lừa đảo',{exact:true})).toBeVisible();
 await page.getByLabel("Tùy chọn ứng dụng").click();
 const toggle=page.getByLabel(info.project.name.endsWith('dark')?'Chuyển sang giao diện tối':'Chuyển sang giao diện sáng');if(await toggle.isVisible())await toggle.click();
 await page.getByLabel("Đóng tùy chọn",{exact:true}).click();
 const mobile=(page.viewportSize()?.width??0)<600;
 if(mobile){
  await expect(page.getByTestId('bottom-navigation').getByRole('tab')).toHaveCount(4);
  await page.getByRole('button',{name:'Thêm',exact:true}).click();
  await expect(page.getByTestId('navigation-more').getByRole('button',{name:'Theo dõi',exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'Quản trị',exact:true})).toHaveCount(0);
  await page.waitForTimeout(250);await page.screenshot({path:`.redesign-artifacts/appnav-20261113/${info.project.name}-more.png`});
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button',{name:'Thêm',exact:true})).toBeFocused();
 }else{
  await expect(page.getByTestId('sidebar')).toHaveCSS('width',(page.viewportSize()!.width<1200?'80px':'248px'));
  const name=page.viewportSize()!.width<1200?'Mở rộng thanh bên':'Thu gọn thanh bên';await page.getByLabel(name).click();
  await expect(page.getByTestId('sidebar')).toHaveCSS('width',page.viewportSize()!.width<1200?'248px':'80px');
  await page.getByLabel(page.viewportSize()!.width<1200?'Thu gọn thanh bên':'Mở rộng thanh bên').click();
 }
 await page.waitForTimeout(250);await page.screenshot({path:`.redesign-artifacts/appnav-20261113/${info.project.name}.png`});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.getByRole(mobile?'tab':'button',{name:'Lịch sử',exact:true}).click();
 await expect(page.getByText('Đăng nhập BaoTin',{exact:true})).toBeVisible();
 role='ADMIN';
 await page.getByLabel('Email, số điện thoại hoặc username',{exact:true}).fill('tester');
 await page.getByLabel('Mật khẩu',{exact:true}).fill('not-a-real-password');
 await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();
 await expect(page.getByLabel('Tài khoản của tester')).toBeVisible();
 if(mobile)await page.getByRole('button',{name:'Thêm',exact:true}).click();
 await page.getByRole('button',{name:'Quản trị',exact:true}).click();
 await expect(page.getByText('Quản lý người dùng',{exact:true})).toBeVisible();
 if(mobile){await expect(page.getByRole('button',{name:'Thêm',exact:true})).toHaveAttribute('aria-current','page');await page.getByRole('button',{name:'Thêm',exact:true}).click();}
 await page.getByRole('button',{name:'Theo dõi',exact:true}).click();
 await expect(page.getByRole('button',{name:'Thêm theo dõi',exact:true})).toBeVisible();
});
