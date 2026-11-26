# Báo cáo xây dựng ứng dụng BaoTin — Mục 3.4 đến 3.6

**Ngày đối chiếu mã nguồn:** 02/11/2026 (giờ Việt Nam).  
**Phạm vi:** giao tiếp API và trạng thái giao diện; lưu trữ dữ liệu cục bộ; kiểm thử và mô phỏng chức năng, theo hai ảnh yêu cầu.  
**Phương pháp:** chỉ đọc mã, cấu hình và mã kiểm thử hiện có. Không sửa code, không cài dependency, không build, không chạy ứng dụng, không gọi API và không thực thi bộ kiểm thử.

Tài liệu này bổ sung cho [báo cáo chương 3 hiện có](/Users/dovandat/BaoTin/BAO_CAO_CHUONG_3_XAY_DUNG_UNG_DUNG.md). Nội dung phản ánh bản mã đang có trong workspace, không xác nhận có phiên bản mới hơn trên remote. Các commit cục bộ được đối chiếu gồm Frontend `2f6bb8c`, Threat-Service `8d5a594`, API-Gateway `d609069` và Contracts `19d0f5a`; các commit này có ngày 22/10/2026.

“Đã tích hợp” trong tài liệu nghĩa là có đường gọi HTTP từ giao diện trong mã. “Demo” nghĩa là dữ liệu mẫu hoặc thao tác trong bộ nhớ. “Chỉ có adapter” nghĩa là có hàm gọi API nhưng màn hình chưa sử dụng. Các trạng thái này không thay thế kết quả chạy thực tế.

## 3.4. Giao tiếp API và loading/success/error

### 3.4.1. API/backend được sử dụng và mục đích

BaoTin sử dụng một frontend Expo, React Native và TypeScript. Các request nghiệp vụ từ frontend hướng đến API Gateway qua tiền tố `/api/v1`; frontend không gọi trực tiếp endpoint nội bộ của mô hình AI. Trong phạm vi đã nối, Gateway nhận request quét và lịch sử để chuyển đến Threat-Service; các luồng xác thực dùng Auth-Service, còn theo dõi URL và thông báo dùng Notification-Service.

Mã hiện tại phân chia chức năng như sau:

| Nhóm chức năng | Nguồn dữ liệu và mục đích | Trạng thái trong giao diện |
|---|---|---|
| Quét SMS/văn bản | Gửi nội dung để backend phân tích và trả snapshot kết quả | Có gọi `POST /api/v1/scans/text`; không tạo kết quả bằng bộ chấm điểm mock |
| Quét URL | Gửi URL để phân tích và nhận thông tin rủi ro, rule/model và URL chuẩn hóa từ backend | Có gọi `POST /api/v1/scans/url` |
| Lịch sử quét | Lấy snapshot đã lưu theo người dùng, lọc loại, phân trang và xóa bản ghi | Có API thật; màn hình yêu cầu `apiEnabled && authenticated` |
| Đăng nhập, đăng ký, MFA | Tạo/khôi phục phiên web, lấy hồ sơ và vai trò do server trả về | Có API khi cấu hình `EXPO_PUBLIC_API_URL`; ngoài chế độ này là phiên demo |
| Theo dõi URL | Tải danh sách, thêm URL và bỏ theo dõi | Có API khi bật chế độ API; ngoài chế độ này dùng danh sách mẫu và xóa trong state |
| Thông báo và tùy chọn | Lấy danh sách, đánh dấu đã đọc, cập nhật tùy chọn | Có API khi bật chế độ API; danh sách được poll mỗi 30 giây, bỏ qua khi tab web bị ẩn |
| Tra cứu | Lọc dữ liệu theo từ khóa, loại đối tượng và mức rủi ro | Màn hình vẫn lọc `searchResults` từ `mock.ts`, chưa gọi Search API |
| Tố giác | Nhập form, thêm bản ghi và hiển thị trạng thái demo | Chỉ cập nhật state trong `AppShell`; chưa gọi API tạo/lấy báo cáo |
| Quản trị | Hiển thị và lọc hàng chờ minh họa | Dữ liệu demo; chưa nối Analytics API |

Nguồn: [AppShell: khởi tạo client](/Users/dovandat/BaoTin/Frontend/App.tsx:155), [tải dữ liệu và polling](/Users/dovandat/BaoTin/Frontend/App.tsx:163), [ghép màn hình đang dùng](/Users/dovandat/BaoTin/Frontend/App.tsx:457), [SearchScreen](/Users/dovandat/BaoTin/Frontend/src/screens/SearchScreen.tsx:9), [AdminDashboardScreen](/Users/dovandat/BaoTin/Frontend/src/screens/AdminDashboardScreen.tsx:70).

`EXPO_PUBLIC_API_URL` được đọc và chuẩn hóa trong [apiConfig.ts](/Users/dovandat/BaoTin/Frontend/src/lib/apiConfig.ts:1). Khi biến này rỗng, Auth/Notification/Watchlist không vào nhánh API. Tuy nhiên, quét vẫn gọi đường dẫn tương đối `/api/v1/scans/...` bằng `guestScanApi`; do đó vẫn cần Gateway truy cập được qua cùng origin. Cấu hình [nginx.conf](/Users/dovandat/BaoTin/Frontend/nginx.conf:18) có reverse proxy `/api/`. Không nên diễn giải “không đặt biến API” thành “mọi chức năng đều chạy mock”.

Màn hình hiện tại chỉ cho chọn **SMS/văn bản và URL**. Các loại PHONE, BANK_ACCOUNT, EMAIL hoặc SOCIAL_ACCOUNT còn xuất hiện trong kiểu dữ liệu/form demo không chứng minh có luồng quét thật tương ứng. Nguồn: [ScanComposer](/Users/dovandat/BaoTin/Frontend/src/components/ScanComposer.tsx:9).

