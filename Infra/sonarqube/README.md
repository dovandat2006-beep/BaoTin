# SonarQube — quality gate cho Jenkins

## Cấu hình hiện hành — 2026-10-16

Migration đã nghiệm thu: [`../ci-stack/README.vi.md`](../ci-stack/README.vi.md).
Một stack với Jenkins/dind/Sonar/DB; host **9001**, CI vẫn `http://sonarqube:9000`.
Không chạy `docker compose up` của stack Sonar riêng sau adoption. Những ví dụ
khởi động9000 phía dưới thuộc installation cũ/rollback.
FullStack#12 đã PASS đủ11 project gates rồi deploy JAR/web; số metric và stage order
được kiểm chứng trên artifact/metadata thật. Anonymous data access401 và CI account
không được tạo gate403. Public9001 login-page reachability đã được operator xác nhận.

Backend: `clean classes sonar` → verdict/metrics/policy PASS → `bootJar` →
security/SBOM. Frontend: source secret gate → Sonar PASS → typecheck/Expo export →
archive/security. Release không chạy tests/JUnit/JaCoCo/LCOV; verify workflows riêng
vẫn chạy tests. Không dùng kết quả release để tuyên bố tests pass.
Host operator scripts mặc định9001 và chấp nhận loopback9000 cho rollback;
token không gửi ra public endpoint. Anonymous project/gate access phải trả401;
CI analysis account không được quản trị/thay quality gate.

Server phân tích tĩnh + quality gate mà pipeline Jenkins gọi tới. Stack gồm
`sonarqube` (Community 9.9.8 LTS) và PostgreSQL nội bộ, chạy bằng
`docker-compose.yml` trong thư mục này.

## Vai trò và ràng buộc

- Container `sonarqube` gắn vào network `jenkins` (external) nên controller kết nối
  được qua `http://sonarqube:9000`. Đây là lý do gate chạy bằng Gradle/Scanner trên
  **controller** chứ không dùng scanner container: build daemon (dind) không tới được
  cổng đã publish ra host.
- PostgreSQL của SonarQube là internal, **không** publish ra host.
- Image pin bằng digest; secret chỉ đến từ `.env` (đã gitignore). `.env.example`
  chỉ chứa placeholder.
- Không file nào ở đây recreate `jenkins-blueocean`.
- Community Edition chỉ phân tích nhánh **mặc định**. BaoTin chỉ gate trên `main`
  nên phù hợp; phân tích branch/PR cần bản thương mại.

## Khởi động

```bash
cd Infra/sonarqube
cp .env.example .env          # điền SONAR_DB_PASSWORD (ví dụ: openssl rand -hex 24)
docker compose up -d
curl -s http://localhost:9000/api/system/status   # -> {"status":"UP", ...}
```

UI: http://localhost:9000 (operator xem; pipeline dùng hostname nội bộ `sonarqube`).

## Bootstrap một lần (operator)

1. Đăng nhập `admin`/`admin`, đổi mật khẩu ngay. Lưu mật khẩu **ngoài Git**
   (ví dụ `C:\Users\<user>\.baotin\sonarqube-admin-password.txt`).
2. Tạo token cho CI: **My Account → Security → Generate Tokens** (type *User Token*).
   Lưu vào `C:\Users\<user>\.baotin\sonarqube-ci.token` với ACL chỉ operator +
   SYSTEM + Administrators.
3. Điền `SONAR_TOKEN` vào `Infra/jenkins/init/baotin-ci.properties` (file đã gitignore).
4. Chạy `Infra/jenkins/apply-config.ps1`. Script init tạo credential Jenkins
   `sonar-token` (Secret text) và đặt global env `SONAR_HOST_URL=http://sonarqube:9000`.
   **Không** tạo credential/env bằng tay — chúng thuộc về script init để cấu hình
   có thể tái lập. Xoay token chỉ cần sửa `SONAR_TOKEN` trong properties rồi chạy lại:
   script init so cả giá trị secret nên credential được cập nhật tại chỗ (class và
   description của Secret text không đổi khi token đổi).
