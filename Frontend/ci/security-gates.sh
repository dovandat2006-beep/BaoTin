#!/usr/bin/env sh
set -eu
set +x
GITLEAKS='zricethezav/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'
TRIVY='aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
mkdir -p build/security
child=''; lock=''
cleanup() {
 result=$?
 if [ -n "$child" ]; then docker rm -f "$child" >/dev/null || exit 4; fi
 if [ -n "$lock" ]; then docker rm -f "$lock" >/dev/null || exit 4; fi
 rm -f build/security/source.tar build/security/image.tar
 exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
if [ -z "${IMAGE_REFERENCE:-}" ]; then
 git archive --format=tar HEAD > build/security/source.tar
 child=$(docker create --cap-drop ALL --security-opt no-new-privileges "$GITLEAKS" dir /tmp --redact --report-format json --report-path /tmp/gitleaks.json)
 docker cp - "$child:/tmp" < build/security/source.tar
 status=0; docker start -a "$child" || status=$?
 docker cp "$child:/tmp/gitleaks.json" build/security/gitleaks.json
 [ "$status" -eq 0 ] || exit "$status"
 echo 'WEB_SECURITY PASS secrets=0'
 exit 0
fi
image=$(docker image inspect "$IMAGE_REFERENCE" --format '{{.Id}}')
printf '%s\n' "$image" | grep -Eq '^sha256:[a-f0-9]{64}$' || exit 2
docker volume create baotin-security-trivy-cache >/dev/null
deadline=$(( $(date +%s) + 900 ))
while ! lock=$(docker create --name baotin-security-trivy-cache-lock --label baotin.security-cache-lock=true --label baotin.security-cache-owner=frontend --network none --read-only --cap-drop ALL --security-opt no-new-privileges --entrypoint /bin/true "$TRIVY" 2>/dev/null); do
 [ "$(date +%s)" -lt "$deadline" ] || exit 4
 docker inspect baotin-security-trivy-cache-lock --format '{{index .Config.Labels "baotin.security-cache-lock"}}' | grep -qx true || exit 4
 sleep 3
done
docker save -o build/security/image.tar "$image"
child=$(docker create --cap-drop ALL --security-opt no-new-privileges -v baotin-security-trivy-cache:/root/.cache/trivy "$TRIVY" image --input /tmp/image.tar --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --output /tmp/report.json)
docker cp build/security/image.tar "$child:/tmp/image.tar"
status=0; docker start -a "$child" || status=$?
docker cp "$child:/tmp/report.json" build/security/image.json
[ "$status" -eq 0 ] || exit "$status"
docker rm "$child" >/dev/null; child=''
child=$(docker create --cap-drop ALL --security-opt no-new-privileges -v baotin-security-trivy-cache:/root/.cache/trivy "$TRIVY" image --input /tmp/image.tar --format cyclonedx --output /tmp/sbom.json)
docker cp build/security/image.tar "$child:/tmp/image.tar"
docker start -a "$child"
docker cp "$child:/tmp/sbom.json" build/security/sbom.cdx.json
test -s build/security/image.json && test -s build/security/sbom.cdx.json
echo 'WEB_IMAGE_SECURITY PASS high_critical=0 sbom=generated'