### 3.4.2. Module chịu trách nhiệm gọi API

| Module | Trách nhiệm thực tế |
|---|---|
| [src/api/client.ts](/Users/dovandat/BaoTin/Frontend/src/api/client.ts:27) | Transport nghiệp vụ bằng `fetch`; chuẩn hóa base URL; gắn header JSON và Bearer token; timeout 10 giây; nhận tín hiệu hủy; refresh rồi thử lại tối đa một lần khi gặp 401; xử lý 204 và lỗi `ApiProblem` |
| [src/api/baotin.ts](/Users/dovandat/BaoTin/Frontend/src/api/baotin.ts:23) | Gom các phương thức scan, history, report, search, notification và watchlist; tạo query/body và encode ID |
| [src/api/contracts.ts](/Users/dovandat/BaoTin/Frontend/src/api/contracts.ts:148) | Khai báo kiểu request/response và đường dẫn endpoint dùng ở frontend |
| [src/api/auth.ts](/Users/dovandat/BaoTin/Frontend/src/api/auth.ts:60) | Client xác thực web riêng; request dùng `credentials: "include"`; đăng ký, đăng nhập, MFA, lấy hồ sơ, refresh và logout; quản lý CSRF token trong bộ nhớ |
| [src/api/scan.ts](/Users/dovandat/BaoTin/Frontend/src/api/scan.ts:24) | Chuyển snapshot thành dữ liệu trình bày bằng `presentScan`; ánh xạ lỗi thành thông báo tiếng Việt; giữ nguyên điểm/mức rủi ro server |
| [App.tsx](/Users/dovandat/BaoTin/Frontend/App.tsx:108) | Giữ access token trong `tokenRef`; chia sẻ tác vụ refresh đang chạy; tạo client có/không có token; tải watchlist, thông báo và tùy chọn |
| [ScanScreen](/Users/dovandat/BaoTin/Frontend/src/screens/ScanScreen.tsx:35), [ScanHistoryScreen](/Users/dovandat/BaoTin/Frontend/src/screens/ScanHistoryScreen.tsx:22) | Gọi phương thức nghiệp vụ và quản lý state tải, kết quả, lỗi, bộ lọc, trang và thao tác xóa |

Timeout 10 giây thuộc `createApiClient`, không phải quy tắc đã áp dụng cho mọi request: client `auth.ts` sử dụng `fetch` riêng và chưa có cùng cơ chế timeout này. Lỗi HTTP được đọc từ body JSON; nếu không đọc được JSON, `problemFromResponse` tạo lỗi dự phòng theo HTTP status. Với các API nghiệp vụ có refresh callback, request 401 chỉ được thử lại khi refresh trả về token mới.

### 3.4.3. Bảng endpoint được giao diện thực sự sử dụng

Các đường dẫn dưới đây là đường dẫn public mà frontend gọi. Nhóm Auth, Watchlist và Notification phụ thuộc chế độ API và phiên tương ứng.

| Method | Endpoint | Mục đích | Màn hình/luồng |
|---|---|---|---|
| POST | `/api/v1/scans/text` | Quét SMS/văn bản với body `{ content }` | Kiểm tra — SMS |
| POST | `/api/v1/scans/url` | Quét URL với body `{ url }` | Kiểm tra — URL |
| GET | `/api/v1/scans/history?page=0&size=20&sort=createdAt%2Cdesc` | Lấy lịch sử; có thể thêm `type=TEXT` hoặc `type=URL` theo bộ lọc | Lịch sử kiểm tra |
| DELETE | `/api/v1/scans/history/{id}` | Xóa snapshot của người dùng hiện tại | Lịch sử kiểm tra |
| POST | `/api/v1/auth/register` | Đăng ký tài khoản | Đăng ký |
| POST | `/api/v1/auth/web/login` | Đăng nhập; nhận token hoặc challenge MFA | Đăng nhập; đăng nhập sau khi đăng ký |
| POST | `/api/v1/auth/web/mfa/challenges` | Hoàn tất challenge bằng mã MFA | Bước xác thực MFA |
| GET | `/api/v1/auth/me` | Lấy hồ sơ và vai trò bằng access token | Hoàn tất/khôi phục phiên |
| POST | `/api/v1/auth/web/refresh` | Khôi phục/gia hạn phiên bằng cookie và CSRF token | Khởi động ứng dụng; xử lý 401 |
| POST | `/api/v1/auth/web/logout` | Yêu cầu kết thúc phiên web | Tài khoản — Đăng xuất |
| GET | `/api/v1/watchlist?page=0&size=100` | Lấy danh sách URL theo dõi | Theo dõi |
| POST | `/api/v1/watchlist` | Thêm URL với body `{ url }` | Form thêm theo dõi |
| DELETE | `/api/v1/watchlist/{id}` | Bỏ theo dõi URL | Theo dõi |
| GET | `/api/v1/notifications?page=0&size=100&unread=false` | Lấy danh sách thông báo | Bảng thông báo; tải nền/polling |
| PATCH | `/api/v1/notifications/{id}/read` | Đánh dấu một thông báo đã đọc | Mở thông báo |
| POST | `/api/v1/notifications/read-all` | Đánh dấu tất cả đã đọc | Bảng thông báo |
| GET | `/api/v1/notifications/preferences` | Tải tùy chọn thông báo | Theo dõi — Thông báo |
| PATCH | `/api/v1/notifications/preferences` | Cập nhật các tùy chọn thông báo | Các công tắc trong mục Theo dõi |

Nguồn: [adapter nghiệp vụ](/Users/dovandat/BaoTin/Frontend/src/api/baotin.ts:23), [hằng endpoint](/Users/dovandat/BaoTin/Frontend/src/api/contracts.ts:148), [adapter xác thực](/Users/dovandat/BaoTin/Frontend/src/api/auth.ts:91), [các callback trong AppShell](/Users/dovandat/BaoTin/Frontend/App.tsx:372).

