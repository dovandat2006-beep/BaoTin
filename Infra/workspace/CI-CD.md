# BảoTin — CI/CD

Tài liệu này mô tả toàn bộ luồng build → test → push image → deploy cho **10 backend
service** trong workspace này.

Mỗi service là một repository độc lập trên GitHub, nên **mỗi service có pipeline
riêng**. Có hai đường pipeline song song, dùng cùng một cách đóng gói và cùng một
script deploy:

| Đường | File trong repo service | Chạy ở đâu | Dùng khi |
|---|---|---|---|
| **GitHub Actions** | `.github/workflows/ci-cd.yml` | Runner của GitHub | CI mặc định: mọi push/PR |
| **Jenkins** | `Jenkinsfile` | `jenkins-blueocean` (máy local/on-prem) | CI nội bộ, deploy chủ động, chạy tay có tham số |

Cả hai đều kết thúc bằng việc **SSH vào máy đích và gọi `./deploy.sh`** — cùng một
script, cùng một `docker-compose.prod.yml`, nên kết quả deploy giống hệt nhau.

---

## 1. Kiến trúc

```
                       ┌──────────────────────────────┐
   push/PR ───────────▶│  GitHub Actions (per repo)   │
                       │  build-test → docker → deploy │──┐
                       └──────────────────────────────┘  │
                                                          │ SSH
                       ┌──────────────────────────────┐  │
   bấm Build ─────────▶│  Jenkins: job per service    │  │
                       │  build-test → docker → deploy │──┤
                       └──────────────────────────────┘  │
                              │ docker CLI (TLS)         │
                              ▼                          ▼
                       ┌──────────────┐        ┌───────────────────────┐
                       │ dind         │        │  máy staging / prod   │
                       │ docker:27-dind│       │  Infra/deploy/*       │
                       └──────────────┘        │  docker compose pull  │
                              │                │  docker compose up -d │
                              ▼                └───────────────────────┘
                       ghcr.io/<owner>/<service>:<tag>
```

Registry được dùng chung: image build ở đâu cũng push lên
`ghcr.io/<owner>/<service>:<tag>`, và máy deploy luôn `pull` từ đó.

---

## 2. Quy ước đặt tên

Đây là hợp đồng giữa các thành phần, đừng đổi lẻ tẻ:

| Thứ | Quy ước | Ví dụ |
|---|---|---|
| Thư mục service trong workspace | PascalCase | `Auth-Service` |
| Tên image / Docker service / compose service | kebab-case | `auth-service` |
| Env key (viết hoa, `-`→`_`) | `Environ` | `AUTH_SERVICE` |
| Port trên host | `PORT_<ENV_KEY>` | `PORT_AUTH_SERVICE=8081` |
| Tag image trong `.env` | `IMAGE_TAG_<ENV_KEY>` | `IMAGE_TAG_AUTH_SERVICE=ghcr.io/baotin/auth-service:main` |
| Mật khẩu DB service | `DB_PASS_<ENV_KEY>` — **phải khớp `Infra/init.sql`** | `DB_PASS_AUTH` |

Tất cả các tên trên suy ra từ **một nguồn duy nhất**: `scripts/service-map.ps1`.
Đừng sửa tay file được sinh ra.

---

## 3. Chuẩn bị

Trước khi làm bất cứ bước nào dưới đây, cần:

- Docker Desktop đang chạy (cho Jenkins + dind trên máy dev).
- `pwsh` (PowerShell 7+).
- Một tài khoản GitHub có quyền tạo repo + package.
- Một máy Linux cho staging/prod có Docker + `docker compose` v2 và SSH mở.

---

## 4. Bước 1 — Đưa 10 service lên GitHub

Workspace này **không phải** một git repo. Từng thư mục service cũng chưa phải.
Mỗi service phải thành một repo riêng vì pipeline đọc `Jenkinsfile` /
`.github/workflows/ci-cd.yml` từ root của repo đó.

Tạo repo trên GitHub (private), đặt tên trùng tên thư mục, rồi với từng service:

```bash
cd Auth-Service
git init -b main
git add .
git commit -m "chore: initial import"
git remote add origin git@github.com:baotin/Auth-Service.git
git push -u origin main
```

> Nếu `GITHUB_OWNER` trong `Infra/jenkins/init/baotin-ci.properties` không phải
> `baotin`, sửa lại cho khớp — nó được dùng để dựng URL clone của mọi job Jenkins.

