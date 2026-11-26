# Jenkins — cấu hình CI/CD cho 10 backend service

> **2026-10-19:** controller/dind/Sonar/DB đã chạy healthy trong `baotin-ci`, Sonar9001.
> FullStack#12 PASS đủ11 child gates rồi deploy JAR + web; không tạo archive theo yêu cầu.
> Tuyến adoption có backup: [`../ci-stack/README.vi.md`](../ci-stack/README.vi.md).
> Không chạy Compose phụ trợ cũ dưới đây cùng stack mới. Những đoạn “không
> recreate controller” mô tả cấu hình lịch sử, không phải hướng dẫn migration mới.

> **Auth/Gateway hiện tại:** dùng pipeline build-only mới trong từng repo và promotion
> `Infra/team/Jenkinsfile` qua node Windows chỉ-deploy. Xem `windows-agent/README.md` và
> `../team/README.md`. Các phần SSH/SKIP_TESTS/auto-deploy phía dưới là legacy cho các
> scaffold khác, không áp dụng cho Auth/Gateway. Không chạy apply-config/restart để tạo agent mới.

Thư mục này chứa **cấu hình** cho Jenkins, không chứa Jenkins.

## Auth/Gateway: authenticated pipeline validation

Create an API token from the existing Jenkins user's Security/API Token settings and save
only its value in a regular file outside the workspace (for example the path shown in
`.env.validation.example`). Restrict the file ACL to the current operator, SYSTEM and
local Administrators. Do not paste tokens into chat, command arguments, `.env` or Git.

```powershell
.\Test-JenkinsPipelines.ps1 -Username YOUR_JENKINS_USERNAME `
  -TokenFile C:\Users\USER\.baotin\jenkins-api.token `
  -OutputFile M:\BaoTin\.changes\jenkins-pipeline-validation.json
```

This authenticates against the existing local loopback controller, checks the actual user,
obtains a CSRF crumb when configured and submits Auth/Gateway/Infra Jenkinsfiles to the
real declarative validator. It does not create jobs/nodes, run builds, restart services or
change the controller. Redirects/proxy routing are rejected; errors never echo server bodies
or token values. A report records only pipeline paths/hashes and validation state; failed
checks cannot leave a stale success report. A token-file path cannot be used as output.

The default URL is now explicit IPv6 loopback `http://[::1]:8080/`. On this Windows host,
IPv4 `127.0.0.1:8080` can be handled by another local proxy while Docker's Jenkins remains
available on IPv6. Before reading/sending any credential the runner performs an anonymous
`/login` request and requires a Jenkins version header. A non-Jenkins endpoint is rejected,
not tried with secrets. This does not change Docker port mappings or other applications.
The endpoint check also invalidates stale success reports. `localhost` is allowed after
identification, but explicit IPv6 avoids JVM address-family selection for the agent.

`Test-JenkinsValidation.ps1` exercises these paths through an ephemeral authenticated HTTP
fixture with synthetic credentials (15 checks including secret-bearing errors). That is
transport/credential/gate proof, not a claim that the live Jenkins DSL accepted a pipeline.
Until a real external token file is supplied, the live validator still returns anonymous403.

The existing build dind currently repeats containerd startup timeouts. Diagnostics observed
PID-file value62 with no matching containerd process; Docker Desktop's separately tested
staging daemon does not certify this build lane. Keep existing containers/volumes unchanged
until the operator chooses the targeted runtime repair; project code does not auto-recreate
controller/dind or delete their state.

> **Nguyên tắc quan trọng:** container `jenkins-blueocean` là container bạn tự dựng
> và đã cấu hình ổn định. Không có file nào ở đây tạo mới / ghi đè / recreate nó.
> `docker-compose.yml` trong thư mục này **cố ý không định nghĩa** service đó.

## 1. Thành phần

| Thành phần | Vai trò | Ai quản lý |
|---|---|---|
| `jenkins-blueocean` | Jenkins controller, port **8080**, volume `jenkins-data` | **Bạn** (dựng tay) |
| `docker` (dind) | Docker daemon cho build/push image, TLS ở `:2376` | `docker-compose.yml` ở đây |
| `jenkins-agent-jdk21` | Build agent tuỳ chọn — **hiện không dùng** | profile `agent`, không bật |

Cấu hình thực tế đang chạy:

