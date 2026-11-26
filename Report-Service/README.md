# Report Service

## Public acceptance — 2026-10-27

FullStack **#27 SUCCESS**, Report **#30**, is deployed on `https://baotin.rimiprod.me`
through Gateway. Real Web owner/evidence and moderator MFA/queue/verify flows,
request-info/resubmit, rejection, version409, self-review403 and report Kafka owner
notifications passed. Temporary fixture ADMIN/MODERATOR roles were removed; audit
remains and no existing real account privileges changed. See
[`../Infra/docs/api-integration-acceptance-2026-10-27.md`](../Infra/docs/api-integration-acceptance-2026-10-27.md).

## Report/Evidence + Moderation v1

Implemented API, PostgreSQL/Flyway persistence, owner/RBAC checks, private MinIO evidence, state/version/history, idempotency and transactional Kafka outbox. Current setup and verified limits: [REPORT_RUNBOOK.md](REPORT_RUNBOOK.md). Deployment requires the external configuration described in the runbook.

## Internal analytics read endpoint

`GET /internal/v1/analytics/moderation` reads the moderation queue and counts for Analytics-Service. Requires a `MODERATOR` or `ADMIN` bearer token; Analytics forwards the caller's token and Report revalidates the role. Internal only — not routed by API-Gateway.

Sở hữu report, evidence metadata, moderation, trạng thái review và audit history.

## Trạng thái

Report/Evidence và Moderation đã triển khai và nghiệm thu Web trên domain thật: API có JWT/ownership, PostgreSQL/Flyway, MinIO private và Kafka transactional outbox. Nghiệm thu thiết bị Android/iOS vẫn là công việc riêng.

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

- Sở hữu: report.reports, evidence, status_history, idempotency_keys và outbox_events.
- Không tự tạo threat ownership, không để AI set VERIFIED và không đọc trực tiếp auth schema.
- Public/internal APIs và events phải được chốt trong `../Contracts` trước khi tích hợp.

## Tiếp tục triển khai

Xem [`TODO.md`](TODO.md) và [`REPORT_RUNBOOK.md`](REPORT_RUNBOOK.md) để cấu hình, kiểm thử và nghiệm thu deployment.


## Jenkins JAR build/release

Pipeline lấy source từ main đã được bảo vệ, chạy JDK21 bootJar, secret/dependency scan và SBOM cho JAR; khi BUILD_DOCKER_IMAGE=true thì build/scan image và publish ghcr.io/vietluccuu/report-service theo commit SHA/digest. Lane release này không chạy test (test chạy ở máy dev và ở lane PR GitHub Actions) và không có auto-deploy qua SSH. Orchestration/deployment được quản lý tại Infra; Docker image cũng dùng được cho deployment thủ công. GitHub Actions chỉ verify trên PR/manual, không deploy production khi push.

## SonarQube quality gate

Release: checkout/preflight, compile main classes + Sonar, chờ quality gate PASS,
sau đó mới `bootJar`, security/SBOM và Docker/publish tùy chọn. Java cần bytecode
để phân tích nhưng không đóng JAR trước gate. Không chạy test/JUnit/JaCoCo ở release;
unit/integration tests vẫn ở verify lane và máy dev. Không lấy coverage cũ để chứng minh
release. Gate vẫn bắt buộc metric bảo mật/chất lượng, bind analysisId, không bỏ qua lỗi.
Policy và regression: `../Infra/ci/README.md`.

## Independent business-schema migration

Run the reviewed JAR with `-Dloader.main=com.baotin.report.operator.DatabaseMigration -cp SERVICE.jar org.springframework.boot.loader.launch.PropertiesLauncher` using DB_URL, DB_MIGRATION_USER, DB_MIGRATION_PASSWORD_FILE and DB_RUNTIME_USER. The password file is a bounded regular external file; the operator applies additive migrations with a schema-scoped migrator and grants only the listed business tables. Runtime sets SPRING_FLYWAY_ENABLED=false and DB_MIGRATION_ENABLED=false and never mounts migration credentials. Preserve a database backup and compatible prior application before migration; no down-migration or data deletion is part of rollback.


## Release dependency gate — 2026-10-28

Jenkins FullStack28/Auth41 identified new HIGH advisories in transitive Snappy and Guava. Runtime constraints select snappy-java1.1.10.9 and Guava33.7.2-jre. This dependency-only patch preserves APIs/auth/events; scanner policy remains enabled. Repo wrapper tests and packaged-JAR scans must pass before promotion.
