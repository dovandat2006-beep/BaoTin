#!/usr/bin/env sh
# SonarQube quality gate for a Gradle service.
#
# Runs on the Jenkins controller (JDK 21) and talks to the internal SonarQube
# server over the `jenkins` Docker network — the build daemon (dind) is NOT
# reachable from the server, so no scanner container is used here.
#
# Contract:
#   SONAR_HOST_URL  internal SonarQube base URL (from Jenkins global env)
#   SONAR_TOKEN     credential injected by withCredentials(string)
#
# Fail closed: an unprocessed/failed compute-engine task, a transport error or a
# non-OK gate all fail the build with a distinct code.
# The token is never printed and `set -x` is never enabled.
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

command -v curl >/dev/null 2>&1 || { echo 'SONAR_GATE ERROR curl_missing' >&2; exit 2; }
command -v perl >/dev/null 2>&1 || { echo 'SONAR_GATE ERROR perl_missing' >&2; exit 2; }
command -v mktemp >/dev/null 2>&1 || { echo 'SONAR_GATE ERROR mktemp_missing' >&2; exit 2; }

json_string() {
    # json_string <file> <key> -> first string value for that key
    perl -0777 -e 'my ($file,$k)=@ARGV; open my $fh,"<",$file or exit 1; local $/; my $t=<$fh>;
                   print $1 if $t =~ /"\Q$k\E"\s*:\s*"([^"]*)"/' "$1" "$2"
}

sonar_curl() {
    # sonar_curl <api-url> <out-file> — the token travels on stdin, never argv,
    # so it cannot be read back from the process list.
    printf 'user = "%s:"\n' "$SONAR_TOKEN" | curl -fsS --connect-timeout 5 --max-time 20 --config - "$1" -o "$2"
}

out=build/sonar
mkdir -p "$out"
rm -f "$out/report-task.txt" "$out/quality-gate.json"

# Test (unit + integration) chạy trên máy dev / lane GitHub Verify, không chạy ở
# lane release, nên phân tích này không có coverage; các project backend cũng
# không cấu hình condition coverage (xem Infra/sonarqube/README.md).
# Java analysis needs current main bytecode, but no test execution or packaged JAR.
# Clean here only: a later clean bootJar would erase this analysis evidence.
./gradlew --no-daemon --console=plain --max-workers=1 clean classes sonar

report=build/sonar/report-task.txt
[ -f "$report" ] || { echo 'SONAR_GATE ERROR missing_report_task_txt' >&2; exit 3; }
ce_task=$(sed -n 's/^ceTaskId=//p' "$report" | head -n 1)
project=$(sed -n 's/^projectKey=//p' "$report" | head -n 1)
printf '%s\n' "$ce_task" | grep -Eq '^[A-Za-z0-9_-]{8,80}$' || { echo 'SONAR_GATE ERROR invalid_ce_task_id' >&2; exit 3; }
printf '%s\n' "$project" | grep -Eq '^baotin_[a-z][a-z0-9-]{2,40}$' || {
    echo 'SONAR_GATE ERROR unexpected_project_key' >&2; exit 3; }
[ "$project" = "baotin_${SERVICE:?SERVICE is required}" ] || { echo 'SONAR_GATE ERROR wrong_project' >&2; exit 3; }
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