```
jenkins-blueocean   image myjenkins:latest  network jenkins  ports 8080:8080, 50000:50000
                    volume jenkins-data:/var/jenkins_home
                    volume jenkins-docker-certs:/certs/client:ro
                    DOCKER_HOST=tcp://docker:2376  DOCKER_TLS_VERIFY=1  DOCKER_CERT_PATH=/certs/client

docker (dind)       image docker:27-dind     network jenkins  privileged
                    volume jenkins-docker-certs:/certs  DOCKER_TLS_CERTDIR=/certs
```

### Vì sao controller tự build, không cần agent riêng

Controller đã có sẵn mọi thứ pipeline cần:

- JDK 21 (temurin)
- `git` 2.47
- Docker CLI + buildx (nói chuyện với dind qua TLS)
- 2 executor, ~900 GB trống

Không có `gradle`/`mvn` hệ thống — pipeline dùng `./gradlew` (wrapper trong từng repo).

Vì vậy script init gắn label `baotin-docker` cho controller và mọi `Jenkinsfile`
đều khai báo `agent { label 'baotin-docker' }`.

Muốn tách build sang agent sau này: bật `docker compose --profile agent up -d`,
tạo node trong Jenkins, rồi đổi `AGENT_LABEL` trong `scripts/service-map.ps1` và
sinh lại `Jenkinsfile`. Không phải sửa gì trong thư mục này.

## 2. Cách cấu hình được nạp

Jenkins chạy mọi file `.groovy` trong `/var/jenkins_home/init.groovy.d/` **mỗi lần
khởi động**, với quyền SYSTEM. Đây là lý do không cần mật khẩu admin hay API token.

```
init/baotin-ci.groovy   ──copy──▶  /var/jenkins_home/init.groovy.d/baotin-ci.groovy
init/baotin-ci.properties ──copy──▶ /var/jenkins_home/baotin-ci.properties
                          restart
                             ▼
                    Jenkins nạp script, tự cấu hình
```

Chạy:

```powershell
cd Infra\jenkins
.\apply-config.ps1                 # copy + restart + in kết quả
.\apply-config.ps1 -NoRestart      # chỉ copy
.\apply-config.ps1 -CreateProperties   # tạo lại baotin-ci.properties từ mẫu
```

Script **idempotent**: chạy lại bao nhiêu lần cũng không tạo credential/job trùng.

### Script init làm gì

| Bước | Kết quả |
|---|---|
| 1 | Gắn label `baotin-docker java21 gradle docker` cho controller |
| 2 | Đặt biến môi trường toàn cục: `GITHUB_OWNER`, `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PATH`, `SONAR_HOST_URL`, `TZ` |
| 3 | Tạo credentials: `baotin-github-scm`, `ghcr-registry`, `sonar-token` (và `deploy-ssh-key` nếu đã điền `DEPLOY_SSH_KEY`) |
| 4 | Tạo folder `baotin` + 12 pipeline job (10 backend, Frontend, FullStack — lấy `Jenkinsfile` từ repo GitHub) |

Mỗi job backend có sẵn parameter `BUILD_DOCKER_IMAGE`; FullStack dùng `DEPLOY_RELEASE`.
Các parameter legacy (`SKIP_TESTS`, `DEPLOY_ENV`, `IMAGE_TAG`) chỉ còn ở job scaffold cũ.

### Credential và env cho SonarQube

`sonar-token` (Secret text) và global env `SONAR_HOST_URL=http://sonarqube:9000` do
script init tạo/đặt, **không** tạo tay. Giá trị lấy từ `SONAR_TOKEN` trong
`baotin-ci.properties`; chưa điền thì script bỏ qua credential và log `BỎ QUA`.

Class của credential được nạp qua reflection và thử `org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl`
trước (plain-credentials ≥ 199), fallback về `com.cloudbees.plugins.credentials.impl.StringCredentialsImpl`
cho plugin cũ — nếu chỉ hard-code một package, Jenkins boot sẽ log
`ClassNotFoundException` và không tạo được credential.

Server, token và vòng đời phân tích nằm ở `../sonarqube/README.md`.


### Vì sao toàn bộ script dùng reflection

Script hook biên dịch **trước khi** Jenkins nạp xong plugin. `import` class của
plugin ở đầu file sẽ lỗi `unable to resolve class`. Nên mọi class của plugin được
nạp động qua `pluginManager.uberClassLoader` rồi khởi tạo bằng reflection.

Đổi lại, thân script được bọc trong `try/catch(Throwable)`: một lỗi cấu hình chỉ
ghi log, **không** làm Jenkins fail boot (nếu fail, container sẽ restart vô hạn).

