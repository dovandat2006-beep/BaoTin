# Full-stack Jenkins orchestration

Ten backend repositories each own a build-only `Jenkinsfile`: trusted main checkout,
JDK21 `bootJar` + secret/dependency gates + JAR SBOM, server-side SonarQube quality gate,
then primary JAR artifact archival. BUILD_DOCKER_IMAGE=false by default; when enabled, pinned-base Docker builds/image scans publish alternative immutable digests under ghcr.io/vietluccuu.
Tests (unit + integration) do not run in this release lane: they run on the developer
machine and in the GitHub verify workflow on `pull_request`. There is therefore no
skip-tests parameter, and no `-x test`, to disable — nothing was made optional. No
SSH/production auto-deploy remains in these build jobs.

```powershell
.\ci\Sync-Pipelines.ps1 -Workspace M:\BaoTin -DryRun
.\ci\Test-Pipelines.ps1 -Workspace M:\BaoTin
```

Run sync only in the multi-repository maintainer workspace. It writes only the named ten
backend pipelines/workflows/scanner scripts and never touches AI or Frontend. Frontend
web build and deployment orchestration remain separate jobs. GitHub Actions verify PRs
and manual runs with read-only credentials; Jenkins is the release/deployment authority.

Configure SCM URL, `*/main`, `Jenkinsfile`, folder credentialsId `baotin-github-scm` before loading a
pipeline. Restrict job/revision configuration to maintainers; an in-pipeline main check
is not a substitute for protecting its source. Registry credential `ghcr-registry` needs
a real GitHub username and package-push permission. Secrets are supplied externally,
not committed or embedded in build context. Never mount runtime/operator secrets in build jobs.

Primary full-stack JAR/web promotion uses Infra/Jenkinsfile and direct/Deploy-Jars.ps1. The alternative Docker Auth/Gateway promotion uses team/Jenkinsfile and the isolated Windows deployment
node after actual source/build/scanner/digest approval. Other services remain startup
scaffolds; CI readiness does not mean their business TODOs have been implemented.
Docker images are also usable for manual deployment, independently of Jenkins automation.

## SonarQube quality gate

Each of the ten backend pipelines runs `ci/sonar-quality-gate.sh` (generated from
`templates/sonar-quality-gate.sh`) and each `build.gradle` applies the `jacoco` and
`org.sonarqube` plugins. Because the release lane does not run tests, the analysis
carries no coverage and the backend and frontend release quality gates deliberately hold **no** `coverage`
condition — a coverage condition on a metric with no measure is silently always OK,
which is the failure mode this setup avoids. `Sonar way`, the built-in gate, stays the
server default but is unused by BaoTin projects: all six of its conditions are `new_*`
and no New Code set is ever built (see `../sonarqube/README.md`). The stage only decodes
the server token through
`withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')])`; the
script receives the token on stdin, never in argv, and never enables `set -x`.

The gate polls `api/ce/task` then `api/qualitygates/project_status` instead of using
`sonar.qualitygate.wait` so the exit code stays explicit and fail-closed:
`2` host/tooling/config, `3` report/compute-engine failure,
`4` compute-engine timeout or gate query failure, `5` gate not `OK`. `SONAR_HOST_URL`
must be the internal `http://sonarqube:9000`; any other host exits `2`.

Frontend runs its pinned source scanner before npm typecheck/Expo export. It never
requires or imports stale LCOV in release. Jest/E2E remain in frontend verify CI;
backend unit/integration tests remain in the backend verify workflow. All11 release
projects use explicit overall-code metrics, not a coverage claim. See `../sonarqube/README.md`.

## Shared Trivy cache serialization

JAR and optional image gates share `baotin-security-trivy-cache` on the build daemon.
They acquire the unstarted, network-disabled/read-only container name
`baotin-security-trivy-cache-lock` atomically before any Trivy operation. This works
across workspaces and Docker clients; scanners never receive a Docker socket. The
owning job retains the lock through dependency/image/SBOM work and removes only its
recorded container IDs. Concurrent jobs wait up to600 seconds (build-only environment
`TRIVY_CACHE_LOCK_WAIT_SECONDS`, integer1..1800), then fail instead of bypassing scans.
INT/TERM cleanup preserves the signal status. Scanner/lock cleanup failure fails the
gate and keeps a held lock rather than allowing overlapping cache writers.

