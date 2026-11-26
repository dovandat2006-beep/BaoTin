import {test,expect} from '@playwright/test';
test('shell gutters, theme, content bounds and protected navigation',async({page},info)=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',r=>r.fulfill({status:401,json:{status:401}}));await page.route('**/downloads/android.json',r=>r.fulfill({status:404}));await page.goto('/');
 await expect(page.getByText('Kiểm tra lừa đảo',{exact:true})).toBeVisible();
 await page.getByLabel('Tùy chọn ứng dụng').click();const desired=info.project.name.endsWith('dark');const toggle=page.getByLabel(desired?'Chuyển sang giao diện tối':'Chuyển sang giao diện sáng');if(await toggle.isVisible())await toggle.click();await page.getByLabel('Đóng tùy chọn',{exact:true}).click();
 const width=page.viewportSize()!.width;const gutter=width<600?16:width<840?24:width<1200?32:40;
 await expect(page.getByTestId('app-content')).toHaveCSS('padding-left',`${gutter}px`);await expect(page.getByTestId('top-bar')).toHaveCSS('padding-left',`${gutter}px`);
 await expect(page.getByTestId('app-content')).toHaveCSS('max-width','1280px');
 await expect(page.getByTestId('app-shell')).toHaveCSS('background-color',desired?'rgb(15, 21, 18)':'rgb(246, 250, 247)');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:`.redesign-artifacts/app-shell-20261113/${info.project.name}.png`});
 await page.getByRole(width<600?'tab':'button',{name:'Lịch sử',exact:true}).click();await expect(page.getByText('Đăng nhập BaoTin',{exact:true})).toBeVisible();await expect(page.getByTestId('app-content')).toHaveCSS('max-width','1120px');
 await page.getByRole(width<600?'tab':'button',{name:'Kiểm tra',exact:true}).click();await expect(page.getByPlaceholder('Dán nội dung tin nhắn')).toBeVisible();expect(errors).toEqual([]);
});