**Các endpoint chưa được màn hình tương ứng gọi:**

| Method | Endpoint | Phạm vi xác nhận |
|---|---|---|
| GET | `/api/v1/scans/history/{id}` | Có `scanHistoryDetail` trong adapter; màn hình mở rộng kết quả từ snapshot đã có trong danh sách |
| GET | `/api/v1/reports/me` | Có `myReports` trong adapter; ReportsScreen vẫn nhận dữ liệu từ state demo |
| POST | `/api/v1/reports` | Có `createReport` và header `Idempotency-Key` trong adapter; form đang dùng chưa gọi hàm này |
| GET | `/api/v1/search` | Có `search` trong adapter; SearchScreen vẫn lọc dữ liệu mẫu |
| GET | `/api/v1/notifications/unread-count` | Có adapter; badge hiện được đếm từ danh sách thông báo đã tải vào state |
| DELETE | `/api/v1/notifications/{id}` | Có adapter; bảng thông báo hiện không nối thao tác xóa |
| GET/POST | `/api/v1/notifications/devices` | Có adapter liệt kê/đăng ký thiết bị; chưa nối đăng ký device token trong giao diện |
| DELETE | `/api/v1/notifications/devices/{id}` | Có adapter; chưa nối giao diện quản lý thiết bị |

Không có lời gọi **PUT** trong hai client `baotin.ts` và `auth.ts` đang khảo sát. Không bổ sung dòng PUT chỉ để đủ mẫu CRUD: cập nhật tùy chọn và trạng thái đã đọc đang dùng PATCH; lịch sử quét không có thao tác sửa nội dung trên UI. Ngoài ra, backend còn có route tương thích `POST /api/v1/scans` và route xóa toàn bộ lịch sử, nhưng các route này không được giao diện đang dùng gọi. Nguồn: [ScanController](/Users/dovandat/BaoTin/Threat-Service/src/main/java/com/baotin/threat/api/ScanController.java:25).

### 3.4.4. Luồng tiêu biểu: người dùng gửi SMS để kiểm tra

```text
Người dùng nhập SMS → bấm “Kiểm tra ngay”
  → ScanScreen.scan()
  → scanning=true; result=null; scanError=null
  → api.scan({ type: "SMS", content }, AbortSignal)
  → createBaoTinApi chuyển thành POST /api/v1/scans/text với { content }
  → createApiClient gọi Gateway, gắn token nếu có
  → nhận snapshot từ backend
  → presentScan(response)
  → setResult(...) nếu request vẫn là request hiện tại
  → scanning=false → hiển thị RiskResult
```

Quá trình cụ thể:

1. `ScanComposer` kiểm tra dữ liệu không rỗng và giới hạn độ dài: SMS tối đa 10.000 ký tự, URL tối đa 4.096 ký tự. Nút quét bị vô hiệu khi dữ liệu chưa hợp lệ.
2. `ScanScreen.scan()` chặn gửi khi đang quét hoặc đầu vào chỉ có khoảng trắng. Mỗi lần gửi tăng `requestId`, xóa kết quả/lỗi cũ và tạo `AbortController` mới.
3. Adapter ánh xạ loại SMS của giao diện thành endpoint `/text`. Nội dung trong body được giữ như người dùng nhập; frontend không tự tính điểm thay backend.
4. `createApiClient` gửi request. Nếu có token thì thêm Bearer token; gặp 401 có thể refresh rồi thử lại một lần. Request được giới hạn bằng timeout 10 giây của client nghiệp vụ.
5. Nếu thành công, `presentScan` giữ nguyên `risk.score`, `risk.level`, model, rule và snapshot để trình bày. Thiếu AI hoặc phân tích chưa đầy đủ phải được diễn giải tương ứng, không coi điểm fallback thấp là bảo đảm an toàn.
6. Nếu thất bại, màn hình lưu thông báo lỗi và hiện nút “Thử lại”. Khối `finally` kết thúc trạng thái tải khi request vẫn còn hiện hành.
7. Khi sửa nội dung, đổi loại quét hoặc rời màn hình, mã tăng `requestId` và hủy request. Response cũ không được ghi đè kết quả của đầu vào mới.

Nguồn: [validation đầu vào](/Users/dovandat/BaoTin/Frontend/src/components/ScanComposer.tsx:14), [quản lý request và state](/Users/dovandat/BaoTin/Frontend/src/screens/ScanScreen.tsx:17), [adapter scan](/Users/dovandat/BaoTin/Frontend/src/api/baotin.ts:25), [trình bày snapshot](/Users/dovandat/BaoTin/Frontend/src/api/scan.ts:24).

### 3.4.5. Loading, success, error và empty state