An orphaned holder is not automatically reclaimed. An operator must confirm its owning
build and scanner processes are terminal before removing that specific lock container;
never delete the cache volume or prune other applications to release it. A foreign or
uninspectable holder fails closed. One missing-holder retry handles release between
name-conflict and inspection; persistent creation/daemon failures still fail.

Lightweight regression (Python stdlib + Bash; synthetic Docker transport, no real scanner
or download):

```powershell
python ci/Test-TrivyCacheLock.py --temp-root PATH_TO_TEMP_DIRECTORY
.\ci\Test-Pipelines.ps1 -Workspace PATH_TO_BAOTIN_WORKSPACE
```

On Linux pass `--bash /bin/bash`. Nine cases cover concurrent JAR/image gates, holder
release race, creation failure, foreign/stale holders, invalid wait budget, scanner
failure, cleanup failure and TERM cleanup. Pipeline checks require all20 generated gate
files to match their two templates. This fixture does not certify a live Trivy database
scan; real Jenkins scan/SBOM execution remains a separate gate.

FullStack stores immutable child build numbers through dynamic env property assignment,
not env[key] (Groovy putAt on Object is rejected by the Jenkins sandbox). Pipeline checks
reject bracket assignment and sandbox/approval bypasses; do not approve a broad putAt
signature to make orchestration pass. Actual child builds and parent orchestration are
verified separately; a failed parent does not mean its successful child artifacts failed.

The direct deployment stage checks DEPLOY_RELEASE with beforeAgent true. Build-only runs
therefore finish without scheduling a missing deploy node; deploy-enabled runs still need
the restricted node and maintainer approval. No node/secret/deployment is invented to
make a build-only pipeline finish.

Current live checkpoint: FullStack#6 terminal SUCCESS and actual ten-JAR + Expo web
promotion. Eleven child jobs succeeded: Auth#12 (62 tests), Gateway#8 (23), eight
scaffolds#6 (8 total), Frontend#6 (25);118 total tests. Post-job runtime ownership and
all11 readiness checks passed. JAR security/SBOM gates passed. The Docker image-build
flag was false; alternative image acceptance is separate under ../alternative.
Twelve jobs pin explicit approved SHAs before Jenkinsfile retrieval; GitHub main remains
unprotected. Later doc-only commits are not automatically promoted. Source pin updates
remain an explicit maintainer operation; no new automatic main/replay execution is enabled.
## Verified verdicts and deployment targets

`sonar-validate-gate.pl` requires correct project, every mandatory metric, numeric actuals,
non-ignored conditions and thresholds no weaker than reviewed policy. Empty OK/invalid JSON/
missing data fail. FullStack builds sequentially; DEPLOY_TARGET chooses jar/docker/vps/k8s/k3s
after maintainer approval. VPS uses strict SSH host keys and remains unconfigured.
Tests: python ci/Test-SonarPolicy.py; pwsh vps/Test-SSHPreflight.ps1.
Cluster targets retain the same default/approval/deploy-node guards and require
explicit external context/namespace/UID, immutable image provenance, Secrets and
model PVC. See [K8s/K3s runbook](../kubernetes/README.vi.md); regression:
`python -B -m unittest discover -s kubernetes/tests`.

## Gate-before-packaging contract

Sonar runs clean main-class compilation (Java prerequisite), never bootJar/test. The next
stage packages bootJar without clean, preserving analysis evidence. Frontend gate runs
before Expo export. Failed gates stop packaging/publication. Tests remain separate, not
claimed as passed by a successful release build. Backend release-evidence.json binds the
JAR hash/source revision and explicit external_verify policy. Older JUnit-proven releases
remain compatible; failed/malformed release proof never falls back silently.

Regression: python ci/Test-ReleaseOrdering.py; Test-SonarPolicy.py; Test-SonarShell.py.