`.gitignore` của mỗi service (do `start.spring.io` sinh) đã bỏ qua `build/` và
`.gradle/`, đồng thời **giữ lại** `gradle/wrapper/gradle-wrapper.jar` (cần thiết để
`./gradlew` chạy được trong CI). Cứ kiểm tra nhanh `git status` trước khi push lần
đầu để chắc chắn không có `build/` lọt vào.

---

## 5. Bước 2 — Secrets trên GitHub

Vào **từng repo** → *Settings* → *Secrets and variables* → *Actions* → *New
repository secret*. Thêm:

| Secret | Bắt buộc | Ý nghĩa |
|---|---|---|
| `GHCR_TOKEN` | khuyến nghị | PAT có `write:packages`. Nếu bỏ trống, workflow fallback về `GITHUB_TOKEN` (chỉ dùng được nếu repo cho phép ghi package bằng `GITHUB_TOKEN`). |
| `DEPLOY_HOST` | khi deploy | hostname/IP máy đích |
| `DEPLOY_USER` | khi deploy | user SSH |
| `DEPLOY_SSH_KEY` | khi deploy | private key OpenSSH (không mật khẩu) |
| `DEPLOY_PATH` | không | thư mục chứa `docker-compose.prod.yml`, mặc định `/opt/baotin` |
| `DEPLOY_ENV` | không | `staging` hoặc `prod`; dùng khi push lên nhánh deploy |

PAT cho `GHCR_TOKEN` cần scope: `repo`, `read:packages`, `write:packages`.

---

## 6. Bước 3 — Jenkins

Làm theo `Infra/jenkins/README.md`. Tóm tắt:

```powershell
cd Infra\jenkins
Copy-Item init\baotin-ci.properties.example init\baotin-ci.properties
notepad init\baotin-ci.properties      # điền GITHUB_TOKEN + DEPLOY_SSH_KEY
.\apply-config.ps1
```

Kết quả: 10 job trong folder `baotin` tại <http://localhost:8080/job/baotin/>.

Job Jenkins lấy `Jenkinsfile` trực tiếp từ repo GitHub, nên sau khi có code trên
GitHub thì job chạy được ngay, không cần cấu hình gì thêm trong UI.

---

## 7. Bước 4 — Máy deploy (staging / prod)

Trên máy đích, tạo thư mục và đưa hạ tầng deploy lên (repo `Infra` hoặc copy thủ công):

```bash
sudo mkdir -p /opt/baotin && sudo chown "$USER" /opt/baotin
cd /opt/baotin
cp /đường/dẫn/Infra/deploy/* .          # deploy.sh, rollback.sh,
                                        # docker-compose.prod.yml, .env.example,
                                        # nginx.conf, init.sql
chmod +x deploy.sh rollback.sh
cp .env.example .env
nano .env                               # điền mật khẩu thật
```

Các việc phải làm trong `.env`:

1. `POSTGRES_PASSWORD`, `MINIO_ROOT_PASSWORD` — mật khẩu mạnh.
2. `DB_PASS_*` cho 8 service dùng PostgreSQL — **phải trùng** với user/password mà
   `init.sql` tạo. Nếu không, service sẽ không kết nối được DB.
3. `GITHUB_OWNER` — owner GitHub chứa package.

Lần đầu khởi động toàn bộ hạ tầng nền (DB, Redis, Kafka, MinIO, nginx):

```bash
docker compose --env-file .env pull
docker compose --env-file .env up -d postgres redis kafka minio nginx
```

> `init.sql` chỉ chạy lần đầu, khi volume `postgres_data` còn trống. Nếu đổi
> `DB_PASS_*` sau đó, phải `ALTER USER` trong DB hoặc xoá volume và tạo lại.

Từ đây mỗi service được `deploy.sh` cập nhật độc lập — không cần `up -d` cả stack.

---

## 8. Bước 5 — Chạy thử một pipeline

**Qua Jenkins:** mở job `baotin/Auth-Service` → *Build with Parameters* →
`DEPLOY_ENV = none` (chỉ build + push), `SKIP_TESTS = false` → *Build*.

**Qua GitHub Actions:** tạo PR nhỏ vào `main` của một service → xem job `Build &
Test` chạy. Merge vào `main` để chạy tiếp `docker`.

