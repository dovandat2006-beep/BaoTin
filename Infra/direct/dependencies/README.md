# PostgreSQL + Redis trong Docker, backend JAR trên Windows

Compose này chỉ chạy hai dependencies, không chạy/recreate Jenkins, dind hoặc backend.
PostgreSQL17/Redis7 pin digest; named volumes giữ dữ liệu qua restart/recreate.
Port chỉ bind `127.0.0.1`: PostgreSQL15432, Redis16379. Chọn15432 để không đụng
PostgreSQL hiện có trên5432; không dừng/xóa instance cũ và không tự chuyển dữ liệu cũ.

## Chuẩn bị

Copy `.env.example` ra `C:\Users\ngoan\.baotin\deps.env`, điền `BAOTIN_SECRETS_DIR`.
Trong secret directory ngoài repo, tạo:

- `postgres/password`: password bootstrap riêng cho PostgreSQL `postgres`.
- `auth-config/spring.datasource.password`: password runtime `baotin_auth`.
- `db/migrator.password`, `db/operator.password`: hai password riêng cho migrations/operator.
- `gateway-config/spring.data.redis.password`: Redis password8–128 byte UTF-8,
  không BOM/newline; nên dùng64 ký tự hex ngẫu nhiên từ password manager. ACL dùng hash
  nên password chứa dấu câu không bị chèn thành cấu hình/lệnh Redis.

Giữ ACL operator/SYSTEM/Administrators như `../SETUP.vi.md`. Redis dùng chính file
Gateway đọc nên không cần sao chép password sang file khác. Secret mới chưa được tạo
bởi Compose; bind file thiếu sẽ fail, không tạo thư mục giả ở vị trí file.

## Khởi động

Chạy từ thư mục `Infra`:

```powershell
docker --context desktop-linux compose --project-name baotin-deps --env-file C:\Users\ngoan\.baotin\deps.env -f direct/dependencies/docker-compose.yml config --quiet
docker --context desktop-linux compose --project-name baotin-deps --env-file C:\Users\ngoan\.baotin\deps.env -f direct/dependencies/docker-compose.yml up -d --wait --wait-timeout 120
docker --context desktop-linux compose --project-name baotin-deps --env-file C:\Users\ngoan\.baotin\deps.env -f direct/dependencies/docker-compose.yml ps
```

`jar.env` dùng:

```dotenv
DB_URL=jdbc:postgresql://127.0.0.1:15432/baotin
REDIS_HOST=127.0.0.1
REDIS_PORT=16379
```

Auth/Gateway JAR chạy trên host nên dùng localhost, không dùng hostname `postgres`/`redis`
của container. Mọi frontend vẫn chỉ gọi Gateway; DB/Redis không mở ra LAN/Tailscale.
Redis ACL chỉ cho namespace `gateway:*`, PING/handshake và Lua rate-limit hiện tại;
CONFIG/FLUSHALL/keys namespace khác bị từ chối. Các service khác thêm ACL riêng theo TODO,
không dùng chung quyền admin/default account này cho nghiệp vụ tương lai.

## Provisioning PostgreSQL

Với ba password64 ký tự hex riêng đã sinh và lưu ngoài Git, có thể chạy operator runner:

```powershell
.\direct\dependencies\Provision-Database.ps1 -EnvFile C:\Users\ngoan\.baotin\deps.env -JarEnvFile C:\Users\ngoan\.baotin\jar.env
```

Runner áp dụng provisioning/grants đã duyệt, migration bằng migrator riêng và kiểm tra
compatibility bằng runtime role. Không khởi động HTTP Auth bằng credential quản trị.
Quy trình interactive dưới đây dành cho cấu hình thủ công; không thay password file của
database đang chạy mà bỏ qua thao tác đồng bộ role/password trên server.

Sau healthcheck, chạy SQL provisioning đã duyệt trong container (password đọc từ file):

```powershell
Get-Content ..\Auth-Service\src\main\resources\db\provision\before-migrate.sql -Raw |
  docker --context desktop-linux compose --env-file C:\Users\ngoan\.baotin\deps.env -f direct/dependencies/docker-compose.yml exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/postgres-password)" psql -h 127.0.0.1 -U postgres -d baotin -v ON_ERROR_STOP=1'
if($LASTEXITCODE -ne 0){throw 'Provisioning failed'}
# Mở psql interactive rồi dùng \password cho ba role; không ghi password trong SQL/history.
docker --context desktop-linux compose --env-file C:\Users\ngoan\.baotin\deps.env -f direct/dependencies/docker-compose.yml exec postgres sh -c 'PGPASSWORD="$(cat /run/secrets/postgres-password)" psql -h 127.0.0.1 -U postgres -d baotin'
```

Dùng `\password baotin_auth`, `\password baotin_auth_migrator`, `\password baotin_auth_operator`
và lưu đúng giá trị vào các file riêng ở trên. Chạy migration JAR trên host bằng migrator,
DB_URL15432, theo `../SETUP.vi.md`; sau đó pipe `after-migrate.sql` tương tự trước khi chạy Auth.
Một database `baotin`, schema/role riêng; Auth không dùng credential `postgres`.
Bootstrap password file chỉ được áp dụng lúc khởi tạo volume mới. Sửa file không đổi
password database đã tồn tại; rotation phải đổi role/password cùng file theo quy trình.

## Dừng/rollback dependencies

```powershell
docker --context desktop-linux compose --project-name baotin-deps --env-file C:\Users\ngoan\.baotin\deps.env -f direct/dependencies/docker-compose.yml down
```

Lệnh trên giữ named volumes. Không thêm `-v`; restart backend/dependencies dùng lại dữ liệu
và khóa hiện hữu. Backup PostgreSQL trước migration/upgrade; không down-migrate database.
Chưa có dữ liệu target được tự chuyển từ PostgreSQL5432 sang volume mới.

## Kiểm chứng độc lập

```powershell
.\direct\dependencies\Test-Dependencies.ps1
```

Test dùng project/volume/secret synthetic riêng: health, SCRAM/authentication negatives,
Redis ACL + Lua, Docker inspect không chứa password, dữ liệu tồn tại sau down/up,
port loopback, real Gateway JAR health và cleanup chỉ fixture. Không tạo secret hay start stack production.
Compose dùng bridge riêng vì `internal:true` không forward cổng host trên Docker Desktop
đã kiểm chứng. Port publish vẫn chỉ loopback, không nối vào network Jenkins hay các app khác.