Thêm một điểm cần lưu ý: `GString` (chuỗi nội suy kiểu `"a${b}"`) **không phải**
`java.lang.String`, nên reflection sẽ báo `argument type mismatch` — helper
`newInstance` tự ép về `String` trước khi gọi constructor.

## 3. Điền secrets

```powershell
Copy-Item init\baotin-ci.properties.example init\baotin-ci.properties   # nếu chưa có
notepad init\baotin-ci.properties
```

Điền các giá trị:

| Key | Ý nghĩa | Ghi chú |
|---|---|---|
| `GITHUB_TOKEN` | PAT để checkout repo + push/pull image | scope: `repo`, `read:packages`, `write:packages` |
| `SONAR_TOKEN` | User token của SonarQube cho quality gate | sinh ở `../sonarqube/README.md`; chưa điền thì credential `sonar-token` bị bỏ qua |
| `DEPLOY_SSH_KEY` | Private key OpenSSH để deploy | dán cả khối `-----BEGIN...-----END...-----`, giữ `\n` hoặc xuống dòng thật đều được |

Giá trị nào còn là placeholder (`REPLACE_WITH_...`) thì script **bỏ qua** credential
tương ứng và ghi log `BỎ QUA ...`, các phần còn lại vẫn được cấu hình bình thường.

> `init/baotin-ci.properties` chứa secret thật và đã được `.gitignore` trong thư mục này.
> **Không commit file đó.**

## 4. Kiểm tra sau khi chạy

Kết quả mong đợi:

```
  label controller = 'baotin-docker java21 gradle docker'
  biến môi trường toàn cục: GITHUB_OWNER, DEPLOY_HOST, DEPLOY_USER, DEPLOY_PATH, TZ
  credential 'github-scm': tạo mới
  credential 'ghcr-registry': tạo mới
  credential 'deploy-ssh-key': tạo mới
  job: 10 tạo mới, 0 cập nhật, 0 lỗi (tổng 10)
  HOÀN TẤT cấu hình BaoTin CI/CD

Số job trong folder 'baotin': 10
```

Kiểm tra trong UI (cần đăng nhập — Jenkins đang bật security, ẩn danh nhận 403):

```
http://localhost:8080/job/baotin/
```

Kiểm tra job trên đĩa:

```powershell
docker exec jenkins-blueocean ls /var/jenkins_home/jobs/baotin/jobs/
```

## 5. Khôi phục khi có sự cố

**Jenkins restart vô hạn (crash loop)** — nguyên nhân gần như luôn là script init
throw ra `Error` (không phải `Exception`) khiến Jenkins coi là boot failure. Gỡ
script rồi khởi động lại:

```powershell
docker stop jenkins-blueocean
docker run --rm -v jenkins-data:/jh alpine rm -f /jh/init.groovy.d/baotin-ci.groovy
docker start jenkins-blueocean
```

Sau đó sửa `init/baotin-ci.groovy` và chạy lại `apply-config.ps1`.

**Xem log cấu hình của lần boot gần nhất:**

```powershell
docker logs --since 5m jenkins-blueocean 2>&1 | Select-String 'baotin-ci'
```

**Xem lỗi thật sự của script:**

```powershell
docker logs --since 5m jenkins-blueocean 2>&1 | Select-String 'Failed to run script' -Context 0,15
```

**Kiểm tra Jenkins kết nối được dind:**

```powershell
docker exec jenkins-blueocean docker info --format "Server: {{.ServerVersion}} | Driver: {{.Driver}}"
# -> Server: 27.5.1 | Driver: overlay2
```

## 6. Thêm một service mới

1. Thêm dòng vào `scripts/service-map.ps1`
2. `pwsh -File scripts/generate-cicd.ps1` — sinh `Dockerfile`, `.dockerignore`,
   `Jenkinsfile`, `.github/workflows/ci-cd.yml`
3. Thêm service vào `Infra/deploy/docker-compose.prod.yml` + `.env.example`
   (chạy lại `scripts/generate-deploy-infra.ps1` nếu sửa generator)
4. Chạy lại `.\apply-config.ps1` — job thứ 11 xuất hiện, các job cũ được cập nhật
   tại chỗ (không mất build history)

## 7. Ghi chú về image và plugin

- `myjenkins:latest` là image **local**, không có trên registry nào. Nếu dựng lại
  máy, cần build lại image đó trước.
- Plugin được cài trong **volume** `/var/jenkins_home/plugins/`, không phải trong
  image. `jenkins-plugin-cli` tải vào `/usr/share/jenkins/ref/plugins/` (nằm trong
  layer image) nên phải copy thủ công vào volume rồi restart mới có hiệu lực.
