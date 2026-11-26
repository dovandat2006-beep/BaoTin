# Docker alternative: all ten backend services + Expo Web

## Jenkins full-stack Docker promotion

FullStack `DEPLOY_TARGET=docker` downloads only immutable SUCCESS child builds,
validates their archived Sonar metrics/tests/JAR security/SBOMs and source revisions,
packages the exact CI artifacts, scans all11 images, checks runtime DB compatibility
and scoped secrets, then waits for all readiness checks. Failed candidate restores
the retained previous config/images; no migration or volume deletion occurs.
Set external `DOCKER_ALT_ENV_FILE` in the node's jar.env before selecting this target.
The default remains `jar`; only `DEPLOY_RELEASE=true` schedules the restricted node.

## Accepted deployment — 2026-10-08 (current)

FullStack#8 terminal SUCCESS promoted ten JARs + Expo web. Auth#14 passes73 tests,
Gateway#10 passes24, eight scaffolds add8 and Frontend#8 adds25:130 pass/no failures/skips.
Actual DB migration is schema10/readerFloor2/BK1, separate migrator/runtime credentials;
persistent keys are unchanged. Primary all11 owned non-elevated processes are readiness UP.
Docker alternative all11 images/health/scans/SBOMs and live JWT/TOTP/EMAIL/refresh/
fault-rollback pass; alternative is manually stopped, preserving images/volumes/dependencies.
Google/Facebook real Web consent passed; native/link/cancel live proofs are not inferred.
Startup is manual; domain/tunnel and business functionality of eight scaffolds remain later.
Retain reader2 for rollback, never reader1/down-migration. See Auth's
`IMPLEMENTATION_STATUS.md`, `EMAIL_MFA_RUNBOOK.md` and Infra's `direct/MANUAL_START.md`.
All older checkpoints below are historical; doc-only pushes do not advance Jenkins pins.


Primary deployment remains Jenkins -> Windows JAR + static web. This alternative runs
the same tested artifacts in Linux containers; it does not recreate Jenkins/dind.
Domain/DNS/tunnel configuration is deferred. Only frontend publishes loopback8095;
`/api/**` reaches Gateway internally, other paths serve the Expo export. All eight
business services remain startup scaffolds and their public endpoints remain disabled.

## Same database and durable keys

Start/provision `../direct/dependencies` first. Alternative containers attach to that
project's existing private network and PostgreSQL/Redis aliases; they do not create
a second database instance or reset existing data. Use the same signing/encryption
files and key ID as primary so switching runtime does not invalidate stored ciphertext.
Don't enable both deployment lanes automatically against different keys/databases.

`prepare-secrets` copies only auth-config/auth-keys/gateway-config regular files into a
named secret volume. Linux UID1001/file0400/directory0700 are applied there, independent
of Windows ACL/Linux host UID. Each app mounts only its own volume subpaths read-only;
Gateway/scaffolds never receive Auth's private key. Bootstrap/migrator/operator credentials
are not copied. A differing existing secret refuses replacement; rotation is separate.
Docker administrators remain trusted operators. No secrets enter an image/build context.

## Build and configure

Requires Docker Engine26+/Compose2.40+, PowerShell7.4+ for these operator runners and
clean per-repo source with complete passing tests/JARs and the verified Expo export.
`Build-Images.ps1` packages artifacts, not an untested source build; run real CI/security
gates first. Existing service Dockerfiles/registry pipelines remain another build route.
These locally built IDs need `docker save`/`docker load` or registry digest publication
before use on another engine; a local ID is not magically available on a new machine.
Artifact staging uses .NET `GetTempPath()` rather than requiring the Windows `TEMP`
variable. Cleanup checks the captured temporary root and removes only its GUID stage.
`Test-ImageTemp.ps1` tests unset `TEMP`, eleven-image packaging and preservation of an
unrelated file using synthetic Git/Docker transport; it does not certify application behavior.

```powershell
# From Infra; outputs live outside Git.
.\alternative\Build-Images.ps1 -OutputFile C:\Users\USER\.baotin\alt-images.json
.\alternative\Initialize-Config.ps1 -ImageManifest C:\Users\USER\.baotin\alt-images.json `
  -JarEnvFile C:\Users\USER\.baotin\jar.env -OutputFile C:\Users\USER\.baotin\alternative.env
.\alternative\Test-ImageSecurity.ps1 -Manifest C:\Users\USER\.baotin\alt-images.json `
  -OutputDirectory C:\Users\USER\.baotin\alternative-security
docker compose --project-name baotin-alt --env-file C:\Users\USER\.baotin\alternative.env -f alternative/docker-compose.yml --profile operations run --rm prepare-secrets
docker compose --project-name baotin-alt --env-file C:\Users\USER\.baotin\alternative.env -f alternative/docker-compose.yml up -d --pull never --wait --wait-timeout 180
docker compose --project-name baotin-alt --env-file C:\Users\USER\.baotin\alternative.env -f alternative/docker-compose.yml ps
```

