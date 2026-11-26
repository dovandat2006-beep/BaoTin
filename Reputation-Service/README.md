# Reputation Service

Tính và lưu risk/reputation score cho `ThreatTarget` từ report, rule, AI và campaign signals.

## Trạng thái

P0/P1 đã triển khai:

- contract-first trong `../Contracts` (OpenAPI nội bộ + event schema);
- Flyway schema `reputation`, DB role riêng, optimistic locking;
- calculation v1 có version/weights/risk bands/confidence/time-decay cấu hình được;
- consume `report.verified|rejected|revoked`, `threat.updated`, `campaign.updated` qua Inbox;
- transactional Outbox cho `reputation.score.changed`;
- internal read/recalculate APIs, JWT Resource Server và Redis cache tùy chọn (không phải source of truth);
- test score bounds, duplicate/out-of-order events, report revoke, persistence/outbox và authorization.

Report/Threat đã có implementation. Consumer nhận cả report lifecycle canonical (`targetId/status/version`) và projection cũ (`threatTargetId/severity`). Policy v1 cho canonical VERIFIED dùng contribution 100; MESSAGE_TEMPLATE ánh xạ vào OTHER với trọng số hiện có. REJECTED loại contribution; event chưa terminal không tạo verified contribution. Legacy severity, Inbox và xử lý timestamp cũ được giữ nguyên.

## API nội bộ

| Method | Path | Role |
|---|---|---|
| `GET` | `/internal/v1/reputation/targets/{threatTargetId}` | JWT `ADMIN`/`MODERATOR` hoặc khóa nội bộ chỉ đọc |
| `POST` | `/internal/v1/reputation/targets/{threatTargetId}/recalculate` | `ADMIN` |

Service tự xác minh JWT của Auth (RS256, issuer, audience, `typ=at+jwt`, `token_use=access`) và authorize tại boundary. Các route `/internal/**` không được publish qua API-Gateway.

Khóa riêng tùy chọn `REPUTATION_THREAT_READ_TOKEN` (hoặc config-tree
`reputation.threat-read-token`) chỉ cho Threat đọc đúng GET target bằng header
`X-Service-Token`. Khóa là 32 byte ngẫu nhiên biểu diễn bằng 64 ký tự hex thường;
để trống sẽ tắt cơ chế này, cấu hình sai định dạng làm startup thất bại. Giữ khóa
trong file secret ngoài Git với ACL hạn chế, không đưa vào client/EXPO_PUBLIC.
Principal chỉ có `SCOPE_reputation.read`, không có role quản trị. Khóa sai/rỗng,
header lặp/ghép dấu phẩy hoặc kèm Authorization trả 401; khóa đúng gọi ghi/route
nội bộ khác trả 403, không thay đổi dữ liệu. JWT cũ và quyền POST ADMIN giữ nguyên.
Triển khai provider trước rồi mới bật caller; rollback tắt lookup trước khi quay
lại provider cũ. Không đổi schema, dữ liệu, host port hoặc tunnel.

`POST` không có body vẫn recalculate target đã tồn tại. Target mới cần `targetType`. Signal/count override chỉ dành cho backfill/demo; luồng chuẩn là event-driven.

## Calculation v1

`finalScore = clamp(0..100, Σ weight × signal)`, làm tròn 2 chữ số. Risk bands:

| Score | Level |
|---|---|
| 0–24.99 | `LOW` |
| 25–49.99 | `MEDIUM` |
| 50–74.99 | `HIGH` |
| 75–100 | `CRITICAL` |

Report signal chỉ lấy contribution `VERIFIED`:

```text
raw          = Σ severity × decay(age)
reportSignal = 100 × raw / (raw + 100)
```

Time-decay: 0–30 ngày `1.0`, 31–90 ngày `0.8`, 91–180 ngày `0.6`, trên 180 ngày `0.4`.

Weights mặc định:

| Target type | report | rule | AI | campaign |
|---|---:|---:|---:|---:|
| `URL` / `OTHER` | 35% | 25% | 25% | 15% |
| `PHONE` / `BANK_ACCOUNT` / `EMAIL` | 55% | 20% | 0% | 25% |

Confidence:

```text
verifiedEvidence = min(1, verifiedReportCount / 5)
reportVolume     = min(1, totalReportCount / 10)
signalCoverage   = số component signal > 0 / 4
confidence       = 0.50×verifiedEvidence + 0.30×reportVolume + 0.20×signalCoverage
```

Chi tiết và compatibility policy: `../Contracts/docs/reputation-service.md`.

## Events

Consume:

| Topic | Event types |
|---|---|
| `baotin.report.events.v1` | `report.verified`, `report.rejected`, `report.revoked` |
| `baotin.threat.events.v1` | `threat.updated` |
| `baotin.campaign.events.v1` | `campaign.updated` |