- Thử `curl http://localhost:8080/login` để biết Jenkins đã lên chưa (mong đợi `200`).


## Current full-stack route and reviewed source pins

Confirmed owner: vietluccuu. Primary artifacts are ten JARs and Frontend/build/web.tar.gz;
FullStack orchestrates them, with direct promotion only on the restricted Windows node.
Docker images are optional. FullStack#3 build-only succeeded with108 tests. Node/config
and live promotion remain separate gates. Folder credential baotin-github-scm is dedicated
repository-read only; build jobs never receive operator runtime secrets.

Actual GitHub main branches were unprotected (org Free/private). A main-only branch selector
is not a reviewed-source boundary. The operator approved exact commit pinning BEFORE
Jenkinsfile retrieval for all twelve jobs. Store approval JSON outside Git with operator,
SYSTEM and Administrators ACL only; the example has intentionally invalid placeholders.

```powershell
# Copy example OUTSIDE Git, replace each value with a maintainer-reviewed current main SHA.
.\Apply-ReviewedPins.ps1 -ManifestFile EXTERNAL_APPROVAL_JSON -Username OPERATOR -TokenFile EXTERNAL_API_TOKEN
# After reviewing the full commit/source, explicitly apply it:
.\Apply-ReviewedPins.ps1 -ManifestFile EXTERNAL_APPROVAL_JSON -Username OPERATOR -TokenFile EXTERNAL_API_TOKEN -Apply
.\Test-ReviewedPins.ps1
```

Preflight requires all12 clean local repositories, exact confirmed origins and manifest
SHA equality with current remote main. It never interprets a branch/tag as an approval.
Apply validates every idle job/SCM boundary before mutation, retains credentials/extensions,
sets one exact commit with lightweight retrieval disabled and persists the same approval
atomically to Jenkins home. A failed transaction restores prior definitions. It starts no
build, node, deployment or controller restart and does not relax sandbox/authorization.

Bootstrap reads baotin-approved-main.json from Jenkins home. Missing/invalid approval
returns before configuration and retains existing definitions; it never falls back to a
moving main. Existing declarative parameters are preserved (no legacy skip-tests reset).
Copy the reviewed bootstrap with -NoRestart; updating this hook must not recreate Jenkins.

After main advances, existing in-pipeline current-main checks can fail closed until the
maintainer reviews the new full commit and applies a fresh manifest. The old pinned
Jenkinsfile is still the only one retrieved; an unreviewed new Jenkinsfile is not loaded.
Job configuration, approval manifest and bootstrap write access belong to maintainers.
Labels alone are not deployment-node authorization; do not add team accounts under the
current FullControlOnceLoggedIn strategy without a separate reviewed permission setup.

## Existing Docker27 dind stale containerd PID guard

This host reproduced a restart loop after an unclean Desktop restart: the persisted
`/var/run/docker/containerd/containerd.pid` referenced a PID reused by dockerd. Vendor
entrypoint removes Docker PID files but does not remove this stale containerd PID.
The operator approved an inline guard in this existing container's writable entrypoint;
container/image IDs, mounts/TLS, cache volumes and Jenkins controller are retained.

`dind/containerd-pid-guard.sh` removes ONLY that regular nonsymlink PID file after numeric
validation, process-name check and a second unchanged-PID read. A live containerd PID is
preserved; absent PID is a no-op; malformed/nonregular paths fail closed. No process is
signalled or killed. `dind/Patch-EntrypointCopy.ps1` patches a distinct hash-verified vendor
script copy; it never uploads, restarts or recreates a live container itself.

```powershell
.\dind\Patch-EntrypointCopy.ps1 -OriginalFile BACKED_UP_ENTRYPOINT -OutputFile MODIFIED_COPY -ExpectedSha256 ORIGINAL_SHA256
.\dind\Test-PidGuard.ps1 -EntrypointFile MODIFIED_COPY -TempDirectory FRESH_TEST_DIRECTORY
```

Regression uses the actual pinned local dind image's BusyBox sh with no network/ports/
Docker socket/capabilities, not a daemon. Linux symlinks are tested because Git Bash can
emulate them as file copies on Windows. Review/apply the checked copy atomically with
original owner0755, then restart only the same approved idle dind container and verify
TLS daemon readiness/stability and unchanged controller/mounts. Preserve the original
script/hash for rollback. This writable-layer patch persists across container restarts,
not recreation; a new dind container/image requires its own review and guard installation.
Never remove image/cache/database/certificate volumes to fix this PID metadata fault.