| Chức năng | Loading | Success | Error | Empty/chưa có dữ liệu |
|---|---|---|---|---|
| Quét SMS/URL | `scanning=true`; nút có trạng thái đang phân tích; hiện “Đang kiểm tra” | Hiện `RiskResult` tại màn hình hiện tại, không tự chuyển trang | Hiện thông báo tiếng Việt và nút “Thử lại” | Ban đầu hiện “Chưa có kết quả.” |
| Lịch sử | Hiện “Đang tải lịch sử”; ẩn các dòng dữ liệu khi đang tải | Gán dữ liệu trang; “Xem kết quả lúc quét” mở chi tiết ngay trong dòng | Hiện `ErrorPanel` và nút thử lại | Khi tải xong và `items=[]`, hiện “Chưa có lịch sử” |
| Xóa lịch sử | Nút của dòng đang xóa có loading; khóa các nút xóa khác | Đóng dòng mở rộng và tải lại; nếu xóa phần tử cuối của trang sau trang đầu thì lùi một trang | Hiện lỗi; không tự xóa dòng khỏi dữ liệu trước response thành công | Sau khi tải lại, danh sách có thể chuyển sang rỗng |
| Auth API | `submitting=true` trong khi gọi API | Nhận session rồi về mục chờ đăng nhập hoặc Kiểm tra; nếu có MFA thì mở bước nhập mã | Hiện lỗi form; kết thúc submitting | Chưa đăng nhập thì hiện form |
| Theo dõi | Loading danh sách; loading riêng khi thêm/xóa | Thêm URL vào đầu state và đóng form; xóa khỏi state sau API thành công | Lỗi tải có thử lại; lỗi thêm nằm ở form; lỗi thao tác ở `ErrorPanel` | Hiện “Chưa theo dõi đối tượng” và hành động thêm URL |
| Thông báo | Loading khi tải chủ động; polling nền không bật loading | Cập nhật danh sách và badge; đọc thông báo có thể chuyển đến Theo dõi/Tố giác | Lỗi tải chủ động có thử lại; lỗi đánh dấu đọc được thông báo rồi tải lại nền | Bảng có trạng thái chưa có thông báo |
| Tùy chọn thông báo | Loading khi tải; khóa công tắc khi đang lưu một tùy chọn | Cập nhật state theo response PATCH | Khi PATCH lỗi, khôi phục state trước đó và hiện lỗi thao tác | Nếu chưa có preferences từ server, component có giá trị dự phòng trong bộ nhớ |

Các component dùng chung [LoadingPanel và ErrorPanel](/Users/dovandat/BaoTin/Frontend/src/components/ui/AsyncState.tsx:6) khai báo vai trò truy cập `progressbar` và `alert`. Màn hình quét còn có `accessibilityLiveRegion="polite"` để cập nhật kết quả. Nguồn trạng thái chi tiết: [ScanScreen](/Users/dovandat/BaoTin/Frontend/src/screens/ScanScreen.tsx:60), [ScanHistoryScreen](/Users/dovandat/BaoTin/Frontend/src/screens/ScanHistoryScreen.tsx:31), [WatchlistScreen](/Users/dovandat/BaoTin/Frontend/src/screens/WatchlistScreen.tsx:78), [NotificationSheet](/Users/dovandat/BaoTin/Frontend/src/components/NotificationSheet.tsx:11).

Lỗi quét/lịch sử được ánh xạ trong [scanErrorMessage](/Users/dovandat/BaoTin/Frontend/src/api/scan.ts:45):

| Tình huống | Phản hồi giao diện |
|---|---|
| HTTP 400 | Báo nội dung hoặc URL chưa hợp lệ, yêu cầu kiểm tra lại |
| HTTP 401 | Báo phiên đăng nhập hết hạn, yêu cầu đăng nhập lại |
| HTTP 413 | Báo nội dung quá dài |
| HTTP 429 | Báo quét quá nhiều hoặc hệ thống bận; đề nghị thử lại sau |
| HTTP 404 | Báo kết quả không còn trong lịch sử, đề nghị tải lại |
| Lỗi khác, mất kết nối hoặc timeout | Báo chưa kết nối được máy chủ, đề nghị kiểm tra kết nối và thử lại |

Một số giới hạn cần ghi đúng theo mã: lịch sử giữ `data` cũ khi lần tải mới thất bại nên có thể hiện dữ liệu cũ cùng lỗi sau khi loading kết thúc; polling thông báo thất bại ở chế độ silent không tạo lỗi tải mới trên UI. Với 401 không khôi phục được, một số callback watchlist/notification gọi `expireSession()`; ScanScreen/ScanHistoryScreen chỉ hiện thông báo qua `scanErrorMessage`, không tự thực hiện cùng bước dọn phiên đó.

## 3.5. Lưu trữ dữ liệu cục bộ

### 3.5.1. Dữ liệu cần lưu và giải pháp thực tế

Phần lưu cục bộ được triển khai rõ ràng trong frontend hiện tại là **lựa chọn giao diện sáng/tối**. Đây là tùy chọn thiết bị cần giữ lại để người dùng không phải chọn lại mỗi lần mở ứng dụng. Cơ chế sử dụng là thư viện `@react-native-async-storage/async-storage` đã khai báo trong [package.json](/Users/dovandat/BaoTin/Frontend/package.json:32).

| Dữ liệu | Vị trí/cơ chế | Khả năng tồn tại sau khi mở lại |
|---|---|---|
| Lựa chọn sáng/tối | AsyncStorage, key `baotin.color-scheme`, giá trị `light` hoặc `dark` | Được đọc lại khi `AppThemeProvider` mount, nếu ghi/đọc thành công và dữ liệu chưa bị xóa |
| Access token và session giao diện | `tokenRef` và React state trong AppShell | Không được lưu trực tiếp vào AsyncStorage; phiên web được thử khôi phục bằng refresh cookie |
| Refresh session web | Flow cookie HttpOnly do backend/trình duyệt quản lý; client gửi cookie qua `credentials: "include"` | Phụ thuộc hiệu lực cookie/session và CSRF; không phải kho lịch sử offline do frontend quản lý |
| Lịch sử quét | Snapshot lưu ở PostgreSQL phía Threat-Service cho người dùng có xác thực | Khi mở lại và đăng nhập hợp lệ, tải lại từ API; không phải đọc cache cục bộ |
| Báo cáo tố giác demo mới tạo | State `reports` trong AppShell | Mất khi khởi tạo lại AppShell; dữ liệu mẫu được nạp lại |
| Watchlist và thông báo | API và state khi bật API; mảng mẫu/state khi ở demo | Chế độ API tải lại từ server; thay đổi demo không được lưu bền vững |
| Tùy chọn thông báo demo | `localPreferences` trong WatchlistScreen | Chỉ trong vòng đời component; có thể mất ngay khi rời rồi mở lại màn hình |
| Nội dung form, trang/bộ lọc và dòng đang mở | State màn hình/component | Không có cơ chế lưu bền vững được triển khai trong các luồng đã khảo sát |

