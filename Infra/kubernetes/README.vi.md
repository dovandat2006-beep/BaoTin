# Deploy BảoTin bằng Kustomize: Kubernetes và K3s

Hai lựa chọn `DEPLOY_TARGET=k8s|k3s` bổ sung cho JAR/Docker/VPS. Mặc định vẫn
`jar`; `DEPLOY_RELEASE=false` không chạy deploy hoặc kết nối cluster. Đây không
phải thao tác chuyển bản production hiện tại sang Kubernetes.

## 1. Điều kiện operator chuẩn bị

- kubectl phù hợp server (minor skew tối đa1), Python3.12+, PowerShell7.4+ cho
  Jenkins Windows agent. Có sẵn Kustomize trong kubectl.
- Cluster K8s có CNI thực sự enforce NetworkPolicy (ví dụ Calico/Cilium). K3s phải
  giữ network-policy controller hoạt động; không tắt để làm smoke test đạt.
- StorageClass/CSI thực, đủ chỗ cho hai model; K3s mặc định `local-path`, một
  model replica/RWO/Recreate, không phải HA/RWX hoặc chống mất một node.
- PostgreSQL/Redis/Kafka/MinIO private và reachable **từ pod**, không dùng Windows
  localhost hoặc Docker network alias của máy hiện tại. Kafka advertised listeners
  cũng phải pod-reachable. Bucket/evidence policy và domain topics được provision.
- Một DB vật lý `baotin`, schema/runtime role riêng, migration/operator tách biệt.
  Chạy additive migration bằng công cụ owning service trước rollout; runtime luôn
  Flyway disabled và không nhận migrator/operator credentials. Không tự migrate.
- Nếu dùng DB hiện có, giữ đúng Auth signing/encryption keys/key IDs và source model
  packages. Tạo khóa mới rồi trỏ vào dữ liệu cũ không phải quy trình migration.
- Backend images cần glibc/OpenMP và UID1001; web UID101. Images phải chứa curl
  trong backend runtime để chạy read-only compatibility và real API smoke Jobs.
  Producer của thư mục này đáp ứng hợp đồng đó từ artifact đã qua gate.
- Kubeconfig/context/namespace/API endpoint được chọn rõ, TLS CA hợp lệ; không
  `use-context`, không TLS skip, không fallback cluster-admin cho Jenkins.

## 2. Bootstrap bằng credential operator, không phải deployer

Render chỉ tạo JSON, không gọi cluster. Chọn namespace trước, giữ cùng tên trong
config và các lệnh. K8s dùng StorageClass của cluster nếu không truyền cờ; K3s
chọn `local-path` nếu không chỉ định khác.

```powershell
python -B kubernetes/bootstrap.py --namespace baotin --profile k8s --storage-class YOUR_CLASS --stage namespace > M:/external/baotin-namespace.json
# Hoặc --profile k3s. Namespace phải tồn tại thật trước server dry-run resources:
kubectl --kubeconfig ADMIN_KUBECONFIG --context EXPLICIT_CONTEXT apply --dry-run=server -f M:/external/baotin-namespace.json
kubectl --kubeconfig ADMIN_KUBECONFIG --context EXPLICIT_CONTEXT apply -f M:/external/baotin-namespace.json
python -B kubernetes/bootstrap.py --namespace baotin --profile k8s --storage-class YOUR_CLASS --stage resources > M:/external/baotin-bootstrap.json
kubectl --kubeconfig ADMIN_KUBECONFIG --context EXPLICIT_CONTEXT --namespace baotin apply --dry-run=server -f M:/external/baotin-bootstrap.json
kubectl --kubeconfig ADMIN_KUBECONFIG --context EXPLICIT_CONTEXT --namespace baotin apply -f M:/external/baotin-bootstrap.json
kubectl --kubeconfig ADMIN_KUBECONFIG --context EXPLICIT_CONTEXT get namespace baotin -o jsonpath='{.metadata.uid}'
```

Bootstrap tạo dedicated namespace/PSS restricted, model PVC và deployer SA/RBAC.
Server dry-run không persist namespace trong cùng một List; vì vậy không dry-run
toàn bộ bootstrap vào namespace chưa có rồi gọi đó là thành công.
Deployer chỉ quản lý tài nguyên ứng dụng trong namespace, đọc metadata Secret/PVC,
đọc đúng namespace để kiểm tra UID và tạo/xóa các probe Job của lần deploy.
Không được xóa PVC, Secret, namespace hoặc sửa project khác. Pod ứng dụng không
mount Kubernetes API token. Deployer là trusted operator: quyền tạo Deployment
trong namespace cũng là quyền dùng Secret ở namespace đó; RBAC không bảo vệ khỏi
pipeline/operator ác ý đã được trao quyền này. Giữ SCM pin/approval/guard Jenkins.

