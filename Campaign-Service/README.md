# Campaign Service

Nhóm các threat target liên quan thành chiến dịch lừa đảo bằng rule-based signals trước khi cân nhắc ML.

## Trạng thái

**Startup scaffold**: service hiện chỉ cung cấp Spring Boot Actuator để build, khởi động và kiểm tra health. Chưa có API nghiệp vụ, persistence, Kafka consumer/producer hoặc integration production.

## Chạy local

Yêu cầu JDK 21.

```powershell
.\gradlew.bat test
.\gradlew.bat bootRun
```

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

Đổi cổng bằng `SERVER_PORT`. Docker image chạy non-root và dùng `/actuator/health` làm healthcheck.

## Ownership

- Sở hữu dự kiến: campaign.campaigns, campaign.campaign_members, campaign.threat_links.
- Không tự xác minh report, không sở hữu threat target và không dùng AI để quyết định trạng thái nghiệp vụ.
- Public/internal APIs và events phải được chốt trong `../Contracts` trước khi tích hợp.

## Tiếp tục triển khai

Xem [`TODO.md`](TODO.md). Chỉ thêm JPA/Flyway/Kafka/Redis/Security khi task tương ứng được implement kèm cấu hình và integration test; không thêm dependency chỉ để giữ chỗ.


## Jenkins JAR build/release

Pipeline lấy source từ main đã được bảo vệ, chạy JDK21 bootJar, secret/dependency scan và SBOM cho JAR; khi BUILD_DOCKER_IMAGE=true thì build/scan image và publish ghcr.io/vietluccuu/campaign-service theo commit SHA/digest. Lane release này không chạy test (test chạy ở máy dev và ở lane PR GitHub Actions) và không có auto-deploy qua SSH. Orchestration/deployment được quản lý tại Infra; Docker image cũng dùng được cho deployment thủ công. GitHub Actions chỉ verify trên PR/manual, không deploy production khi push.

## SonarQube quality gate

Release: checkout/preflight, compile main classes + Sonar, chờ quality gate PASS,
sau đó mới `bootJar`, security/SBOM và Docker/publish tùy chọn. Java cần bytecode
để phân tích nhưng không đóng JAR trước gate. Không chạy test/JUnit/JaCoCo ở release;
unit/integration tests vẫn ở verify lane và máy dev. Không lấy coverage cũ để chứng minh
release. Gate vẫn bắt buộc metric bảo mật/chất lượng, bind analysisId, không bỏ qua lỗi.
Policy và regression: `../Infra/ci/README.md`. Scaffold chưa có nghiệp vụ.
## Owning Search snapshot exporter — 2026-11-12 (candidate)

The manual `com.baotin.campaign.operator.SearchSnapshotExport` CLI accepts a NEW
absolute JSONL output path and the Search rebuild `startedAt` ISO-8601 timestamp.
Set SOURCE_EXPORT_URL, SOURCE_EXPORT_USER and SOURCE_EXPORT_PASSWORD_FILE for
this process only. It verifies its actual owning DB user, uses a repeatable-read
read-only transaction, never reads another business schema, and writes a hashed
complete owner manifest next to the data. No runtime HTTP endpoint or scheduler
is added. Export files/passwords stay outside Git.

Launch with the approved JDK21 JAR and PropertiesLauncher, for example:
`java -Dloader.main=com.baotin.campaign.operator.SearchSnapshotExport -cp build/libs/Campaign-Service-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher L:/external/campaign.jsonl STARTED_AT_ISO`

Role baotin_campaign_exporter is metadata-only. This business scaffold declares an empty public snapshot ONLY after verifying there are no persisted relations in its own campaign namespace. Any future persistence requires an owning exporter implementation; unsupported data causes failure, not fabricated rows. The PostgreSQL driver is CLI-only; no JDBC starter/runtime datasource is added.

Full owning-role/privacy/negative privilege tests must pass before promotion.