Nguồn: [AppThemeProvider](/Users/dovandat/BaoTin/Frontend/src/theme/AppTheme.tsx:6), [state AppShell](/Users/dovandat/BaoTin/Frontend/App.tsx:92), [khôi phục phiên web](/Users/dovandat/BaoTin/Frontend/src/api/auth.ts:117), [localPreferences](/Users/dovandat/BaoTin/Frontend/src/screens/WatchlistScreen.tsx:62), [lưu snapshot server](/Users/dovandat/BaoTin/Threat-Service/src/main/java/com/baotin/threat/infrastructure/ScanStore.java:38).

Trong mã frontend đã khảo sát, không có kho SQLite, IndexedDB riêng, offline queue hoặc cache lịch sử bằng AsyncStorage. Không mô tả chúng như chức năng đã có. Secure token storage dành cho native vẫn nằm trong [TODO](/Users/dovandat/BaoTin/Frontend/TODO.md:13).

### 3.5.2. Thời điểm ghi, đọc, cập nhật và xóa

Logic persistence thuộc `AppThemeProvider`:

| Thao tác | Thời điểm | Hành vi trong mã |
|---|---|---|
| Đọc | Sau khi provider mount, trong `useEffect([])` | Gọi `AsyncStorage.getItem("baotin.color-scheme")` |
| Kiểm tra dữ liệu đọc | Khi Promise đọc hoàn tất | Chỉ áp dụng giá trị chính xác `light` hoặc `dark`; dữ liệu khác bị bỏ qua |
| Ghi lần đầu | Người dùng bấm đổi theme khi chưa có giá trị đã lưu | Tính theme kế tiếp, đổi UI bằng `setColorScheme(next)`, gọi `setItem` |
| Cập nhật | Những lần đổi theme tiếp theo | Ghi đè giá trị trên cùng key, không thêm một bản ghi cho mỗi lần đổi |
| Xóa | Chưa có thao tác xóa preference trong ứng dụng | Provider không gọi `removeItem`/`clear`; đăng xuất không xóa key theme |
| Lỗi đọc/ghi | Promise bị reject | Bị bắt và bỏ qua bằng `catch(() => undefined)`; chưa có thông báo lỗi hoặc nút thử lại riêng |

Trình tự ghi thực tế là **đổi giao diện trước, ghi bất đồng bộ sau**. Vì lỗi ghi bị bỏ qua, thấy giao diện đã đổi không đủ chứng minh giá trị đã được lưu thành công. Khi không đọc được giá trị hợp lệ, provider không áp dụng theme đã lưu; giao diện tiếp tục theo trạng thái màu mà NativeWind đang cung cấp. Nguồn: [AppTheme.tsx, đọc và ghi](/Users/dovandat/BaoTin/Frontend/src/theme/AppTheme.tsx:20).

### 3.5.3. Minh họa lưu → đóng/mở → khôi phục

Kịch bản minh họa phù hợp với phần đã triển khai:

1. Mở ứng dụng với giao diện sáng.
2. Bấm nút chuyển sang giao diện tối. `toggleTheme()` đặt `next="dark"`, đổi màu UI và gọi `AsyncStorage.setItem("baotin.color-scheme", "dark")`.
3. Chờ thao tác ghi hoàn tất rồi đóng/mở ứng dụng; với web có thể tải lại trang trên cùng origin và cùng hồ sơ trình duyệt.
4. `AppThemeProvider` được tạo lại và đọc key đã lưu.
5. Nếu đọc được `dark`, provider gọi `setColorScheme("dark")`; UI khôi phục chế độ tối.
6. Chuyển lại sáng và mở lại để đối chiếu chiều ngược lại.

```text
Chọn tối → setColorScheme("dark") → AsyncStorage.setItem(key, "dark")
  → đóng/mở lại hoặc reload web
  → AsyncStorage.getItem(key) → giá trị hợp lệ → setColorScheme("dark")
```

Đây là kịch bản suy ra từ code, **chưa được chạy trong lần lập tài liệu này**. Ca Playwright hiện có đã viết bước đổi tối, reload trang rồi kiểm tra vẫn tối, nhưng việc tồn tại mã test không chứng minh lần chạy hiện tại đã Pass. Nguồn: [test theme persistence](/Users/dovandat/BaoTin/Frontend/tests/e2e/architecture.spec.ts:150).

### 3.5.4. Phân biệt khôi phục theme với tải lại lịch sử

Lịch sử cá nhân không dùng cùng cơ chế với theme. Backend chỉ ghi `scan_history` khi có `userId`; khách vẫn nhận kết quả quét nhưng không có bản ghi lịch sử cá nhân. Khi người dùng mở mục Lịch sử, frontend gọi API lấy snapshot theo trang. Snapshot là kết quả tại thời điểm quét, không phải một lần tính lại rủi ro hiện tại.

`ScanStore.history`, `detail` và `delete` lọc theo `user_id`; đọc/xóa một ID còn yêu cầu ID thuộc người dùng đó. UI xóa lịch sử gọi DELETE rồi tải lại dữ liệu. Việc bản ghi còn sau khi mở ứng dụng lại, nếu được xác nhận khi chạy, là bằng chứng lưu phía server; không được ghi thành “lịch sử lưu trong AsyncStorage”. Nguồn: [lưu khi có userId](/Users/dovandat/BaoTin/Threat-Service/src/main/java/com/baotin/threat/infrastructure/ScanStore.java:38), [đọc/xóa theo chủ sở hữu](/Users/dovandat/BaoTin/Threat-Service/src/main/java/com/baotin/threat/infrastructure/ScanStore.java:97), [màn hình tải lịch sử](/Users/dovandat/BaoTin/Frontend/src/screens/ScanHistoryScreen.tsx:22).

## 3.6. Kiểm thử và mô phỏng chức năng

### 3.6.1. Môi trường và phạm vi bằng chứng