Operator cấp kubeconfig cho `baotin-deployer` với token ngắn hạn, CA/server/context
đúng và namespace đã chọn. Không dùng kubeconfig admin của K3s trong Jenkins.
Giữ kubeconfig/token ở file external/ACL hạn chế; không đưa token vào argv/log/Git.
Nên bật encryption-at-rest cho Secret trong cluster theo quy trình quản trị cluster.

Trên Windows deploy host, `New-DeployerKubeConfig.ps1 -AdminKubeConfig ... -Context
... -Namespace baotin -OutputFile EXTERNAL_NEW_KUBECONFIG` tạo file scoped token2h
với ACL hạn chế. Helper chỉ lấy PUBLIC CA field của cluster đã chọn, không dump
admin config/private key; không đổi current-context/global kubeconfig. Với cluster
không inline CA, operator chuẩn bị public CA đúng thay vì bật TLS skip.

## 3. Secret và model: provision riêng, không đưa vào image

Copy `config.example.json` và `.env.example` ra **ngoài workspace**, điền endpoint,
namespace UID/context và CIDR egress private thật. File example chưa đủ điều kiện
deploy. Thêm bốn đường dẫn từ `.env.example` vào external `jar.env` của deploy node.

Operator tạo các Secret versioned `immutable:true` trước rollout:

| Secret | Nội dung file/config-tree của owning service |
|---|---|
| `auth-service-runtime-v1` | `spring.datasource.password`, Auth provider/config secrets, stable key IDs và provider callback/return allowlists đúng public origin |
| `baotin-auth-keys-v1` | `private.pem`, `encryption.bin` — đúng identity đang dùng khi nối DB hiện có |
| `api-gateway-runtime-v1` | `spring.data.redis.password` |
| `threat-service-runtime-v1` | DB password, `scan.hash-key`, optional `scan.content-key`, `scan.model-token`, `scan.reputation-token`, `threat.report-service-token`, `threat.notification-service-token` |
| `model-inference-service-runtime-v1` | `model.service-token` khớp Threat |
| `reputation-service-runtime-v1` | DB password, `reputation.threat-read-token` khớp Threat, optional Redis/Kafka credentials |
| `report-service-runtime-v1` | DB password, private Threat credential, MinIO scoped credentials, optional Kafka credentials |
| `notification-service-runtime-v1` | DB password, `notification.threat-token`, optional actual FCM/Kafka credentials |

Tên tham chiếu có thể đổi trong config; mỗi service dùng Secret riêng. Renderer
mount private tree trước public tree, nên URI/role/port/peer list Kubernetes trong
public ConfigMap thay thế cấu hình Windows/localhost cũ; không copy config nguyên
xi rồi bỏ qua kiểm chứng. Public ConfigMap không chứa password/token/private key.
Registry pull Secret `baotin-registry` là pull-only, khác package-write credential
của producer. Credential rotation và model/DB/namespace replacement là thao tác
review riêng, không ngầm thực hiện qua deploy thông thường.

Model PVC phải Bound và có đủ `text-1.0.1/`, `url-1.0.1/` (hoặc tên package đã điền),
bao gồm manifest, checksum, external weights, tokenizer và metadata parity thật.
Operator có thể dùng Pod trong `bootstrap-model-loader.yaml` để copy real packages
bằng `kubectl cp`, kiểm tra manifest rồi xóa **đúng Pod loader**, không xóa claim.
Không dùng model fixtures để làm readiness UP. Ứng dụng chỉ mount PVC read-only.

## 4. Chuẩn bị đúng OCI images từ artifact Jenkins

Trong FullStack mới, child jobs chạy tuần tự và parent dừng ở maintainer approval.
Lấy đúng11 immutable child build numbers của parent để tạo external `builds.json`.
Dùng artifact fetcher hiện hữu để xác minh archived Sonar/security/SBOM/source/JAR,
rồi producer đóng gói đúng JAR/web archive, không build source lại hoặc bỏ APK.