Consumer group `reputation-service-v1`; duplicate `eventId` bị bỏ qua bởi `reputation.inbox_events`; event cũ hơn `last_event_at` được ack nhưng không đổi state; poison record retry 3 lần rồi ghi `<topic>.DLT`.

Produce `reputation.score.changed` lên `baotin.reputation.events.v1` qua Outbox trong cùng transaction với score/history. Outbox publisher dùng lease, retry có backoff và reclaim lease hết hạn.

## Cấu hình

Các biến chính (mặc định trong `src/main/resources/application.properties`):

| Biến | Ý nghĩa |
|---|---|
| `SERVER_PORT` | mặc định `8184` |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | datasource runtime |
| `DB_MIGRATION_ENABLED=true` | bật Flyway khi provision |
| `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | identity chạy migration |
| `AUTH_JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE` | xác minh access token |
| `REPUTATION_EVENTS_ENABLED=true` | bật Kafka consumer/outbox publisher |
| `KAFKA_BOOTSTRAP_SERVERS` | broker khi eventing bật |
| `REPUTATION_CACHE_ENABLED=true` | bật Redis read cache |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis khi cache bật |
| `REPUTATION_CACHE_TTL_SECONDS` | TTL cache, mặc định `300` |

Kafka hỗ trợ `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT`, `SASL_SSL`; với SASL cần `KAFKA_SASL_MECHANISM` và `KAFKA_SASL_JAAS_CONFIG`.

## Chạy local

Yêu cầu JDK 21 và PostgreSQL đã có schema `reputation` + role `baotin_reputation` (xem `../Infra/deploy/init.sql`). Ví dụ với PostgreSQL local:

```powershell
$env:DB_URL='jdbc:postgresql://127.0.0.1:15432/baotin?currentSchema=reputation'
$env:DB_USER='baotin_reputation'
$env:DB_PASSWORD='<local-password>'
$env:DB_MIGRATION_ENABLED='true'
$env:DB_MIGRATION_USER='baotin_reputation'
$env:DB_MIGRATION_PASSWORD='<local-password>'
$env:SERVER_PORT='8184'
.\gradlew.bat bootRun
```

```bash
curl http://localhost:8184/actuator/health
curl http://localhost:8184/actuator/health/liveness
curl http://localhost:8184/actuator/health/readiness
```

Kafka/Redis là optional. Khi tắt eventing, outbox vẫn được ghi nhưng chưa publish; khi bật cache, Redis chỉ là cache và lỗi Redis không làm fail request.

## Test

```powershell
.\gradlew.bat test --max-workers=1
```

Test dùng Testcontainers PostgreSQL 17. Không cần Kafka/Redis cho test hiện tại; Kafka consumer/DLT và Redis cache là infrastructure adapter mỏng, đã được cấu hình để bật bằng biến môi trường.

## Jenkins / Docker

Pipeline hiện vẫn là verify lane (JDK21, test, bootJar, security/SBOM). Docker image chạy non-root, healthcheck `/actuator/health`. Deployment/orchestration thuộc `../Infra`; service chỉ lắng nghe cổng nội bộ và không được expose `/internal/**` qua Gateway.

## Giới hạn còn lại

- Producer Report/Threat đã có source; full-stack event delivery và Campaign integration cần acceptance riêng.
- Chưa có Micrometer business metrics/alerts và Kafka/Redis integration test.
- Chưa có endpoint lịch sử score; history đang được lưu để audit và có thể bổ sung API sau.


## Release dependency gate — 2026-10-28

Jenkins FullStack28/Auth41 identified new HIGH advisories in transitive Snappy and Guava. Runtime constraints select snappy-java1.1.10.9. This dependency-only patch preserves APIs/auth/events; scanner policy remains enabled. Repo wrapper tests and packaged-JAR scans must pass before promotion.

## Owning Search snapshot exporter — 2026-11-12 (candidate)

The manual `com.baotin.reputation.operator.SearchSnapshotExport` CLI accepts a NEW
absolute JSONL output path and the Search rebuild `startedAt` ISO-8601 timestamp.
Set SOURCE_EXPORT_URL, SOURCE_EXPORT_USER and SOURCE_EXPORT_PASSWORD_FILE for
this process only. It verifies its actual owning DB user, uses a repeatable-read
read-only transaction, never reads another business schema, and writes a hashed
complete owner manifest next to the data. No runtime HTTP endpoint or scheduler
is added. Export files/passwords stay outside Git.

Launch with the approved JDK21 JAR and PropertiesLauncher, for example:
`java -Dloader.main=com.baotin.reputation.operator.SearchSnapshotExport -cp build/libs/Reputation-Service-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher L:/external/reputation.jsonl STARTED_AT_ISO`

Role baotin_reputation exports actual final_score/risk_level/calculated_at only for calculated targets. Uncalculated rows remain unknown; no fabricated old score/reason/domain-change event is produced.

Full owning-role/privacy/negative privilege tests must pass before promotion.