| Hạng mục | Thông tin đã xác định |
|---|---|
| Môi trường khảo sát | Workspace `/Users/dovandat/BaoTin` trên máy macOS; chỉ đọc file và Git metadata |
| Nền tảng ứng dụng | Expo `~57.0.31`, React `19.2.3`, React Native `0.86.3`, TypeScript `~6.0.3` theo package.json |
| Kiểm thử đơn vị đã cấu hình | Jest qua `jest-expo`; script `npm test` chạy `jest --runInBand` |
| E2E giao diện đã cấu hình | Playwright, profile Desktop Chrome với viewport 320×844, 375×844, 414×896, 768×1024 và 1440×1000; chạy bản web export ở `http://127.0.0.1:4183` |
| Smoke test stack thật đã cấu hình | Playwright dùng Chromium desktop 1440×1000 và mobile viewport 375×844; base URL mặc định `http://localhost:8096`, cho phép đổi bằng `SCAN_E2E_BASE_URL`; cần stack thật được khởi động trước |
| Thiết bị/trình duyệt thực chạy trong lần này | Không chạy trình duyệt, Android/iOS emulator hay điện thoại thật; không xác nhận phiên bản browser/device thực tế |
| Kết quả thực thi trong lần này | Không chạy unit test, E2E, smoke test hoặc build; các ca bên dưới đều chưa có kết quả Pass/Fail mới |

Nguồn: [script và dependency](/Users/dovandat/BaoTin/Frontend/package.json:6), [cấu hình E2E giao diện](/Users/dovandat/BaoTin/Frontend/playwright.config.ts:3), [cấu hình smoke test](/Users/dovandat/BaoTin/Frontend/playwright.scan.config.ts:3).

Các profile mobile của Playwright chỉ mô phỏng kích thước web; không phải bằng chứng chạy React Native trên Android/iOS thật. Tên profile không xác nhận thiết bị vật lý đã dùng để demo.

### 3.6.2. Các bộ kiểm thử đã có trong mã

| File kiểm thử | Nội dung được viết để kiểm tra | Giới hạn bằng chứng |
|---|---|---|
| [client.test.ts](/Users/dovandat/BaoTin/Frontend/src/api/client.test.ts:12) | Refresh một lần khi 401, thử lại với token mới; lỗi không phục hồi; body lỗi không phải JSON | Kiểm tra client bằng mock; không chứng minh backend đang chạy |
| [scan.test.ts](/Users/dovandat/BaoTin/Frontend/src/api/scan.test.ts:13) | Endpoint text/url và body; giữ nguyên điểm/mức server; diễn giải thiếu AI; phân biệt rate limit và lỗi mạng | Kiểm tra adapter/presentation, không đo độ chính xác mô hình |
| [baotin.test.ts](/Users/dovandat/BaoTin/Frontend/src/api/baotin.test.ts:4) | Query phân trang/sort, Idempotency-Key khi tạo report, notification, preference và watchlist | Report adapter có test không đồng nghĩa ReportsScreen đã tích hợp API |
| [auth.test.ts của API](/Users/dovandat/BaoTin/Frontend/src/api/auth.test.ts:12) | Web login, CSRF, chuẩn hóa số điện thoại đăng ký và role từ hồ sơ server | Không thay thế E2E với tài khoản Auth/MFA thật |
| [authValidation.test.ts](/Users/dovandat/BaoTin/Frontend/src/lib/authValidation.test.ts:3) | Trường bắt buộc, thông tin đăng ký, mật khẩu, xác nhận và điều khoản | Unit test quy tắc; cần đối chiếu mức tối thiểu 12 ký tự khi bật API |
| [auth.test.ts của lib](/Users/dovandat/BaoTin/Frontend/src/lib/auth.test.ts:3), [admin.test.ts](/Users/dovandat/BaoTin/Frontend/src/lib/admin.test.ts:4) | Quyền mở UI admin theo role, không suy quyền từ tên; lọc và thống kê hàng chờ demo | Không chứng minh phân quyền endpoint backend |
| [AppTheme.test.ts](/Users/dovandat/BaoTin/Frontend/src/theme/AppTheme.test.ts:12) | Độ tương phản các cặp màu semantic | Không phải unit test của thao tác getItem/setItem hay lỗi storage |
| [architecture.spec.ts](/Users/dovandat/BaoTin/Frontend/tests/e2e/architecture.spec.ts:21) | Điều hướng, responsive, quét với fixture, tra cứu demo, cổng lịch sử, watchlist/report/notification demo, form, theme sau reload | Chặn request quét để trả fixture; phiên demo không có lịch sử server |
| [admin.spec.ts](/Users/dovandat/BaoTin/Frontend/tests/e2e/admin.spec.ts:4) | Nhãn demo, thống kê, lọc/mở dòng, chặn khách và USER, cho ADMIN demo và thu quyền khi logout | Kiểm tra UI với vai trò mô phỏng |
| [scan-stack.live.ts](/Users/dovandat/BaoTin/Frontend/tests/live/scan-stack.live.ts:3) | POST thật qua Gateway, snapshot text/url, chuẩn hóa URL, bỏ secret trong response, cảnh báo thiếu AI, 401 lịch sử của khách, sáng/tối và overflow | Không mock API trong test; chưa có luồng đăng nhập rồi CRUD lịch sử của tài khoản thật |

Không sử dụng log hoặc ảnh kiểm chứng cũ để gán Pass cho phiên khảo sát ngày 02/11/2026. Riêng `risk.test.ts` còn kiểm tra bộ chấm điểm mock; đó không phải bằng chứng scan đang dùng thuật toán mock hoặc AI thực đã được kiểm định.

### 3.6.3. Bảng ca kiểm thử chức năng

