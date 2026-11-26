#!/usr/bin/env sh
# SonarQube quality gate for the Expo web client.
#
# Runs on the Jenkins controller (JDK 21) with a pinned SonarScanner CLI and
# talks to the internal SonarQube server over the `jenkins` Docker network.
# The build daemon (dind) cannot reach SonarQube, so no scanner container is
# used. Release analysis runs before Expo export; tests/coverage belong to verify CI.
#
# Contract:
#   SONAR_HOST_URL  internal SonarQube base URL (from Jenkins global env)
#   SONAR_TOKEN     credential injected by withCredentials(string)
#
# Fail closed: a missing/failed scanner, an unprocessed
# compute-engine task, a transport error or a non-OK gate all fail the build
# with a distinct code. The token never appears in argv or in the log.
set -eu
set +x

: "${SONAR_HOST_URL:?SONAR_HOST_URL is required}"
: "${SONAR_TOKEN:?SONAR_TOKEN is required}"
printf '%s\n' "$SONAR_TOKEN" | grep -Eq '^[A-Za-z0-9_-]{20,256}$' || { echo 'SONAR_GATE ERROR invalid_token_format' >&2; exit 2; }

# The analysis endpoint must be the internal CI service, never a public edge.
case "$SONAR_HOST_URL" in
    http://sonarqube:9000|http://sonarqube) ;;
    *) echo 'SONAR_GATE ERROR unexpected_host_url' >&2; exit 2 ;;
esac

# SonarScanner CLI 6.2.1.4610 — checksum published by SonarSource at
# .../sonar-scanner-cli-<version>.zip.sha256 and re-verified on every run.
SCANNER_VERSION='6.2.1.4610'
SCANNER_SHA256='d45e09eecb2fe867ce7548be59d54317192c79944ef7e54c691423c832a8208f'
SCANNER_ROOT="${SONAR_SCANNER_ROOT:-/var/jenkins_home/tools}"
SCANNER_HOME="$SCANNER_ROOT/sonar-scanner-$SCANNER_VERSION"
SCANNER_BASE='https://binaries.sonarsource.com/Distribution/sonar-scanner-cli'
PROJECT_KEY='baotin_frontend'

for tool in curl sha256sum unzip java mktemp perl tar; do
    command -v "$tool" >/dev/null 2>&1 || { echo "SONAR_GATE ERROR ${tool}_missing" >&2; exit 2; }
done

# The legacy server plugin does not bootstrap Node and otherwise reports a green
# analysis after silently skipping TypeScript. Fetch an immutable runtime into
# Jenkins' tools volume, verify it, and require a nonempty completed TS sensor.
NODE_VERSION='24.16.0'
NODE_SHA256='2faf6a387e9b62b888e21c54f01249fb27537ffecf1842f29f4c919d0a59a0ff'
NODE_HOME="$SCANNER_ROOT/node-v$NODE_VERSION-linux-x64"
NODE_ARCHIVE="$SCANNER_ROOT/node-v$NODE_VERSION-linux-x64.tar.gz"
mkdir -p "$SCANNER_ROOT"
if [ ! -f "$NODE_ARCHIVE" ]; then
    curl -fsSL --connect-timeout 5 --max-time 120 -o "$NODE_ARCHIVE.part" "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" || exit 4
    mv "$NODE_ARCHIVE.part" "$NODE_ARCHIVE"
fi
printf '%s  %s\n' "$NODE_SHA256" "$NODE_ARCHIVE" | sha256sum -c - >/dev/null || { echo 'SONAR_GATE ERROR node_checksum_mismatch' >&2; exit 2; }
tar -xzf "$NODE_ARCHIVE" -C "$SCANNER_ROOT"
NODE_EXE="$NODE_HOME/bin/node"
[ "$("$NODE_EXE" --version)" = "v$NODE_VERSION" ] || { echo 'SONAR_GATE ERROR node_runtime_unavailable' >&2; exit 2; }
export PATH="$NODE_HOME/bin:$PATH"
# Analysis needs Expo's referenced tsconfig/types. This only installs locked
# dependencies; tests and Expo packaging still occur in their existing lanes.
npm ci --ignore-scripts --no-audit --no-fund