5. Chạy `sonarqube/Sync-QualityGates.ps1` để tạo/đồng bộ 4 quality gate và gán cho
   11 project (xem mục "Quality gate" bên dưới). Bước này **bắt buộc**: thiếu nó thì
   project rơi về gate default `Sonar way` — gate không bao giờ fail.

Token ở bước 2 và mật khẩu ở bước 1 không bao giờ được commit hay dán vào chat.

## Đấu nối pipeline

| Lane | Script | Coverage | Nguồn phân tích |
|---|---|---|---|
| 10 backend service | `ci/sonar-quality-gate.sh` (sinh từ `Infra/ci/templates/sonar-quality-gate.sh`) | không có — lane release không chạy test | plugin `org.sonarqube` trong `build.gradle` |
| Frontend | `Frontend/ci/sonar-quality-gate.sh` | không có — tests/LCOV thuộc verify lane | SonarScanner CLI pin sha256 |

Stage `SonarQube quality gate` trong `Jenkinsfile` backend chạy
`./gradlew clean classes sonar`, kiểm chứng gate trước stage `Build release artifact`
chạy `./gradlew bootJar`; test (unit + integration) **không** chạy ở
lane release mà chạy ở máy dev và ở lane GitHub Verify (`.github/workflows/ci-cd.yml`
chạy `./gradlew test bootJar` trên `pull_request`). Token chỉ được giải mã qua
`withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')])`.
Script không nhận token qua đối số dòng lệnh và không bật `set -x`.

Vì lane release không chạy test, phân tích backend không có coverage nên 2 gate backend
cố ý **không** có condition `coverage`: một condition coverage đo trên phân tích không có
dữ liệu sẽ luôn `OK` — đúng kiểu "gate vô hiệu" mà mục dưới cảnh báo. `jacoco` vẫn được
giữ trong `build.gradle` để máy dev sinh report coverage khi cần.
Project key là `baotin_<rootProject.name.toLowerCase()>`, ví dụ `baotin_auth-service`;
script kiểm tra key khớp `^baotin_[a-z][a-z0-9-]{2,40}$` trước khi tin tưởng.

## Fail closed — mã thoát

| Mã | Ý nghĩa |
|---|---|
| 2 | `SONAR_HOST_URL` không phải host nội bộ, thiếu công cụ, hoặc cấu hình timeout sai |
| 3 | thiếu `report-task.txt`/`ceTaskId`, hoặc compute-engine task `FAILED`/`CANCELED` |
| 4 | compute-engine quá hạn (`SONAR_GATE_TIMEOUT_SECONDS`, mặc định 900) hoặc lỗi truy vấn gate |
| 5 | gate khác `OK` — in từng condition (metric, status, actual, threshold) |

Script poll trạng thái thay vì dùng `sonar.qualitygate.wait` để giữ mã thoát tường
minh. Mọi nhánh lỗi đều fail build; không có nhánh "bỏ qua".

## Quality gate: vì sao "Sonar way" vô hiệu, và gate thật đang dùng

Gate mặc định **"Sonar way"** chỉ gồm condition **New Code**. Với các project BaoTin,
SonarQube **không dựng được tập New Code nào cả**: CE 9.9 suy ra new code từ period
(mặc định `PREVIOUS_VERSION`) và cần `projectVersion`/blame ổn định, trong khi mọi
service đều báo `0.0.1-SNAPSHOT` nên lần phân tích nào cũng là "phiên bản mới".

Hệ quả đã kiểm chứng: `new_lines`, `new_coverage`, `new_bugs`, `new_lines_to_cover`
**đều vắng mặt**; gate `Sonar way` vẫn trả `OK` kể cả khi thêm hẳn một method không có
test (coverage overall tụt 33.3% → 20%) hay khi ép period 30 ngày. **"Từ lần phân tích
thứ hai là được gate thật" là sai** — gate kiểu này không bao giờ fail.

Vì vậy BaoTin dùng gate **overall code** kiểu ratchet: lấy mức hiện tại làm mốc, mục
tiêu là chặn hồi quy, **không** tuyên bố code đã đạt chuẩn:

| Gate | Project | Condition (overall) | Mốc hiện tại |
|---|---|---|---|
| `baotin-scaffold` | 8 service scaffold | `bugs > 0`, `vulnerabilities > 0`, `blocker_violations > 1` | bugs 0, blocker 1 (`java:S2699` trên `contextLoads`) |
| `baotin-api-gateway` | `baotin_api-gateway` | `bugs > 2`, `vulnerabilities > 0`, `critical_violations > 8` | bugs 2, critical 8 |
| `baotin-auth-service` | `baotin_auth-service` | `bugs > 6`, `vulnerabilities > 0`, `critical_violations > 35` | bugs 6, critical 35 |
| `baotin-frontend` | `baotin_frontend` | `coverage < 80`, `vulnerabilities > 0`, `bugs > 0` | coverage 84.9 |

`Sonar way` vẫn là gate **default** của server: không xoá, không hạ ngưỡng.

### Đồng bộ gate

`Sync-QualityGates.ps1` tạo/đồng bộ 4 gate trên và gán cho 11 project. Script
idempotent (chạy lại cho cùng kết quả) và tự kiểm chứng việc gán gate. Ngưỡng nằm
trong `$GateSpecs` ở đầu file — muốn siết thì sửa ở đó rồi chạy lại:

```powershell
.\Sync-QualityGates.ps1 -TokenFile C:\Users\USER\.baotin\sonarqube-gate-admin.token
```

Script in `SONAR_GATES_SYNCED projects=11 gates=4` khi thành công.

Ba điểm cần nhớ:

- Gate chỉ được áp ở **lần phân tích kế tiếp**, nên sau khi đổi gate phải chạy lại
  job Jenkins tương ứng rồi mới đọc `project_status`.
- Đổi ngưỡng **không** cần sửa `Jenkinsfile` hay `ci/sonar-quality-gate.sh`: script
  gate phía pipeline chỉ đọc verdict (`OK`/`ERROR`) từ server.
- Gate tạo qua API được SonarQube seed sẵn 6 condition `new_*` của gate default; script
  tự bỏ chúng để gate chỉ còn đúng condition trong spec.

## Bằng chứng enforcement

Gate được kiểm chứng bằng negative test thật, không chỉ nhánh happy:

- **Gate thật** `baotin-scaffold` bị siết tạm `blocker_violations GT 0` (Threat-Service
  đang có blocker=1) → Threat-Service#15 **FAILURE**, log:
  `SONAR_GATE FAIL project=baotin_threat-service gate=ERROR`,
  `condition blocker_violations=ERROR (actual=1 threshold=0)`, `script returned exit code 5`.
- Chạy lại `Sync-QualityGates.ps1` → `[~] blocker_violations: GT 0 -> GT 1`;
  Threat-Service#16 **SUCCESS** với `gate=OK` (ngưỡng hội tụ về mốc).
- Trước đó một probe gate `coverage LT 80` (overall) cũng cho build fail **exit 5**;
  probe đã được gỡ.
- Live run đầy đủ với định nghĩa gate cuối cùng: 11/11 job SUCCESS và
  `project_status` của 11/11 project = `OK`, mọi condition có `actual` thật
  (coverage 82.1 / 71.3 / 84.9, blocker 1, bugs 2 / 6 / 0, critical 8 / 35).

## Vận hành

- Xem log: `docker logs --since 10m sonarqube`
- Reset dữ liệu phân tích (giữ cấu hình tay): xoá project trong UI, hoặc
  `docker compose down -v` để xoá toàn bộ DB (mất hết gate/token đã cấu hình).
- Backup Postgres: `docker exec sonarqube-postgres pg_dump ...` (xem `docker-compose.yml`
  để lấy tên service/container).

## Rà soát 2026-10-13

`Configure-AnalysisIdentity.ps1` tách account CI `baotin-ci-analysis` khỏi admin:
CI chỉ có analyse/browse project, thử tạo quality gate trả403. Token admin dùng
cho sync nằm trong `sonarqube-gate-admin.token`, không đưa vào Jenkins credential.
Token CI ở `sonarqube-ci.token`; credential và canonical properties được cập nhật
không restart controller. Password/token files ngoài repo có ACL hạn chế. Khi chạy
lại helper, token cũ của account CI bị thu hồi sau khi credential mới cập nhật.