> ⚠️ Trước khi push/merge vào `main` lần đầu, **hãy set đủ secrets deploy** (mục 5).
> Push lên `main` sẽ kích hoạt luôn job `deploy`; thiếu `DEPLOY_HOST` /
> `DEPLOY_USER` / `DEPLOY_SSH_KEY` thì job đó sẽ đỏ (build vẫn xanh). Nếu chưa có
> máy đích, xoá tạm nhánh `main` khỏi `on.push.branches` trong workflow rồi thêm lại sau.

Kiểm tra image đã lên registry:

```bash
docker pull ghcr.io/baotin/auth-service:main
```

---

## 9. Quy trình thường ngày

Hai đường pipeline có cùng logic rẽ nhánh:

| Sự kiện | Build | Test | Push image | Deploy |
|---|---|---|---|---|
| PR vào `main`/`develop` | ✅ | ✅ | ❌ | ❌ |
| Push `develop` | ✅ | ✅ | ✅ | ❌ |
| Push `main` | ✅ | ✅ | ✅ | ✅ (nếu có secrets deploy) |
| Push tag `v1.2.3` | ✅ | ✅ | ✅ | ✅ → `staging` |
| Chạy tay, `deploy_env=none` | ✅ | ✅ | ✅ | ❌ |
| Chạy tay, `deploy_env=staging\|prod` | ✅ | ✅ | ✅ | ✅ tới môi trường đã chọn |

**Nhánh deploy** được quyết định bởi `DEPLOY_BRANCH` — mặc định `main`. Đổi trong
`scripts/service-map.ps1`, sinh lại `Jenkinsfile` và workflow rồi chạy lại
`generate-cicd.ps1`.

**Môi trường deploy** (GitHub Actions):

- Chạy tay có chọn `deploy_env` → đúng môi trường đã chọn.
- Push `main` → `prod`.
- Mọi trường hợp khác (push tag, chạy tay trên nhánh khác) → `staging`.

**Tag image** được sinh khác nhau giữa hai đường chạy, nhưng **tag dùng để deploy
luôn khớp một tag đã push**:

| Hoàn cảnh | GitHub Actions push | Jenkins push | Tag đem đi deploy |
|---|---|---|---|
| nhánh `main` | `main`, `latest`, `build-<sha7>` | `main`, `latest` | `main` |
| nhánh `develop` | `develop`, `build-<sha7>` | `build-<sha7>` | `develop` (GHA) |
| nhánh khác | `build-<sha7>` | `build-<sha7>` | `build-<sha7>` |
| tag `v1.2.3` | `1.2.3`, `1.2`, `build-<sha7>` | `1.2.3` | `1.2.3` |
| `IMAGE_TAG` param (Jenkins) | — | chính giá trị đó | chính giá trị đó |

`build-<sha7>` là 7 ký tự đầu của commit SHA. Ghi chú:

- `latest` chỉ được gắn trên nhánh `main`, để staging không đè lên bản production.
- `main`/`develop` không được gắn cho nhánh khác, tránh nhánh phụ ghi nhãn dùng chung.

Máy deploy ghi lại tag cũ vào `.deploy-state/<service>.prev` trước mỗi lần deploy,
phục vụ rollback.

### Ghi chú riêng cho Jenkins

Job có 3 parameter:

- `SKIP_TESTS` — bỏ test, chỉ build jar.
- `DEPLOY_ENV` — `none` / `staging` / `prod`. Mặc định `none`.
- `IMAGE_TAG` — ép tag image, thường để trống.

Build chạy trên chính controller (label `baotin-docker`) và dùng `./gradlew` trong
repo. Docker CLI trên controller nói chuyện với dind qua TLS
(`DOCKER_HOST=tcp://docker:2376`), nên không cần mount socket của host.

---

## 10. Rollback

Rollback **không** cần build lại — chỉ đổi tag trong `.env` và kéo image cũ về:

```bash
cd /opt/baotin
./rollback.sh auth-service
```

Script đọc `.deploy-state/auth-service.prev` và quay về tag của lần deploy trước.
Nếu pipeline deploy thất bại (container không healthy sau 5 phút), `deploy.sh` **tự
động rollback**; trường hợp chưa từng có bản chạy trước đó thì script dừng và báo
lỗi thay vì cố deploy một tag rỗng.

---

## 11. Thêm một service mới