# --- scanner: fetched once, checksum verified on every run ------------------
zip="$SCANNER_ROOT/sonar-scanner-cli-$SCANNER_VERSION.zip"
mkdir -p "$SCANNER_ROOT"
if [ ! -f "$zip" ]; then
    curl -fsSL --connect-timeout 5 --max-time 120 -o "$zip.part" "$SCANNER_BASE/sonar-scanner-cli-$SCANNER_VERSION.zip" || exit 4
    mv "$zip.part" "$zip"
fi
printf '%s  %s\n' "$SCANNER_SHA256" "$zip" | sha256sum -c - >/dev/null || { echo 'SONAR_GATE ERROR scanner_checksum_mismatch' >&2; exit 2; }
unzip -q -o "$zip" -d "$SCANNER_ROOT"
if [ ! -x "$SCANNER_HOME/bin/sonar-scanner" ]; then
    zip="$SCANNER_ROOT/sonar-scanner-cli-$SCANNER_VERSION.zip"
    mkdir -p "$SCANNER_ROOT"
    if [ ! -f "$zip" ]; then
        echo "SONAR_GATE fetching sonar-scanner-cli-$SCANNER_VERSION"
        curl -fsSL -o "$zip.part" "$SCANNER_BASE/sonar-scanner-cli-$SCANNER_VERSION.zip" || {
            rm -f "$zip.part"; echo 'SONAR_GATE ERROR scanner_download_failed' >&2; exit 4; }
        mv "$zip.part" "$zip"
    fi
    printf '%s  %s\n' "$SCANNER_SHA256" "$zip" | sha256sum -c - >/dev/null || {
        rm -f "$zip"; echo 'SONAR_GATE ERROR scanner_checksum_mismatch' >&2; exit 2; }
    unzip -q -o "$zip" -d "$SCANNER_ROOT"
fi
[ -x "$SCANNER_HOME/bin/sonar-scanner" ] || { echo 'SONAR_GATE ERROR scanner_unavailable' >&2; exit 2; }

json_string() {
    # json_string <file> <key> -> first string value for that key
    perl -0777 -e 'my ($file,$k)=@ARGV; open my $fh,"<",$file or exit 1; local $/; my $t=<$fh>;
                   print $1 if $t =~ /"\Q$k\E"\s*:\s*"([^"]*)"/' "$1" "$2"
}

sonar_curl() {
    # sonar_curl <api-url> <out-file> — the token travels on stdin, never argv.
    printf 'user = "%s:"\n' "$SONAR_TOKEN" | curl -fsS --connect-timeout 5 --max-time 20 --config - "$1" -o "$2"
}

out=build/sonar
mkdir -p "$out"
rm -rf .scannerwork "$out/report-task.txt" "$out/quality-gate.json"

if ! SONAR_HOST_URL="$SONAR_HOST_URL" SONAR_TOKEN="$SONAR_TOKEN" \
"$SCANNER_HOME/bin/sonar-scanner" \
    "-Dsonar.projectKey=$PROJECT_KEY" \
    -Dsonar.projectName=Frontend \
    "-Dsonar.host.url=$SONAR_HOST_URL" \
    "-Dsonar.nodejs.executable=$NODE_EXE" \
    -Dsonar.typescript.tsconfigPaths=tsconfig.sonar.json \
    -Dsonar.sourceEncoding=UTF-8 \
    -Dsonar.sources=index.ts,App.tsx,src \
    -Dsonar.tests=src \
    '-Dsonar.test.inclusions=src/**/*.test.ts,src/**/*.test.tsx' \
    -Dsonar.javascript.lcov.reportPaths= \
    '-Dsonar.exclusions=**/node_modules/**,**/dist*/**,**/coverage/**,**/.expo/**,**/.redesign-artifacts/**,**/.verification/**,**/test-results/**,**/playwright-report/**,**/*.config.js,**/*.config.ts' > "$out/scanner.log" 2>&1; then
    cat "$out/scanner.log"
    echo 'SONAR_GATE ERROR scanner_failed' >&2; exit 3