**Quy ước:** `Chưa thực thi` nghĩa là chưa thao tác và ghi nhận kết quả trong lần lập tài liệu này. “Kết quả mong đợi” bám hành vi code hiện có; các ca kiểm tra rủi ro/concurrency có thể phát hiện lỗi và không được mặc định là Pass.

| Test | Chức năng | Thao tác/Input | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|
| TC01 | Danh sách lịch sử | Đăng nhập API hợp lệ, mở Lịch sử khi đã có bản ghi | Gọi GET history trang 0, size 20; hiện preview, thời điểm và mức rủi ro từ snapshot | Chưa thực thi |
| TC02 | Navigation bảo vệ | Khi chưa đăng nhập, mở Lịch sử | Mở Đăng nhập; đăng nhập thành công quay về mục đang chờ. Phiên demo vẫn thấy cảnh báo không có lịch sử server | Chưa thực thi |
| TC03 | Xem chi tiết | Bấm “Xem kết quả lúc quét” của một dòng | Mở RiskResult ngay trong dòng; không gọi GET detail và không tính lại điểm hiện tại | Chưa thực thi |
| TC04 | Lọc và phân trang | Chọn Tin nhắn/URL, rồi chuyển trang khi có nhiều trang | Bộ lọc đưa page về 0, request mang type tương ứng; các nút trang khóa đúng giới hạn/khi tải | Chưa thực thi |
| TC05 | Xóa lịch sử thành công | Bấm xóa một dòng; thử dòng cuối trên trang sau trang đầu | Gửi DELETE đúng ID; tải lại sau thành công; lùi trang nếu dòng đó là phần tử cuối của trang | Chưa thực thi |
| TC06 | Xóa lịch sử thất bại | Cho DELETE trả lỗi | Hiện lỗi; không coi thao tác đã xóa thành công, kết thúc loading của nút | Chưa thực thi |
| TC07 | Form quét không hợp lệ | Nhập khoảng trắng; nhập SMS dài hơn 10.000 hoặc URL dài hơn 4.096 ký tự | Nút quét bị vô hiệu; quá dài có lỗi tại ô nhập; không gửi request qua thao tác nút | Chưa thực thi |
| TC08 | Quét SMS thành công | Nhập SMS hợp lệ, bấm quét với Gateway hoạt động | POST `/text` đúng body; qua loading rồi hiện snapshot, giữ điểm/mức server | Chưa thực thi |
| TC09 | Quét URL thành công | Chọn URL, nhập `https://example.test/login` | POST `/url` với `{ url }`; hiện kết quả do backend trả về | Chưa thực thi |
| TC10 | API lỗi và thử lại | Trả 503 hoặc ngắt kết nối; sau đó khôi phục và bấm “Thử lại” | Hiện lỗi kết nối; nút thử lại gửi request mới và có thể chuyển sang kết quả | Chưa thực thi |
| TC11 | Mã lỗi nghiệp vụ | Lần lượt trả 400, 401, 413, 429 và 404 ở luồng áp dụng | Hiện thông báo đúng bảng lỗi tại mục 3.4.5 | Chưa thực thi |
| TC12 | Loading và chống gửi lặp | Làm chậm response rồi bấm quét liên tiếp | Hiện “Đang kiểm tra”; không gửi thêm từ cùng thao tác quét trong khi scanning | Chưa thực thi |
| TC13 | Response cũ | Gửi quét rồi sửa nội dung, đổi loại hoặc chuyển màn hình trước khi trả kết quả | Request cũ được hủy/loại bỏ; không hiện kết quả cũ cho đầu vào mới | Chưa thực thi |
| TC14 | Empty state | API history trả `items=[]`; tra cứu demo từ khóa không tồn tại; watchlist rỗng | Hiện trạng thái rỗng tương ứng; không nhầm empty với lỗi request | Chưa thực thi |
| TC15 | Dữ liệu cũ khi lỗi tải | Tải history thành công rồi gây lỗi lần tải tiếp theo | Hiện lỗi và kết thúc loading; dữ liệu cũ có thể còn hiển thị theo code hiện tại | Chưa thực thi |
| TC16 | Refresh token | API trả 401 lần đầu; refresh trả token mới; thử thêm tình huống refresh thất bại | Thành công thì thử lại một lần với token mới; thất bại không lặp vô hạn và trả lỗi cho caller | Chưa thực thi |
| TC17 | Validation đăng ký | Username sai định dạng, contact sai, mật khẩu dưới 12 ký tự ở API mode, xác nhận lệch hoặc chưa đồng ý điều khoản | Hiện lỗi trường tương ứng, không gọi đăng ký khi validation còn lỗi | Chưa thực thi |
| TC18 | MFA | Đăng nhập API trả challenge; nhập mã rỗng, sai rồi hợp lệ | Mã rỗng bị chặn; mã sai hiện lỗi; mã hợp lệ hoàn tất phiên theo response server | Chưa thực thi |
| TC19 | Form tố giác demo | Bước 1: target ≤5 ký tự; bước 2: title ≤5 hoặc description ≤15 ký tự; sau đó nhập đủ và gửi | Khóa bước/gửi khi chưa đủ; hợp lệ thì thêm state, đóng/reset form và hiện “Đã gửi tố giác”; không gửi API | Chưa thực thi |
| TC20 | Bằng chứng demo | Bấm “Thêm file giả lập” nhiều lần | Bộ đếm tối đa 5; không có upload file thật hoặc xác minh dung lượng file | Chưa thực thi |
| TC21 | Theo dõi URL | API mode: nhập URL sai protocol rồi URL HTTP/HTTPS hợp lệ, lưu và bỏ theo dõi | Sai thì hiện lỗi; thành công POST cập nhật danh sách/đóng form; DELETE thành công mới bỏ dòng | Chưa thực thi |
| TC22 | Preference và rollback | Đổi công tắc thông báo, cho PATCH lỗi | Trong khi lưu khóa các công tắc; khi lỗi khôi phục giá trị trước và hiện lỗi | Chưa thực thi |
| TC23 | Thông báo và điều hướng | Mở bảng, đánh dấu đọc, chọn notification watchlist/report | Cập nhật badge/state, gọi API ở API mode; đóng bảng và mở mục Theo dõi/Tố giác, chưa mở sâu theo ID | Chưa thực thi |
| TC24 | Local storage | Chọn tối, chờ ghi, đóng/mở lại hoặc reload web cùng origin | Đọc key `baotin.color-scheme` và khôi phục tối; đổi sáng kiểm tra chiều ngược lại | Chưa thực thi |
| TC25 | Storage không hợp lệ/lỗi | Trong môi trường kiểm thử, cho getItem trả giá trị khác light/dark hoặc reject; cho setItem reject | Giá trị không hợp lệ bị bỏ qua; không hiện lỗi storage riêng; lỗi ghi có thể khiến lần mở sau không giữ theme | Chưa thực thi |
| TC26 | Phân biệt state và persistence | Tạo tố giác demo rồi khởi tạo lại app; đổi preference demo rồi rời/mở màn hình | Tố giác mới mất khi AppShell khởi tạo lại; preference demo có thể trở về mặc định khi màn hình mount lại | Chưa thực thi |
| TC27 | Quyền lịch sử | Gọi history không token; dùng tài khoản B đọc/xóa ID thuộc A trong môi trường test | Khách bị từ chối; B không đọc/xóa được bản ghi A; cần xác minh thực tế Gateway và service | Chưa thực thi |
| TC28 | AI thiếu dữ liệu | Backend trả snapshot `degraded=true` hoặc thiếu model | Hiện cảnh báo/diễn giải thiếu dữ liệu; không trình bày fallback như kết luận an toàn | Chưa thực thi |
| TC29 | Responsive và theme | Mở các mục ở các viewport đã cấu hình, đổi sáng/tối | Không tràn ngang; nút/trạng thái đọc được, bố cục và màu phù hợp theme | Chưa thực thi |