1. Thêm dòng vào `scripts/service-map.ps1` (Dir, Image, EnvKey, Port, Schema, DbPass).
2. `pwsh -File scripts/generate-cicd.ps1` — sinh `Dockerfile`, `.dockerignore`,
   `Jenkinsfile`, `.github/workflows/ci-cd.yml` cho service.
3. `pwsh -File scripts/generate-deploy-infra.ps1` — cập nhật
   `Infra/deploy/docker-compose.prod.yml` + `.env.example`.
4. Nếu service dùng PostgreSQL: thêm schema/user vào `Infra/init.sql`.
5. Trên máy deploy: cập nhật `docker-compose.prod.yml`, thêm `PORT_*` /
   `IMAGE_TAG_*` / `DB_PASS_*` vào `.env`.
6. `pwsh -File Infra/jenkins/apply-config.ps1` — job mới xuất hiện trong folder
   `baotin`, các job cũ được cập nhật tại chỗ (giữ build history).

---

## 12. Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp | Cách xử lý |
|---|---|---|
| Job Jenkins `Couldn't find any revision to build` | Repo chưa có nhánh `main`, hoặc credential `github-scm` chưa được tạo | Push `main` lên GitHub; điền `GITHUB_TOKEN` rồi chạy lại `apply-config.ps1` |
| Jenkins không clone được repo private | `GITHUB_TOKEN` thiếu scope `repo` | Tạo lại PAT, cập nhật properties, chạy lại `apply-config.ps1` |
| `denied: permission_denied` khi push image | PAT thiếu `write:packages` | Sửa PAT (Jenkins) hoặc secret `GHCR_TOKEN` (GHA) |
| `Cannot connect to the Docker daemon` trong Jenkins | dind chết, hoặc mất volume cert | `docker compose -f Infra/jenkins/docker-compose.yml up -d docker`; kiểm tra `docker exec jenkins-blueocean docker info` |
| Container mới chạy rồi thoát ngay | Sai `DB_PASS_*` so với `init.sql`, hoặc thiếu biến môi trường | Xem `docker compose logs <service>`; đối chiếu `Infra/init.sql` |
| Service không healthy, `deploy.sh` tự rollback | Config/migration sai | Xem log script in ra; sửa rồi deploy lại |
| Jenkins restart vô hạn | Script init trong `init.groovy.d` lỗi | Xem mục *Khôi phục khi có sự cố* trong `Infra/jenkins/README.md` |
| Jenkins trả HTTP 403 khi mở URL | Đang bật security, chưa đăng nhập | Đăng nhập bằng tài khoản admin của bạn — 403 với ẩn danh là bình thường |

---

## 13. Bảo mật

- **Không commit** `Infra/jenkins/init/baotin-ci.properties` (đã `.gitignore`) và
  `Infra/deploy/.env` (đã `.gitignore`). Chỉ commit file `.example`.
- Private key deploy trên máy đích không nên là key dùng chung cho việc khác. Tạo
  riêng một key cho CI, và giới hạn quyền trên `authorized_keys` nếu có thể.
- `deploy.sh` cần quyền ghi `.env`; đặt quyền file `600` và chỉ cấp cho user deploy.
- Image trên `ghcr.io` nên để **private** cho tới khi có chủ đích public.

---

## 14. File liên quan

| File | Vai trò |
|---|---|
| `scripts/service-map.ps1` | Nguồn sự thật: danh sách 10 service |
| `scripts/generate-cicd.ps1` | Sinh `Dockerfile`, `.dockerignore`, `Jenkinsfile`, `.github/workflows/ci-cd.yml` |
| `scripts/generate-deploy-infra.ps1` | Sinh `Infra/deploy/*` |
| `Infra/jenkins/README.md` | Cấu hình Jenkins chi tiết |
| `Infra/jenkins/init/baotin-ci.groovy` | Script hook tạo label/env/credentials/job |
| `Infra/jenkins/apply-config.ps1` | Nạp cấu hình vào container đang chạy |
| `Infra/deploy/deploy.sh` | Deploy 1 service (dùng bởi cả GHA và Jenkins) |
| `Infra/deploy/rollback.sh` | Quay lui 1 service về tag trước |
| `Infra/deploy/docker-compose.prod.yml` | Stack staging/prod |
| `Infra/init.sql` | Tạo schema/user PostgreSQL — phải khớp `DB_PASS_*` |
| `docker-compose.yml` (root) | Stack **local dev** — không dùng cho staging/prod |
