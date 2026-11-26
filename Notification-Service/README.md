# Notification Service

Quản lý device token, preference, watchlist URL, in-app/push delivery và audit lần gửi.

## Trạng thái

Đã có API nghiệp vụ, Flyway schema `notification`, JWT Resource Server, ownership checks,
pagination, browser/native device token, FCM adapter, Kafka Inbox/Outbox, retry/backoff,
invalid-token cleanup, URL watchlist canonicalization và scheduled retention. Contract công khai nằm tại
`../Contracts/openapi/notification-service.yaml`; event `notification.sent` nằm tại
`../Contracts/events/notification/notification-sent-v1.schema.json`.

FCM chỉ bật khi có credential. Kafka chỉ bật khi có broker; khi tắt, API vẫn chạy và
các delivery attempt PUSH được ghi `SKIPPED` thay vì gửi sai.

## Chạy local

Yêu cầu JDK 21 và PostgreSQL đã tạo schema/role `notification` (xem `../Infra/init.sql`).

```bash
export JAVA_HOME=/path/to/jdk-21
export DB_URL=jdbc:postgresql://localhost:54322/baotin
export DB_USER=baotin_notification
export DB_PASSWORD=...
export DB_MIGRATION_ENABLED=true
./gradlew test
./gradlew bootRun
```

Health:

```bash
curl http://localhost:8186/actuator/health
curl http://localhost:8186/actuator/health/liveness
curl http://localhost:8186/actuator/health/readiness
```

Docker image chạy non-root, `SERVER_PORT=8080`, healthcheck `/actuator/health`.
Liveness chỉ kiểm tra process; readiness gồm PostgreSQL và sẽ drain traffic khi DB
không truy cập được. Kafka/FCM không nằm trong readiness vì outbox/consumer tự retry
và provider có thể tạm thời không khả dụng mà API in-app vẫn phục vụ được.

## API

Tất cả endpoint dưới `/api/v1/notifications/**` và `/api/v1/watchlist/**` yêu cầu Bearer
access token Auth-Service. `sub` là userId duy nhất được tin; client không gửi userId/role.

```text
GET    /api/v1/notifications?page&size&unread
GET    /api/v1/notifications/unread-count
PATCH  /api/v1/notifications/{id}/read
POST   /api/v1/notifications/read-all
DELETE /api/v1/notifications/{id}
GET    /api/v1/notifications/preferences
PATCH  /api/v1/notifications/preferences
GET    /api/v1/notifications/devices
POST   /api/v1/notifications/devices
DELETE /api/v1/notifications/devices/{id}
GET    /api/v1/watchlist?page&size
POST   /api/v1/watchlist
DELETE /api/v1/watchlist/{id}
```

Watchlist canonicalize scheme/host/default port/path/query, bỏ fragment và tracking
parameters. Cùng URL trả cùng `urlTargetId`/`canonicalHash`; POST lặp là idempotent.
URL có credential hoặc query chứa token/mật khẩu/OTP bị từ chối để không lưu secret.
`reputation.updated` khớp theo `targetId` hoặc canonical hash nên không phụ thuộc
Threat-Service đã tồn tại.

## Cấu hình

```text
DB_URL, DB_USER, DB_PASSWORD
DB_MIGRATION_ENABLED, DB_MIGRATION_USER, DB_MIGRATION_PASSWORD
AUTH_JWKS_URI, JWT_ISSUER, JWT_AUDIENCE
NOTIFICATION_ALLOWED_ORIGINS
NOTIFICATION_PUSH_ENABLED, FIREBASE_PROJECT_ID, FIREBASE_CREDENTIALS_PATH
NOTIFICATION_EVENTS_ENABLED, KAFKA_BOOTSTRAP_SERVERS
KAFKA_SECURITY_PROTOCOL, KAFKA_SASL_MECHANISM, KAFKA_SASL_JAAS_CONFIG
NOTIFICATION_EVENTS_GROUP_ID, REPUTATION_EVENTS_TOPIC, REPORT_EVENTS_TOPIC, NOTIFICATION_EVENTS_TOPIC
NOTIFICATION_RISK_MIN_SCORE_DELTA
NOTIFICATION_RETENTION_ENABLED, NOTIFICATION_RETENTION_CLEANUP_CRON
NOTIFICATION_RETENTION_NOTIFICATIONS_DAYS, NOTIFICATION_RETENTION_DELIVERY_DAYS
NOTIFICATION_RETENTION_INBOX_DAYS, NOTIFICATION_RETENTION_OUTBOX_DAYS
NOTIFICATION_RETENTION_INACTIVE_DEVICE_DAYS
NOTIFICATION_RETENTION_BATCH_SIZE, NOTIFICATION_RETENTION_MAX_BATCHES
```

