#!/usr/bin/env sh
# Run from a service repo after test/bootJar and image build. No registry login or deploy.
set -eu
: "${IMAGE_REFERENCE:?Exact locally built image reference required}"
: "${GITLEAKS_IMAGE:=zricethezav/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f}"
: "${TRIVY_IMAGE:=aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa}"
for image in "$GITLEAKS_IMAGE" "$TRIVY_IMAGE"; do
    printf '%s\n' "$image" | grep -Eq '^[a-z0-9][a-z0-9./:_-]+@sha256:[a-f0-9]{64}$' || exit 2
done
out=build/security
mkdir -p "$out"
rm -f "$out/gitleaks.json" "$out/dependencies.json" "$out/image.json" "$out/sbom.cdx.json"
prefix="baotin-security-$$"
leak="$prefix-leak"; dep="$prefix-dep"; scan="$prefix-image"; sbom="$prefix-sbom"
leak_id=''; dep_id=''; scan_id=''; sbom_id=''; cache_lock_id=''
cleanup() {
    result=$?
    cleanup_failed=0
    for id in "$leak_id" "$dep_id" "$scan_id" "$sbom_id"; do
        if [ -n "$id" ]; then
            docker rm -f "$id" >/dev/null 2>&1 || cleanup_failed=1
        fi
    done
    # Never unlock while a scanner might still be writing the shared cache.
    if [ "$cleanup_failed" -eq 0 ] && [ -n "$cache_lock_id" ]; then
        docker rm -f "$cache_lock_id" >/dev/null 2>&1 || cleanup_failed=1
    fi
    rm -f "$out/image.tar" "$out/source.tar"
    if [ "$cleanup_failed" -ne 0 ]; then
        echo 'TRIVY_CACHE_LOCK ERROR scanner_or_lock_cleanup_failed' >&2
        if [ "$result" -eq 0 ]; then result=4; fi
    fi
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Uploads work even when the daemon is remote dind; /tmp exists in both scanner images.
leak_id=$(docker create --name "$leak" --cap-drop ALL --security-opt no-new-privileges \
    "$GITLEAKS_IMAGE" dir /tmp --redact --report-format json --report-path /tmp/gitleaks.json)
tar --exclude=.git --exclude=.gradle --exclude=build -cf "$out/source.tar" .
docker cp - "$leak:/tmp" < "$out/source.tar"
status=0; docker start -a "$leak" || status=$?
docker cp "$leak:/tmp/gitleaks.json" "$out/gitleaks.json"
[ "$status" -eq 0 ] || exit "$status"
jar=$(find build/libs -maxdepth 1 -name '*-SNAPSHOT.jar' ! -name '*-plain.jar')
[ -n "$jar" ] && [ "$(printf '%s\n' "$jar" | wc -l | tr -d ' ')" -eq 1 ]
docker volume create baotin-security-trivy-cache >/dev/null
# The cache is shared by JAR and optional image jobs on this daemon, including
# callers on other hosts. Docker name creation is the cross-client atomic lock.
: "${TRIVY_CACHE_LOCK_WAIT_SECONDS:=600}"
case "$TRIVY_CACHE_LOCK_WAIT_SECONDS" in ''|*[!0-9]*) exit 2;; esac
[ "$TRIVY_CACHE_LOCK_WAIT_SECONDS" -ge 1 ] && [ "$TRIVY_CACHE_LOCK_WAIT_SECONDS" -le 1800 ] || exit 2
cache_lock_name=baotin-security-trivy-cache-lock
deadline=$(( $(date +%s) + TRIVY_CACHE_LOCK_WAIT_SECONDS ))
missing_holder=0
while :; do
    if cache_lock_id=$(docker create --name "$cache_lock_name" \
        --label baotin.security-cache-lock=true --label "baotin.security-cache-owner=$prefix" \
        --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
        --entrypoint /bin/true "$TRIVY_IMAGE" 2>/dev/null); then
        break
    fi
    holder=$(docker inspect --format '{{index .Config.Labels "baotin.security-cache-lock"}}' "$cache_lock_name" 2>/dev/null || true)
    if [ -z "$holder" ]; then
        # A holder can release after create conflicts but before inspect sees it.
        # Retry once; persistent daemon/create errors still fail closed.
        missing_holder=$((missing_holder + 1))
        if [ "$missing_holder" -ge 2 ]; then
            echo 'TRIVY_CACHE_LOCK ERROR acquire_failed' >&2; exit 4
        fi
    elif [ "$holder" != true ]; then
        echo 'TRIVY_CACHE_LOCK ERROR acquire_failed' >&2; exit 4
    else
        missing_holder=0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo 'TRIVY_CACHE_LOCK ERROR wait_timeout existing_holder_preserved=true' >&2; exit 4
    fi
    sleep 1
done
# rootfs enables packaged-JAR analysis; fs can return zero language files for the same JAR.
dep_id=$(docker create --name "$dep" --cap-drop ALL --security-opt no-new-privileges \
    -v baotin-security-trivy-cache:/root/.cache/trivy "$TRIVY_IMAGE" rootfs /tmp \
    --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --output /tmp/dependencies.json)
docker cp "$jar" "$dep:/tmp/app.jar"
status=0; docker start -a "$dep" || status=$?
docker cp "$dep:/tmp/dependencies.json" "$out/dependencies.json"
[ "$status" -eq 0 ] || exit "$status"
grep -Eq '"Type":[[:space:]]*"jar"' "$out/dependencies.json" || exit 3
# Scan the exact built image archive without exposing a Docker socket to the scanner.
docker save -o "$out/image.tar" "$IMAGE_REFERENCE"
scan_id=$(docker create --name "$scan" --cap-drop ALL --security-opt no-new-privileges \
    -v baotin-security-trivy-cache:/root/.cache/trivy "$TRIVY_IMAGE" image --input /tmp/image.tar \
    --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --output /tmp/image.json)
docker cp "$out/image.tar" "$scan:/tmp/image.tar"
status=0; docker start -a "$scan" || status=$?
docker cp "$scan:/tmp/image.json" "$out/image.json"
[ "$status" -eq 0 ] || exit "$status"
grep -Eq '"Type":[[:space:]]*"jar"' "$out/image.json" || exit 3
grep -Eq '"Class":[[:space:]]*"os-pkgs"' "$out/image.json" || exit 3
sbom_id=$(docker create --name "$sbom" --cap-drop ALL --security-opt no-new-privileges \
    -v baotin-security-trivy-cache:/root/.cache/trivy "$TRIVY_IMAGE" image --input /tmp/image.tar \
    --format cyclonedx --output /tmp/sbom.cdx.json)
docker cp "$out/image.tar" "$sbom:/tmp/image.tar"
docker start -a "$sbom"
docker cp "$sbom:/tmp/sbom.cdx.json" "$out/sbom.cdx.json"
grep -Eq '"bomFormat":[[:space:]]*"CycloneDX"' "$out/sbom.cdx.json" || exit 3
echo 'SECURITY_GATES PASS secrets=0 dependencies_high_critical=0 image_high_critical=0 sbom=generated'