Nguồn validation: [ScanComposer](/Users/dovandat/BaoTin/Frontend/src/components/ScanComposer.tsx:14), [authValidation](/Users/dovandat/BaoTin/Frontend/src/lib/authValidation.ts:3), [AuthScreen API mode](/Users/dovandat/BaoTin/Frontend/src/screens/AuthScreen.tsx:97), [ReportComposer](/Users/dovandat/BaoTin/Frontend/src/components/ReportComposer.tsx:27), [WatchlistScreen](/Users/dovandat/BaoTin/Frontend/src/screens/WatchlistScreen.tsx:37).

### 3.6.4. Kịch bản demo đề xuất theo phạm vi hiện có

**Demo API thật:** cần bản web có đường tới Gateway, dịch vụ quét hoạt động và tài khoản thật nếu muốn xem lịch sử. Nếu cần Auth/Notification/Watchlist, phải cấu hình `EXPO_PUBLIC_API_URL` trong lúc tạo bundle web. Biến môi trường runtime không tự thay đổi JavaScript đã export. Kịch bản dưới đây là hướng dẫn, chưa được thực hiện trong lần khảo sát.

1. Mở Kiểm tra, nhập SMS hoặc URL và gửi request; quan sát loading rồi kết quả. Nếu thiếu mô hình thật, trình bày rõ cảnh báo fallback.
2. Mở chi tiết phân tích để đối chiếu điểm tổng hợp, rule/model và URL nhúng với snapshot server.
3. Đăng nhập thật, quét mới rồi mở Lịch sử; lọc loại, mở kết quả tại dòng và xóa một bản ghi.
4. Trong môi trường test, tạo tình huống mất kết nối hoặc response lỗi; quan sát thông báo và thử lại. Không gọi đây là kết quả đo độ chính xác AI.
5. Thêm/bỏ theo dõi URL, đổi tùy chọn thông báo, kiểm tra bảng thông báo khi các API tương ứng hoạt động.
6. Đổi theme, chờ lưu rồi mở lại để minh họa persistence cục bộ.

**Demo không có Auth API:** có thể minh họa điều hướng và phiên mô phỏng, tra cứu/tố giác/quản trị demo, thao tác state của watchlist/thông báo và lưu theme. Quét vẫn cần backend hoặc fixture của bộ kiểm thử; phiên demo không có lịch sử cá nhân server. Nút Google/Facebook trong demo không chứng minh SSO thật đã hoạt động.

### 3.6.5. Giới hạn và cách ghi nhận kết quả

Các điểm chưa được chứng minh bằng phiên chạy mới gồm: CRUD lịch sử với tài khoản thật, phân quyền khác chủ sở hữu qua toàn stack, MFA thật, lỗi/khôi phục storage trên thiết bị, hành vi response cũ ở các tình huống cạnh tranh và chạy native trên Android/iOS. Smoke test hiện có kiểm tra quét khách và bảo vệ history; chưa bao phủ luồng đăng nhập rồi đọc/xóa lịch sử cá nhân. Test tên “retry” trong suite giao diện gửi lại bằng nút quét sau khi thay input, chưa trực tiếp bấm nút “Thử lại”.

Khi thực thi sau này, mỗi ca cần ghi ngày giờ, commit/bundle, URL môi trường, chế độ API/demo, browser hoặc thiết bị, input, response/thông báo quan sát được và bằng chứng ảnh/log phù hợp. Chỉ đổi cột kết quả sang **Pass** hoặc **Fail** sau khi đã quan sát và đối chiếu với kết quả mong đợi. Không ghi token, cookie hoặc dữ liệu nhạy cảm vào bằng chứng.

Trong lần lập tài liệu này, việc kiểm chứng chỉ gồm lần theo đường gọi trong code, đối chiếu state/endpoint/storage với mã kiểm thử và rà soát liên kết nguồn. Tài liệu không công bố ứng dụng đã Pass kiểm thử thực thi.