```powershell
pwsh -NoProfile -File alternative/Fetch-JenkinsArtifacts.ps1 -BuildsFile EXTERNAL_BUILDS_JSON -OutputDirectory M:/BaoTin-k8s-work/verified-UNIQUE -Username JENKINS_USER -TokenFile EXTERNAL_JENKINS_TOKEN_FILE
pwsh -NoProfile -File kubernetes/Build-PublishImages.ps1 -VerifiedArtifactsDirectory M:/BaoTin-k8s-work/verified-UNIQUE -PublicOrigin https://baotin.rimiprod.me -WorkRoot M:/BaoTin-k8s-work -OutputManifest EXTERNAL_REGISTRY_IMAGES_JSON -Publish -RegistryPrefix ghcr.io/vietluccuu/baotin-kube -RegistryUsername REGISTRY_USER -RegistryTokenFile EXTERNAL_PACKAGE_WRITE_TOKEN
```

`WorkRoot` tồn tại, ngoài workspace; không dùng L: cho stage riêng của tác vụ.
Producer dùng local Docker context/socket, OCI build chỉ có `app.jar` hoặc verified
`web/`; glibc/libgomp cho model, source/secret/model không vào build context. Web
giữ nguyên APK bytes/manifest/source snapshot. Scan OS+JAR và SBOM đủ11 images trước
push; scanner failures không được hạ gate. Registry auth dùng directory private
tạm riêng, bị xóa sau login/push; không thay Docker login/context sẵn có của user.
Không `-Publish` thì chỉ tạo local IDs/scans; **local image ID không dùng để deploy**.

Registry manifest có đủ11 `registry/repository@sha256:...` và provenance mỗi repo:
`build`, `sourceRevision`, `artifactSha256`, thêm `publicOrigin` cho Frontend. Jenkins
binding kiểm tra lại actual successful child build/JAR evidence, source/origin và
stream hash web archive; không lưu thêm APK/JAR copy chỉ để đối chiếu manifest.

## 5. Validate, deploy và verify

```powershell
pwsh -NoProfile -File kubernetes/Deploy-Kubernetes.ps1 -Profile k8s -ConfigFile EXTERNAL_CLUSTER_JSON -ImageManifest EXTERNAL_REGISTRY_IMAGES_JSON -KubeConfig EXTERNAL_DEPLOYER_KUBECONFIG -StateDirectory M:/BaoTin-kubernetes-state -ValidateOnly
# Sau đó chọn FullStack DEPLOY_TARGET=k8s hoặc k3s, DEPLOY_RELEASE=true.
# Hoặc operator dùng cùng runner (không -ValidateOnly) với file đã review.
```

Validate-only local không kết nối cluster; nếu đưa thêm BuildsFile/JarEnvFile,
binding check kết nối Jenkins loopback nhưng vẫn không kết nối cluster. Live runner
luôn truyền kubeconfig/context/namespace từng lệnh, kiểm tra endpoint/TLS/namespace
UID/PSS, K3s identity, version skew, Bound model PVC và immutable Secret identities.
Không đổi current-context hay cài CNI/ingress/CSI/registry vào cluster âm thầm.

Server dry-run, apply common resources rồi rollout tuần tự. Chỉ service web và
Gateway là public entry; mặc định chưa có ingress. Các private services chỉ ClusterIP
và default-deny NetworkPolicy. Hai probe compatibility đọc DB bằng Auth runtime
role, không migrate. Exact frontend/Gateway Pod IPs được reconcile /32 hoặc /128,
không tin toàn PodCIDR; rerun quy trình khi reschedule/scale ngoài rollout đổi peers.
Ingress ngoài namespace cần `edgeSource` selectors và `edgePeers` thật, không wildcard.

Smoke Jobs không backoff/retry POST: actual Gateway->Threat->ONNX->DB, hai scan có
chủ đích cho Kafka/Reputation round trip, guest admin401, private Gateway internal401
(security chạy trước route lookup; public web nginx ẩn `/internal` bằng404). Cùng
model positive reachability từ Threat và denied reachability từ frontend chứng minh
CNI enforcement; không có policy thực thì rollout không được nghiệm thu. Jobs mang
role `probe`, không vào Service endpoints; bị xóa theo đúng nonce/ownership sau test.