Kiểm tra persistence: token property chấp nhận whitespace quanh key/`=`, ghi lại một
canonical `SONAR_TOKEN=...`; thiếu hoặc trùng key bị từ chối. `Test-AnalysisProperties.ps1`
kiểm tra6 cases. Sau replay boot hook đã lưu, credential phải vẫn khớp external CI token.

Pipeline mới kiểm tra JSON, đúng project/analysisId, đủ metric có giá trị số,
ignoredConditions=false và ngưỡng không yếu hơn policy đã review. Empty OK cũng FAIL.
Không suy ra chất lượng từ riêng status=OK. Coverage floors: Auth70%, Gateway80%,
Frontend80% (hai mức backend hết hiệu lực từ 2026-10-15 — xem mục cuối);
scaffold chỉ kiểm tra startup/static baseline, không chứng nhận nghiệp vụ.
Nợ Sonar hiện tại: Auth6 bugs/35 critical; Gateway2 bugs/8 critical; scaffold1 blocker.
Chưa tự hạ ngưỡng để pass. Lộ trình: sửa từng issue kèm regression, siết baseline.
Chưa chứng nhận Clean-as-You-Code. Version0.0.1-SNAPSHOT không tự chứng minh New Code;
cần baseline và SCM blame đo được. Các kết luận tuyệt đối “Sonar way luôn vô hiệu”
ở ghi chép cũ chỉ mô tả probe tại thời điểm đó, không phải thuộc tính của SonarQube.
Server hiện là9.9.8 legacy, chỉ loopback/private CI. Trước VPS/production: backup Sonar DB,
chọn LTA còn hỗ trợ, kiểm tra đường upgrade chính thức và scan image mới; không tự migrate
Sonar DB trong tranche này. Không xóa project hoặc down -v để làm gate xanh.
Token operator runner mặc định đọc ngoài repo; ACL operator/SYSTEM/Administrators.

## Rà soát 2026-10-15 — bỏ test khỏi lane release

Test (unit + integration) chuyển hẳn sang máy dev và lane GitHub Verify
(`.github/workflows/ci-cd.yml` chạy `./gradlew --no-daemon test bootJar` trên
`pull_request`). `Jenkinsfile` backend gộp build + gate thành một stage
`Build and SonarQube quality gate` chạy `./gradlew clean bootJar` rồi gọi
`ci/sonar-quality-gate.sh`; bỏ `junit`/archive `build/reports/**`, và `build.gradle` bỏ
`sonar.dependsOn(jacocoTestReport)` cùng `sonar.coverage.jacoco.xmlReportPaths`.

Hệ quả: phân tích CI backend không còn coverage, nên condition `coverage` đã bị bỏ khỏi
2 gate backend (`baotin-api-gateway`, `baotin-auth-service`) trong `Sync-QualityGates.ps1`.
Frontend vẫn chạy test như cũ nên vẫn giữ `coverage < 80`. Đổi lại lane release nhanh hơn,
không phải chờ test, và gate backend không còn condition đo trên metric vắng mặt.

Nợ đã biết: lane release không còn test nên chất lượng chỉ dựa vào lane PR + máy dev;
coverage floor backend ở mục "Rà soát 2026-10-13" **không còn hiệu lực** từ mốc này. Muốn
có coverage ở CI trở lại thì phải chạy test trong lane release (hoặc publish coverage từ
lane khác trước khi gate) rồi thêm lại condition.

## Release order — 2026-10-16

Backend: compile main classes + Sonar/gate, rồi mới bootJar. Frontend: source Sonar/gate
trước typecheck/Expo export. Cả11 release jobs không chạy test/JaCoCo/LCOV. Verify CI vẫn
chạy tests; không ngụ ý verify đã pass chỉ vì release SUCCESS. Frontend release gate
không còn coverage condition và CLI không nhập LCOV cũ. Bugs/vulnerabilities và các
ratchet hiện hữu vẫn bắt buộc; không dùng status OK rỗng để bỏ metric.