Use current Docker context (or explicit `-DockerContext`) rather than hardcoding Desktop
in these runners. On another OS, replace the external paths and prepare/provision
dependencies there before generating network addresses; do not copy this host's IPs.
The existing direct-dependency provisioning runner currently targets Windows Desktop;
Linux operators may run the same reviewed SQL and container migration entry point with
separate files. The Compose/images are Linux-container runtime artifacts, not native OS binaries.

`Test-ImageSecurity.ps1` scans the manifest's exact local images, including OS packages
and packaged JARs for backend services, then generates a CycloneDX SBOM for each image.
It acquires the same atomic Docker cache lock as CI, exports one archive at a time,
never mounts a Docker socket and fails on HIGH/CRITICAL or missing scan coverage.
Do not use its `-Baseline` regression mode to approve a release. Source/JAR secret and
dependency gates remain required separately; an image scan is not authorization proof.
Normal scans require `schemaVersion: 1`, all eleven exact image keys emitted by
`Build-Images.ps1`, and eleven distinct immutable local `sha256:` IDs. Empty, partial,
extra/renamed keys and mutable references fail before any Docker operation; a partial
scan cannot print a full release PASS. Input regression (synthetic Docker transport,
no image scans or running-app changes):

```powershell
.\alternative\Test-SecurityManifest.ps1
```

Its fifteen cases cover malformed/incomplete references, duplicate IDs, unsafe names
and complete-input acceptance. A real eleven-image scan remains required afterward.
The web runtime uses a digest-pinned patched Nginx 1.30 base plus Alpine package updates;
the prior 1.28 base still had ten Nginx findings after `apk upgrade` alone.

## Release and rollback

Image IDs in alternative.env are immutable. Retain a previous reviewed image manifest,
compatible reader/schema/cipher metadata, data backups and key ring before replacement.
Rollback restores the previous application image IDs and checks all health endpoints;
it never down-migrates PostgreSQL or removes data volumes. `down` here disconnects only
alternative apps, not dependencies; omit `-v` to retain the secret volume as well.
After initial startup verify actual signup/login/USER JWT, forged ADMIN denial, refresh
rotation/logout and persistent MFA/session behavior before declaring a release accepted.
Connected/healthy containers alone are not full completion proof.

```powershell
.\alternative\Test-Deployment.ps1 -EnvFile EXTERNAL_ALTERNATIVE_ENV `
  -ImageManifest EXTERNAL_IMAGE_MANIFEST -OutputFile EXTERNAL_DEPLOYMENT_REPORT
.\alternative\Test-LiveRuntime.ps1 -EnvFile EXTERNAL_ALTERNATIVE_ENV `
  -OutputFile EXTERNAL_RUNTIME_REPORT -TestRollback
```

The live regression uses the configured sender-self mailbox only if it has no existing
account, verifies the newly generated account's encrypted outbox proof, then disables/
anonymizes that fixture and revokes its sessions while retaining audit. This is controlled
outbox proof, not inbox consumption. It intentionally replaces only alternative Gateway
with invalid CORS, checks correlated503, restores the reviewed config and verifies the
same session still works. It does not restart the primary Windows release or down-migrate.

## Observed acceptance — 2026-10-07

Fresh ten-backend + web Linux containers passed health, immutable retained image/JAR
identity, non-root/read-only, scoped secret modes and loopback-only frontend publication.
All eleven images scanned with zero HIGH/CRITICAL and eleven SBOMs; report layer IDs
matched the actual image layers. Real HTTP JWT/MFA/recovery/refresh reuse/forged ADMIN
checks and fault rollback passed. This lane was stopped afterward without deleting
volumes; Jenkins FullStack#6 JAR + web remains the primary deployment. Domain/tunnel
configuration is still deferred, and business endpoints of eight scaffolds stay closed.

### Host reboot recovery checkpoint

The host rebooted after the successful release. Native Windows JAR/web and remoting
processes do not survive a reboot; automatic startup has not been configured yet.
The existing node was reconnected non-elevated. The same successful FullStack#6 child
artifacts were restored through the reviewed deploy runner after proving old ownership
records were pre-boot, no owned process survived and all runtime ports were free.
Only stale metadata was retired; the unrelated process with a reused PID was preserved.
Fresh post-recovery checks pass all11 owned non-elevated processes/readiness endpoints
and actual deployed HTTP JWT/MFA/recovery/refresh-reuse/forged ADMIN tests. Existing
Docker controller/dind/CLIProxyAPI IDs/images remain unchanged; their start times reflect
the observed host/Docker restart, not a project-triggered restart. Autostart remains a
separate operator choice, not a claimed completed gate.

Search projection now requires SEARCH_CONFIG_DIR pointing to a Docker-reachable
external configtree with spring.datasource.* and spring.kafka.bootstrap-servers.
Mount is read-only; runtime migration remains disabled. Provision the owning Search
schema/pg_trgm/grants and new topic before starting the new immutable image.