fi
cat "$out/scanner.log"
"$NODE_EXE" ci/verify-sonar-analysis.cjs "$out/scanner.log" || exit 3

report=.scannerwork/report-task.txt
[ -f "$report" ] || { echo 'SONAR_GATE ERROR missing_report_task_txt' >&2; exit 3; }
cp "$report" "$out/report-task.txt"
ce_task=$(sed -n 's/^ceTaskId=//p' "$report" | head -n 1)
project=$(sed -n 's/^projectKey=//p' "$report" | head -n 1)
printf '%s\n' "$ce_task" | grep -Eq '^[A-Za-z0-9_-]{8,80}$' || { echo 'SONAR_GATE ERROR invalid_ce_task_id' >&2; exit 3; }
printf '%s\n' "$project" | grep -Eq '^baotin_[a-z][a-z0-9-]{2,40}$' || {
    echo 'SONAR_GATE ERROR unexpected_project_key' >&2; exit 3; }
[ "$project" = "$PROJECT_KEY" ] || { echo 'SONAR_GATE ERROR wrong_project' >&2; exit 3; }
echo "SONAR_GATE project=$project ce_task=$ce_task"

: "${SONAR_GATE_TIMEOUT_SECONDS:=900}"
case "$SONAR_GATE_TIMEOUT_SECONDS" in ''|*[!0-9]*) exit 2;; esac
[ "$SONAR_GATE_TIMEOUT_SECONDS" -ge 1 ] && [ "$SONAR_GATE_TIMEOUT_SECONDS" -le 1800 ] || exit 2
deadline=$(( $(date +%s) + SONAR_GATE_TIMEOUT_SECONDS ))

analysis=''
while :; do
    body=$(mktemp)
    if sonar_curl "$SONAR_HOST_URL/api/ce/task?id=$ce_task" "$body"; then
        state=$(json_string "$body" status)
        case "$state" in
            SUCCESS)  analysis=$(json_string "$body" analysisId); rm -f "$body"; break ;;
            FAILED|CANCELED)
                rm -f "$body"
                echo "SONAR_GATE ERROR compute_engine_$state" >&2; exit 3 ;;
        esac
    fi
    rm -f "$body"
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo 'SONAR_GATE ERROR compute_engine_timeout' >&2; exit 4
    fi
    sleep 5
done
printf '%s\n' "$analysis" | grep -Eq '^[A-Za-z0-9_-]{8,80}$' || { echo 'SONAR_GATE ERROR invalid_analysis_id' >&2; exit 3; }

body=$(mktemp)
if ! sonar_curl \
        "$SONAR_HOST_URL/api/qualitygates/project_status?analysisId=$analysis" "$body"; then
    rm -f "$body"
    echo 'SONAR_GATE ERROR quality_gate_query_failed' >&2; exit 4
fi
cp "$body" "$out/quality-gate.json"
gate=$(perl -0777 -ne 'print $1 if /"projectStatus"\s*:\s*\{\s*"status"\s*:\s*"([A-Z_]+)"/' "$body")
rm -f "$body"

if [ "$gate" != OK ]; then
    echo "SONAR_GATE FAIL project=$project analysis=$analysis gate=$gate" >&2
    perl -0777 -ne 'while(/\{([^{}]*"metricKey"[^{}]*)\}/g){
            my $o=$1;
            my ($m)=$o=~/"metricKey"\s*:\s*"([^"]*)"/;
            my ($s)=$o=~/"status"\s*:\s*"([^"]*)"/;
            my ($a)=$o=~/"actualValue"\s*:\s*"([^"]*)"/;
            my ($t)=$o=~/"errorThreshold"\s*:\s*"([^"]*)"/;
            printf "  condition %s=%s (actual=%s threshold=%s)\n", $m,$s,$a//"-",$t//"-";
        }' "$out/quality-gate.json" >&2 || true
    exit 5
fi

perl ci/sonar-validate-gate.pl "$out/quality-gate.json" "$project"
echo "SONAR_GATE PASS project=$project analysis=$analysis gate=OK"
