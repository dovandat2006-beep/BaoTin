# Manual checkpointed Search rebuild / backfill

This is a real operator CLI, not an anonymous/admin HTTP endpoint. It neither
reads another service schema nor resets a live Kafka group. The application
continues serving the current projection while owner snapshots are staged.

## Preconditions

1. Owning migration through V3 applied using SearchMigration; runtime gets only
   its own data/Inbox and append-only journal rights (see db/provision.sql).
2. Operator uses baotin_search_migrator with an external protected regular password
   file. Never mount that file/user in the runtime. Set SEARCH_REBUILD_URL,
   SEARCH_REBUILD_USER, SEARCH_REBUILD_PASSWORD_FILE only for this command.
3. Each owning source provides its complete public snapshot AFTER the begin
   barrier. Threat/Reputation/Campaign data must be exported by their own roles,
   not by Search querying foreign tables. Empty sources require explicit owner
   completeness, never fabricated records. Owner exporters are implemented in Threat/Reputation/Campaign; actual local
   owning-source backfill acceptance is recorded separately from public deployment.

## Commands

Use the actual approved JAR and JDK21. The common launch prefix is:

```powershell
java -Dloader.main=com.baotin.search.SearchRebuild -cp build/libs/Search-Service-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher begin
# Save returned run UUID and startedAt, then obtain complete owner snapshots.
# Every following command uses the same java/-Dloader.main/-cp/launcher prefix:
# status RUN_UUID
# compose RUN_UUID THREAT_MANIFEST REPUTATION_MANIFEST CAMPAIGN_MANIFEST OUTPUT_MANIFEST
# resume RUN_UUID C:/external/rebuild-manifest.json 1000
# cutover RUN_UUID
# cancel RUN_UUID
# purge RUN_UUID
```

`resume` processes at most the supplied batch (1..10000). Re-run with the SAME
manifest/data SHA to continue. A record mutation, shadow Inbox and checkpoint
commit atomically. Invalid records stop at the last committed line; changing the
input of an attached run is rejected. Cancel and start a new run for corrected
input. READY requires exact EOF/count/hash and all three owner completeness/time
declarations. Future (>5min), stale (pre-begin) or incomplete sources are rejected.

Manifest schema: ../Contracts/events/search/rebuild-manifest-v1.schema.json.
It binds schemaVersion1, runId, an absolute regular dataFile, SHA256, record count
and unique Threat/Reputation/Campaign source snapshotAt/complete declarations.
Data is bounded UTF8 JSONL: owner-snapshot-v1.schema.json records (not fabricated
Kafka changes), or validated topic/event records. Maximum1million rows/512MiB,
64KiB per line. Lookup hashes and masked fields follow search-publication-v1.md.
Neither status nor errors print passwords or raw record payloads.

## Cutover correctness

- The V3 row journal trigger shares the live write transaction and captures the
  original V1 writer too. begin drains document writers before recording its
  committed journal barrier; no lower uncommitted sequence can be missed.
- cutover locks Inbox -> live documents -> journal, merges committed post-barrier
  row deltas into staging by independent publication/risk clocks, then replaces
  live contents and unions seed Inbox IDs in ONE transaction. It uses DELETE +
  INSERT (not TRUNCATE), so pre-cutover repeatable-read snapshots retain their
  old coherent view. No offsets are rewound and no application stop is required.
- PRIVATE/REMOVED tombstones, newer scores and new targets arriving during
  staging survive. Any failure after replacement rolls the whole cutover back.
- Completed/cancelled runs cannot be applied again. purge removes only their
  own shadow rows/Inbox, retaining run/journal audit. No scheduled cleanup exists.

## Proven tests / remaining acceptance

Real PostgreSQL tests cover resume/duplicate/count/hash/time/completeness checks,
concurrent legacy writers, live deltas, independent clocks, privacy withdrawal,
rollback after replacement, MVCC old readers and runtime-role denial. A subprocess
executes the packaged CLI with actual owning credentials and rejects runtime
credentials without echoing the password. This proves the implementation, not a
production rebuild/backfill or a substitute for verified owner exports.

## Owner composition and actual acceptance

Each owner CLI returns/writes owner-export-v1.schema.json: source, snapshotAt,
complete, records, absolute dataFile and SHA256. `compose` validates exactly one
Threat/Reputation/Campaign export, ownership per record, hashes/counts and time,
then creates NEW combined JSONL/run-bound manifest. No foreign DB connection is
made by Search. Use PowerShell `ConvertFrom-Json -DateKind String` when forwarding
ISO timestamps to Java (do not pass culture-formatted DateTime strings).

Local actual backfill run193fb6dc-259a-4729-89d5-1d2070931426 completed34 records
(Threat17, Reputation17, Campaign0) into17 public URL rows with17 actual scores.
The empty Campaign namespace was actually verified using its metadata-only role;
foreign business privileges were denied. Search V3 migration and atomic cutover
completed; this is NOT a public HTTP/deployment acceptance. Detailed private local
file/hash evidence: M:/BaoTin/.changes/search-full-20261112/actual-backfill-result.json.