Node nhiều network interface cần IP/interface ổn định qua reboot. Kiểm tra cả node
InternalIP và dataplane thực; controller còn chạy hoặc NetworkPolicy đã apply chưa
chứng minh traffic bị chặn. Negative probe chỉ đạt khi không nhận HTTP và curl báo
lỗi kết nối7/timeout28; lỗi DNS hoặc HTTP403/503 không được coi là network denial.
Probe Job mới chờ tối đa5 health GET chỉ đọc để CNI cập nhật policy/ipset; hai scan
POST và negative network probe không tự retry. Health của runtime cũ không thay
thế việc xác nhận kết nối từ đúng probe identity mới.

Giữ private exposure tới khi kiểm thử các flow Auth/cookie/OAuth/report/evidence thật
với public origin dự định. Không lấy health của scaffold làm chứng nhận nghiệp vụ.

## 6. Rollback và Cloudflare

State directory external lưu accepted config/image references/Secret+PVC identities,
release records và lock; không lưu secret values. Candidate lỗi quay về compatible
accepted application release, refresh exact peer IPs rồi smoke/readiness lại. Nếu
rollback không khỏe, báo operator review; không tuyên bố thành công. First deployment
chưa có accepted predecessor chỉ dừng workload candidate có ownership nonce đúng.
Không xóa namespace, Secret, PVC, dependency data hoặc down-migrate DB.

Lock còn lại sau crash/reboot phải kiểm tra process/ownership trước khi operator
gỡ; không suy diễn chỉ từ PID hay tự xóa lock rồi deploy song song.

Nếu token hết hạn hoặc API bị gián đoạn trong lúc cleanup, kiểm tra process và
trạng thái cluster thực trước khi chạy lại. Khôi phục cùng dependency/credential,
gia hạn đúng scoped ServiceAccount qua operator; không đưa admin kubeconfig vào
pipeline. Với lần đầu chưa có `accepted.json`, operator kiểm tra namespace UID,
nonce `baotin.io/release-id` và từng workload của candidate trước khi dừng/retire
đúng Deployment chưa nghiệm thu. Giữ Secret/PVC/data; không tạo lịch sử accepted
giả để vượt guard. Khi đã có predecessor, dùng quy trình rollback tương thích.

Cloudflare/tunnel/DNS bản primary hiện tại không bị runner sửa. Cutover là bước
operator riêng sau nghiệm thu: giữ old origins/config để rollback; chọn một private
ingress/reverse-proxy origin reachable từ connector, kiểm tra API prefix, HTTPS
original-scheme/trusted peers, Web refresh/CSRF, OAuth callback, evidence và APK.
Đừng chạy thêm connector cùng tunnel nhưng origin khác trong khi primary còn trỏ
origin cũ; mixed routing không phải canary an toàn. Không public internal/DB/model.

## 7. Kiểm thử source

```powershell
python -B -m unittest discover -s kubernetes/tests
pwsh -NoProfile -File ci/Test-Pipelines.ps1
```

Real `kubectl kustomize` chạy cho cả profile trong tests; metadata/Docker transport
unit fixtures không được gọi là live rollout hoặc model/APK acceptance. Ghi riêng
bằng chứng actual cluster, private network denial, full application smoke và failed-
candidate rollback. Goal chỉ hoàn tất khi các bằng chứng được yêu cầu đã kiểm tra.

## Search URL projection — 2026-11-11

Search now requires its own runtime Secret, baotin_search role, owning migration
and Kafka topics baotin.search.targets.v1 / baotin.reputation.events.v1. Catalog
grants Gateway→Search, Search→PostgreSQL/Kafka only; no Redis/MinIO or host port.
Provide spring.datasource.password in the external Search config Secret. Configure
Kafka pod-reachable advertised listeners. Existing seven-service config JSON needs
one added search-service secretRefs entry. Runtime Flyway remains false.

Current source audit also adds the already-implemented read-only Analytics to the
configured-service set: separate analytics-service Secret, baotin_analytics DB role,
Gateway→Analytics and Analytics→Auth/JWKS. No Kafka/Redis/MinIO access is added for
Analytics. These are render/test changes, not a new live-cluster acceptance.

Full Search now requires owning migration through V3 plus4 streams: existing baotin.search.targets.v1 / baotin.reputation.events.v1 and baotin.threat.search.events.v1 / baotin.campaign.search.events.v1. Runtime journal SELECT/INSERT+sequence usage stay inside search; rebuild/owner credentials are NEVER runtime Secrets. No additional network/host ports are needed. Owner snapshots and manual rebuild/cutover require separate operator credentials.
