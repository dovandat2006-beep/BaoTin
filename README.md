# BảoTin

Mã nguồn BảoTin được gom từ 14 repository của [vietluccuu](https://github.com/vietluccuu), đồng bộ ngày 26/11/2026. Mỗi thư mục chứa toàn bộ file được Git quản lý tại commit ghi bên dưới; mã nguồn được lưu trực tiếp trong repo này.

## Thành phần

| Thư mục | Commit nguồn |
| --- | --- |
| [AI](./AI/) | [`acb5874`](https://github.com/vietluccuu/AI/commit/acb58749c6118fc2f2004e93351f870d2d315211) |
| [API-Gateway](./API-Gateway/) | [`e7c20a4`](https://github.com/vietluccuu/API-Gateway/commit/e7c20a4546071358de81bc9f00b5e590a331d276) |
| [Analytics-Service](./Analytics-Service/) | [`9e81392`](https://github.com/vietluccuu/Analytics-Service/commit/9e8139253fcbef00039d9753be3fb4dfb84c2c13) |
| [Auth-Service](./Auth-Service/) | [`31bc354`](https://github.com/vietluccuu/Auth-Service/commit/31bc354c48fec930069bf58859da9a2d6069cffd) |
| [Campaign-Service](./Campaign-Service/) | [`dbd2964`](https://github.com/vietluccuu/Campaign-Service/commit/dbd2964d8602947477ae1f89c812ffa5bde8b2ab) |
| [Contracts](./Contracts/) | [`61d4ad1`](https://github.com/vietluccuu/Contracts/commit/61d4ad1d7f59205d4984960184fc45d3f898236b) |
| [Frontend](./Frontend/) | [`6d52a03`](https://github.com/vietluccuu/Frontend/commit/6d52a031ab5d76631f84c2fae35cf72ade0da72f) |
| [Infra](./Infra/) | [`dfb04bd`](https://github.com/vietluccuu/Infra/commit/dfb04bd4dea7a70b12a51155683234594e257233) |
| [Model-Inference-Service](./Model-Inference-Service/) | [`44c269a`](https://github.com/vietluccuu/Model-Inference-Service/commit/44c269ac0a3b212de37c3a4a2c006b261b47300c) |
| [Notification-Service](./Notification-Service/) | [`47d6f22`](https://github.com/vietluccuu/Notification-Service/commit/47d6f22243afa0625618811ffefd42cafb5643dd) |
| [Report-Service](./Report-Service/) | [`feec111`](https://github.com/vietluccuu/Report-Service/commit/feec111bf15e4838eec73258f43061b389683f4d) |
| [Reputation-Service](./Reputation-Service/) | [`adaf4bd`](https://github.com/vietluccuu/Reputation-Service/commit/adaf4bd18e56a9bb28ff53e65cb3c2517121efe1) |
| [Search-Service](./Search-Service/) | [`4f7fff7`](https://github.com/vietluccuu/Search-Service/commit/4f7fff73e68fb9f1eb6c3251c0dd1703abccce9a) |
| [Threat-Service](./Threat-Service/) | [`08bc23e`](https://github.com/vietluccuu/Threat-Service/commit/08bc23e0cc9382adb346fb8d556c0a0f3ecd496d) |

## Chạy và phát triển

Đọc `README.md`, `AGENTS.md` và `TODO.md` của từng thành phần trước khi cấu hình hoặc chạy. Frontend sử dụng Expo/React Native; các dịch vụ Java sử dụng JDK 21 và Gradle Wrapper. `Contracts` chứa API/event schema; `Infra` chứa cấu hình và hướng dẫn vận hành.

Các file `BAO_CAO_*.md` tại thư mục gốc là báo cáo dự án. Thông tin tài khoản, `.env`, khóa riêng, log, cache, thư viện đã cài và dữ liệu runtime không nằm trong bản gom này. Dùng các file `.env.example` làm mẫu và lưu giá trị thật ngoài Git. Model đã huấn luyện không được đóng gói trong bản mã nguồn này.

Đây là bản chụp mã nguồn hiện tại; lịch sử commit của từng repo nguồn vẫn nằm ở các liên kết trên. Các pipeline trong thư mục thành phần giữ cấu hình của repo nguồn; việc gom mã nguồn không tự triển khai ứng dụng hay kích hoạt các pipeline đó tại repo gốc.
