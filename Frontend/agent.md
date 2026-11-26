# BaoTin Frontend - Hướng dẫn cho agent

## Phạm vi

- Chỉ sửa mã trong `M:\BaoTin\Frontend` khi làm frontend.
- Backend business scaffold không thuộc đợt nâng SDK; chỉ phối hợp contract/Infra/config khi người dùng duyệt rõ. Đợt SDK58 đã duyệt audit API, cấu hình secret và Jenkins; không phát triển thêm Search/Campaign/Analytics.
- Không chạy lệnh xóa file/thư mục. Muốn xóa phải có chấp thuận rõ ràng từ người dùng.
- Không đưa dữ liệu bí mật hoặc `.env` vào output, test, log hay mock data.

## Stack

- Expo SDK58, React Native0.88, TypeScript strict; phiên bản native modules theo bundledNativeModules của SDK.
- Một codebase cho Web, Android và iOS.
- NativeWind 4.2.7 + Tailwind CSS 3.4.17 là nhánh ổn định cho Expo 57.
- `tokens.css` giữ bản token tương thích Tailwind CSS v4 cho web.
- Font: Space Grotesk cho display, Manrope cho body.
- Icon: Font Awesome qua `@fortawesome/react-native-fontawesome` và `@fortawesome/free-solid-svg-icons`.

## Quy tắc icon

- Tuyệt đối không dùng emoji hoặc ký tự Unicode làm icon trong UI.
- Repo tham khảo chỉ dùng emoji, không có thư viện icon hợp lệ; vì vậy BaoTin dùng Font Awesome.
- Không trộn Font Awesome với Phosphor, Lucide, Material Icons hoặc SVG tự vẽ.
- Icon phải có nhãn truy cập ở nút cha khi ý nghĩa không thể hiện bằng text.

## Dữ liệu và hành vi

- Mặc định dùng API thật qua Gateway; chỉ test-build tường minh mới dùng dữ liệu fixture. API lỗi không chuyển sang mock.
- Logic điểm rủi ro giả nằm ở `src/lib/risk.ts`; không mô tả nó như kết luận thật.
- Luồng UI hiện có: 6 loại scan, signal breakdown, snapshot/current reputation, search projection, lịch sử, report state/evidence, watchlist, notifications và preferences.
- `src/api/` bám endpoint công khai đã được ghi trong Backend Architecture; UI vẫn chạy mock cho tới khi backend được mở khóa.
- AI là tín hiệu tham khảo, không phải bộ ra quyết định cuối cùng.
- Login/register và Google/Facebook SSO UI đã có adapter giả; chưa gọi backend. Guest scan được phép, còn history, watchlist và report yêu cầu phiên giao diện.
- `TODO.md` là backlog cho i18n, quên mật khẩu và kết nối Auth Service. Dark mode đã có toggle, persistence và semantic light/dark tokens.

## Design system

- Tone: technical-soft, trust-first, modern-minimal.
- Palette light: canvas `#FAFAFA`, surface `#FFFFFF`, Material Teal `#00796B`, tonal `#E0F2F1`, text `#212121`, divider `#E0E0E0`.
- Palette dark: canvas `#101413`, surface `#171C1B`, accent `#80CBC4`, text `#F4F7F6`, divider `#2B3533`.
- Không dùng gradient, glow, glassmorphism hoặc nhiều accent trên cùng màn hình.
- Macrostructure: Workbench; desktop dùng side rail, mobile dùng bottom navigation.
- Dùng token có tên; không tự thêm màu/font rời trong component.
- Kiểm tra responsive tại 320, 375, 414, 768 và 1440 px.
- Không horizontal scroll. CTA và nav label không xuống hai dòng.
- Interactive control cần focus-visible, active, disabled và nhãn accessibility phù hợp.

## Skill cục bộ

Chỉ 7 skill dưới đây được vendored tại `.agents/skills/`:

1. `tailwind-design-system`
2. `hallmark`
3. `high-end-visual-design`
4. `design-taste-frontend`
5. `gpt-taste`
6. `graphify`
7. `caveman`

Khi người dùng gọi một skill, đọc `.agents/skills/<skill>/SKILL.md` và các file `references` liên quan trước khi sửa.

## Lệnh kiểm tra

```bash
npm run typecheck
npm test
npm run build:web
npm run build:android:bundle
npm run build:ios:bundle
docker compose -f docker-compose.prod.yml up --build
```

`--no-bytecode` chỉ dùng để xác minh bundle trên Windows khi `hermesc.exe` lỗi. Build phát hành nên dùng EAS/native toolchain chuẩn với Hermes.

## Artifact kiểm chứng

- `VERIFICATION.txt`: lệnh và kết quả kiểm tra.
- `BAOTIN_FRONTEND.diff`: diff frontend đã tạo.
- `ROLLBACK.sh`: khôi phục `App.tsx` trên bản sao kiểm thử.
- `.agents/skills/COPY_VERIFICATION.json`: số file và trạng thái hash của 7 skill.

