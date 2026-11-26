# Search Service

## Full read-model extension candidate — 2026-11-12 (unpublished)

The current worktree extends public Search to URL, PHONE, BANK_ACCOUNT, EMAIL,
CAMPAIGN and OTHER. V2 persists reference_id/status/metadata and masked exact-lookup
keys. Publication is explicit owner PUBLIC; PRIVATE/REMOVED removes index content
and lookup keys. No raw SMS/contact/account data is indexed or returned. Phone,
account and email lookups use the versioned normalization/hash contract; this is
an exact-match index, not encryption or an anonymization claim.

The listener accepts all five master-plan event names on dedicated owner Search
streams: threat.created/updated, campaign.created/updated, plus the existing
reputation.score.changed. It retains the original URL stream during rolling
upgrade. Existing risk streams and their consumers are unchanged. Threat now
writes its new snapshots into the SAME existing target/scan transaction + Outbox,
including report-resolved targets; message templates publish fingerprints only.
Campaign business creation remains in Campaign-Service; Search consumes its
contract without inventing production campaigns. Other target producers must
provide approved masked snapshots before those types have searchable data.

See ../Contracts/docs/search-publication-v1.md. Run the additive owning V2 migration
and provision baotin.threat.search.events.v1 / baotin.campaign.search.events.v1
before this candidate can be deployed. The V1 reference trigger preserves original
URL writers during rollback. Query limit is now254 (supports full DNS hosts).
Managed rebuild/checkpoint and atomic cutover are implemented and tested; see
REBUILD.md. Owning exporters/composition and local actual backfill are now verified;
operational/public acceptance and a full-feature signed frontend release remain required.

## Historical URL increment — 2026-11-11

`GET /api/v1/search?q=example&type=URL&riskLevel=HIGH&page=0&size=20`
is anonymous through API-Gateway. The service independently permits only this GET
and health probes; writes, unknown routes and actuator details are denied. It has
no authenticated/private business endpoint, so client roles/JWT claims cannot grant
additional access. Public documents contain DNS host only, never a URL path/query,
userinfo, SMS, report text or arbitrary metadata. Unknown risk remains null, not LOW.

PostgreSQL `search` schema only; FTS/simple + pg_trgm GIN, stable ranking and bounded
pagination. Prepared queries and repeatable-read count/page. Java normalization
folds Vietnamese accents. Inbox and projection write share one transaction; Kafka
record offset commits only after success. Independent target/risk clocks reject
older updates and permit reputation-before-target. Poison events retry instead of
silent acknowledgement. Retention/lag and poison intervention remain operational work.

Contracts: `../Contracts/openapi/search-service.json`,
`../Contracts/events/search/search-target-events-v1.schema.json`,
`../Contracts/docs/search-v1.md`. Threat publishes the new host-only event into its
existing transactional Outbox on `baotin.search.targets.v1`; existing events stay
unchanged. Search also consumes `baotin.reputation.events.v1` without calculating
risk itself. PHONE/BANK_ACCOUNT/EMAIL/campaign and historical export/backfill are
not included in this URL increment.

## Provision and run

JDK21, private PostgreSQL17 with pg_trgm in public and private Kafka. Operator
creates `search` owned by a separate `baotin_search_migrator`, runtime `baotin_search`
with USAGE only and no cross-schema grants. See `src/main/resources/db/provision.sql`;
apply extension/schema before migration and table grants after migration. Runtime
does not get schema CREATE, DDL, DELETE, Flyway credentials or history access.

Run owning migration explicitly, before promotion (never startup migration):

```powershell
# Set SEARCH_MIGRATION_URL, SEARCH_MIGRATION_USER=baotin_search_migrator and
# SEARCH_MIGRATION_PASSWORD_FILE to an external protected file; never print it.
java -Dloader.main=com.baotin.search.SearchMigration -cp build/libs/Search-Service-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
# Then unset migration variables and apply minimal runtime grants.
.\gradlew.bat test bootJar --no-daemon --max-workers=1
.\gradlew.bat bootRun
```

Tests require Docker and start isolated PostgreSQL; they never migrate the running
application database. Runtime uses SEARCH_DB_URL/USER/PASSWORD or Spring configtree
with `spring.datasource.*`; Flyway is disabled by default. Kafka:
KAFKA_BOOTSTRAP_SERVERS, SEARCH_CONSUMER_GROUP. Provision both topics before boot.
Readiness requires DB/schema and assigned consumer partitions. Liveness is local
only. Expose no new host port; use private network/Gateway.

## Replay / rollback

For retained-topic replay into a separate isolated projection use a fresh consumer
group (earliest). Never truncate live tables or read other service schemas.
Replay cannot recover events already expired or targets predating the producer.
Rollback restores the previous immutable JAR/web bundle; migration is additive and
new Kafka topic retained, not deleted. Old Threat consumers ignore this separate
topic. Keep Search state/Inbox for a forward retry. A source rollback was tested on
an independent copy; it is not evidence of a production deployment rollback.

## CI/CD

Repo GitHub verify uses JDK21 wrapper test + bootJar; PostgreSQL tests need Docker.
Jenkins release enforces Sonar/security/SBOM and immutable artifacts, but its current
release lane does not run tests: external verification of the same revision is a
separate prerequisite, not coverage evidence. Dockerfile uses pinned bases,
non-root1001 and health probes. Deployment needs the external Search configtree,
DB grants/migration and Kafka topic before readiness. See Infra/direct and
Infra/kubernetes; local tests alone do not claim live deployment.

Operator rebuild: [REBUILD.md](REBUILD.md). Migration V3 journals live/legacy writes without foreign-schema access; no public write route or Kafka offset reset.

## Masked message lookup — 2026-11-12 (candidate, not deployed)

Message history retains readable masked wording, rather than hiding the whole
message. Detected contacts, numeric identifiers, labelled secrets/name/address
fields and URL paths/query values become `***`; automatic pattern masking is
not a guarantee of detecting every possible personal detail. High/critical text
scans publish only masked wording through the owning Threat Outbox. Search
validates the same masking policy, returns optional full `content`, and removes
it on PRIVATE/REMOVED. Existing fingerprint-only documents remain compatible.
Old hidden snapshots without retained input cannot be reconstructed.

Dataset TEXT/URL seeds use the owning Threat operator, SHA-bound JSONL and
per-row transactions/deduplication. SUSPICIOUS is a content warning only; UNKNOWN
is not a safe verdict, and no seed changes report VERIFIED or fabricates scores.
Dataset source stays internal, not a required UI field. Threat/Search additive
V4 migrations and runtime grants must precede promotion. Roll back code without
dropping these populated additive tables. Deployment/import acceptance remains
separate from passing source tests.

## Dataset binary labels — 2026-11-12 correction
SMS0=legitimate (BENIGN),1=spam/fraud (SUSPICIOUS); URL benign=BENIGN,
phishing=SUSPICIOUS. UNKNOWN is reserved for genuinely unlabelled input.
Lookup displays a dataset label independently of aggregate risk scores and report
verification. A normalized message/domain may combine records: a dangerous label
wins over a benign one; absence of a score does not discard an existing label.
Additive ThreatV6 restores previously seeded normal labels; expand Search enum
support before publishing BENIGN events. No report VERIFIED or score is inferred.
