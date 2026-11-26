# Tạo cấu hình deploy JAR + Expo Web trên máy hiện tại

Chạy các lệnh PowerShell bằng `pwsh` (7.4+). Tài liệu này chuẩn bị cấu hình,
không khởi động dịch vụ, tạo database, thay đổi Jenkins hoặc bật route HTTPS.
`jar.env` chỉ chứa đường dẫn và cấu hình công khai. Secret nằm ở file riêng ngoài Git.

## 1. Mở mẫu đã chuẩn bị

```powershell
notepad C:\Users\ngoan\.baotin\jar.env
```

Mẫu đã điền JDK21, Node, username Jenkins `rimiprod` và đường dẫn token đã có.
Hai dòng cần đối chiếu với PostgreSQL/Redis thực tế:

```dotenv
DB_URL=jdbc:postgresql://127.0.0.1:15432/baotin
REDIS_HOST=127.0.0.1
REDIS_PORT=16379
```

PostgreSQL/Redis chạy bằng [Compose dependencies](dependencies/README.md), dùng volume
bền vững và chỉ bind **127.0.0.1**. Thực hiện hướng dẫn đó để tạo bootstrap password,
khởi động container và provision database; không sửa Jenkins/dind hay PostgreSQL5432 hiện hữu.
PostgreSQL dùng một database `baotin`, schema/role riêng từng service.
`RUNTIME_ROOT=M:/BaoTin-runtime` nằm ngoài source. Gateway8090, web8091,
Auth8181 và các scaffold8182..8189 chỉ listen loopback.

## 2. Tạo thư mục riêng và bảo vệ file mới

Chạy đoạn dưới trong một phiên PowerShell; các bước 3–4 dùng lại các hàm này.
Hàm tạo file dùng `CreateNew`: giữ nguyên mọi file đã tồn tại.

```powershell
$ErrorActionPreference='Stop'
$base='C:\Users\ngoan\.baotin\secrets'
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
function Protect-Directory([string]$path) {
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    $acl=[Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    foreach($who in @($sid,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
        [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $who,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    }
    Set-Acl -LiteralPath $path -AclObject $acl
}
function Write-NewBytes([string]$path,[byte[]]$bytes) {
    $stream=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes,0,$bytes.Length)} finally {$stream.Dispose()}
}
function Read-SecretFile([string]$path) {
    $secret=Read-Host 'Nhập giá trị secret (ẩn)' -AsSecureString
    $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try {
        $text=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
        if([string]::IsNullOrWhiteSpace($text)){throw 'Giá trị trống'}
        Write-NewBytes $path ([Text.UTF8Encoding]::new($false).GetBytes($text))
    } finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr);$text=$null}
}
foreach($name in 'auth-config','gateway-config','auth-keys','db') {
    Protect-Directory (Join-Path $base $name)
}
```

Thư mục `auth-config` đang chứa SMTP/Google/Facebook đã cấu hình: giữ nguyên nội dung.
Các file secret mới kế thừa ACL chỉ operator hiện tại/SYSTEM/Administrators.
File cũ cần kiểm tra ACL riêng; thay ACL thư mục không tự chứng minh ACL file cũ.

## 3. Tạo khóa JWT và khóa mã hóa MFA lần đầu

Chỉ chạy khi **chưa có cả hai file**. Khóa MFA là 32 byte binary, không phải chuỗi base64.
Khóa JWT là RSA3072 PKCS#8 PEM. Cả hai được dùng lại qua các lần deploy;
backup riêng với ACL tương đương, không tạo lại khi restart hoặc đổi release.