Không commit credential. Kafka consumer dùng Inbox idempotency, DLT `<topic>.DLT`;
outbox publisher dùng lease/retry. Provider token không được trả về API hoặc ghi log.

Retention chạy mặc định 03:15 UTC mỗi ngày: notification cũ hơn 180 ngày, delivery
attempt/inbox/outbox đã xử lý cũ hơn 30 ngày, device token inactive quá 180 ngày.
Inbox chưa xử lý và outbox chưa publish không bị xóa vì là bằng chứng lỗi cần điều tra.
Mỗi bảng xóa theo batch có giới hạn (`BATCH_SIZE` × `MAX_BATCHES`), nên lần chạy đầu
trên dữ liệu lớn sẽ đuổi kịp dần qua các ngày thay vì giữ transaction dài.

## Ownership

- Sở hữu: `notification.device_tokens`, `preferences`, `watchlist`, `notifications`,
  `delivery_attempts`, `inbox_events`, `outbox_events`.
- Không gửi email xác minh/reset của Auth trong tranche này và không tự tính risk.
- Không đọc/ghi schema service khác. User id, target id và report id là opaque UUID.

## Jenkins JAR build/release

Pipeline lấy source từ main đã được bảo vệ, chạy JDK21 bootJar, secret/dependency scan và SBOM cho JAR; khi BUILD_DOCKER_IMAGE=true thì build/scan image và publish ghcr.io/vietluccuu/notification-service theo commit SHA/digest. Lane release này không chạy test (test chạy ở máy dev và ở lane PR GitHub Actions) và không có auto-deploy qua SSH. Orchestration/deployment được quản lý tại Infra; Docker image cũng dùng được cho deployment thủ công. GitHub Actions chỉ verify trên PR/manual, không deploy production khi push.

## SonarQube quality gate

Release: checkout/preflight, compile main classes + Sonar, chờ quality gate PASS,
sau đó mới `bootJar`, security/SBOM và Docker/publish tùy chọn. Java cần bytecode
để phân tích nhưng không đóng JAR trước gate. Không chạy test/JUnit/JaCoCo ở release;
unit/integration tests vẫn ở verify lane và máy dev. Không lấy coverage cũ để chứng minh
release. Gate vẫn bắt buộc metric bảo mật/chất lượng, bind analysisId, không bỏ qua lỗi.
Policy và regression: `../Infra/ci/README.md`.

## Gateway / canonical Reputation bridge — 2026-10-27

Integrated deployments set `THREAT_SERVICE_URI` to the private Threat origin and mount `notification.threat-token` (or `NOTIFICATION_THREAT_SERVICE_TOKEN`) with the distinct Notification target-resolution credential (different from Report). Both settings are required together. A configured Threat outage fails watch creation with 503 instead of silently creating an unbound watch. Legacy standalone mode remains available when both settings are absent.

V3 adds schema-local watch-to-Threat UUID bindings, preserving public watch IDs and old URL normalization. Re-adding an existing watch repairs its binding idempotently; no existing watch is deleted. `reputation.score.changed`/`threatTargetId` and legacy `reputation.updated`/`targetId` both update matches, with unchanged Inbox deduplication and significant-change notifications. No target URL is fetched and no public caller supplies target IDs. See `../Contracts/docs/notification-integration.md`. Runtime grants must include the new binding table; migration stays separate from runtime.

## Independent business-schema migration

Run the reviewed JAR with `-Dloader.main=com.baotin.notification.operator.DatabaseMigration -cp SERVICE.jar org.springframework.boot.loader.launch.PropertiesLauncher` using DB_URL, DB_MIGRATION_USER, DB_MIGRATION_PASSWORD_FILE and DB_RUNTIME_USER. The password file is a bounded regular external file; the operator applies additive migrations with a schema-scoped migrator and grants only the listed business tables. Runtime sets SPRING_FLYWAY_ENABLED=false and DB_MIGRATION_ENABLED=false and never mounts migration credentials. Preserve a database backup and compatible prior application before migration; no down-migration or data deletion is part of rollback.


## Release dependency gate — 2026-10-28

Jenkins FullStack28/Auth41 identified new HIGH advisories in transitive Snappy and Guava. Runtime constraints select snappy-java1.1.10.9 and Guava33.7.2-jre. This dependency-only patch preserves APIs/auth/events; scanner policy remains enabled. Repo wrapper tests and packaged-JAR scans must pass before promotion.