```powershell
$private=Join-Path $base 'auth-keys/private.pem'
$cipher=Join-Path $base 'auth-keys/encryption.bin'
if((Test-Path $private) -or (Test-Path $cipher)){throw 'Khóa đã tồn tại; giữ nguyên hoặc dùng quy trình rotation'}
$rsa=[Security.Cryptography.RSA]::Create(3072)
try {Write-NewBytes $private ([Text.UTF8Encoding]::new($false).GetBytes($rsa.ExportPkcs8PrivateKeyPem()))}
finally {$rsa.Dispose()}
Write-NewBytes $cipher ([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

## 4. File mật khẩu nào cần tạo?

| File ngoài repo | Giá trị cần nhập |
|---|---|
| `secrets/auth-config/spring.datasource.password` | Password PostgreSQL của `baotin_auth` |
| `secrets/db/migrator.password` | Password riêng của `baotin_auth_migrator` |
| `secrets/db/operator.password` | Password riêng của `baotin_auth_operator` |
| `secrets/gateway-config/spring.data.redis.password` | Password của Redis thực tế |

Các đường dẫn trong bảng tính từ `C:\Users\ngoan\.baotin`.
Tạo file bằng prompt ẩn, không đặt password trong history, câu SQL hoặc command arguments:

```powershell
Read-SecretFile (Join-Path $base 'auth-config/spring.datasource.password')
Read-SecretFile (Join-Path $base 'db/migrator.password')
Read-SecretFile (Join-Path $base 'db/operator.password')
Read-SecretFile (Join-Path $base 'gateway-config/spring.data.redis.password')
```

Nhập các password đã đặt cho đúng PostgreSQL role/Redis; tạo file không tự đổi password server.
Mỗi role dùng password khác nhau. Jenkins runtime chỉ đọc runtime password;
migrator/operator không được đưa vào `auth-config`, `jar.env` hoặc build credentials.
Token Jenkins đã có ở `C:\Users\ngoan\.baotin\jenkins-api.token`, giữ nguyên.
`github-read.token` là PAT checkout SCM, không thay cho token Jenkins.
`agent.secret` là secret do Jenkins sinh khi tạo node, không tự chọn password cho nó.

## 5. Chuẩn bị PostgreSQL trước lần deploy đầu

Với identity provisioning, tạo database `baotin` nếu chưa có. Sau đó chạy SQL đã duyệt
`Auth-Service/src/main/resources/db/provision/before-migrate.sql` trong database này.
Trong `psql` có thể dùng `\i M:/BaoTin/Auth-Service/src/main/resources/db/provision/before-migrate.sql`.
Dùng prompt `psql` để đặt password đúng với file ở bước 4:

```text
\password baotin_auth
\password baotin_auth_migrator
\password baotin_auth_operator
```

Chạy migration bằng **Auth JAR đã build/test và được duyệt**, trong phiên operator riêng.
Lệnh sau dùng file mật khẩu, không khởi động HTTP Auth:

```powershell
$env:DB_URL='jdbc:postgresql://127.0.0.1:15432/baotin' # Docker PostgreSQL, khớp jar.env
$env:DB_MIGRATION_USER='baotin_auth_migrator'
$env:DB_MIGRATION_PASSWORD_FILE='C:\Users\ngoan\.baotin\secrets\db\migrator.password'
Remove-Item Env:DB_MIGRATION_PASSWORD -ErrorAction SilentlyContinue
try {
    & 'C:\Users\ngoan\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe' `
      '-Dloader.main=com.baotin.auth.operator.DatabaseMigration' `
      -cp 'M:\BaoTin\Auth-Service\build\libs\Auth-Service-0.0.1-SNAPSHOT.jar' `
      org.springframework.boot.loader.launch.PropertiesLauncher
    if($LASTEXITCODE -ne 0){throw 'Migration failed'}
} finally {
    Remove-Item Env:DB_URL,Env:DB_MIGRATION_USER,Env:DB_MIGRATION_PASSWORD_FILE -ErrorAction SilentlyContinue
}
```

Sau migration, identity provisioning chạy
`Auth-Service/src/main/resources/db/provision/after-migrate.sql`.
Xác nhận runtime role không DDL/GRANT/đổi ADMIN hoặc truy cập schema service khác;
dùng quy trình và privilege tests trong `Auth-Service/OPERATOR_RUNBOOK.md`.
Runtime Auth dùng `baotin_auth`, migration tắt. Không dùng postgres/migrator/operator để chạy Auth.
Tám service scaffold chưa cần provisioning schema nghiệp vụ ngay.

## 6. Đối chiếu SMTP, OAuth và HTTPS

Giữ SMTP đã test; `spring.mail.username` là tài khoản SMTP/email gửi, không phải username Jenkins.
Các file `auth.mail.enabled`, `auth.mail.from`, `spring.mail.username`, `spring.mail.password`
và cấu hình OAuth hiện có được giữ nguyên.

Mẫu chọn origin dự kiến `https://rimi.tail614b07.ts.net:9444`.
Đối chiếu hostname thực tế, trang `/auth/callback` của frontend và ingress đã duyệt trước deploy.
Trong `auth-config`, `auth.oauth.web-return-uris` phải chứa URL callback frontend thật;
Google/Facebook callback backend phải khớp tuyệt đối với console provider, ví dụ:

```text
https://rimi.tail614b07.ts.net:9443/api/v1/auth/oauth2/callback/google
https://rimi.tail614b07.ts.net:9443/api/v1/auth/oauth2/callback/facebook
```

Các route test9443/9444 đã gỡ; mẫu không bật lại chúng và không đổi443/8443.
Direct deployment giữ Firebase phone/Kafka events tắt; Spark fictional-phone test
là bước riêng, không cần file Firebase iOS/Android để chuẩn bị deploy backend.

## 7. Chuyển đường dẫn cho agent, rồi kiểm tra trước promotion

Trong **phiên khởi động deploy agent**, đặt:

```powershell
$env:BAOTIN_JAR_ENV_FILE='C:\Users\ngoan\.baotin\jar.env'
```

Agent cần kế thừa biến này khi khởi động; đặt biến ở terminal khác không cập nhật agent đang chạy.
Chỉ job Infra đã pin/được duyệt chạy trên deploy-only node.
Sau khi các file và dependencies có thật, kiểm tra readiness, JWT, authorization,
key/session continuity và rollback trước promotion. `DEPLOY_RELEASE=false` mặc định;
chỉ bật deploy sau khi cấu hình được xác nhận và maintainer duyệt.
Docker image là tuyến alternative, không cần `team.env`/GHCR digest cho JAR + web.

## Hoàn tất bước cấu hình

Gửi lại **đường dẫn** `C:\Users\ngoan\.baotin\jar.env` và cho biết PostgreSQL/Redis
đã provision chưa. Nội dung password/key/token giữ ở máy, không gửi chat.
Mẫu và tài liệu đã chuẩn bị không đồng nghĩa deploy/HTTPS/node đã hoàn tất.
