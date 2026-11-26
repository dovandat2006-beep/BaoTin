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
cleanup() {
    docker rm -f "$leak" "$dep" "$scan" "$sbom" >/dev/null 2>&1 || true
    rm -f "$out/image.tar" "$out/source.tar"
}
trap cleanup EXIT
# Uploads work even when the daemon is remote dind; /tmp exists in both scanner images.
docker create --name "$leak" --cap-drop ALL --security-opt no-new-privileges \
    "$GITLEAKS_IMAGE" dir /tmp --redact --report-format json --report-path /tmp/gitleaks.json >/dev/null
tar --exclude=.git --exclude=.gradle --exclude=build -cf "$out/source.tar" .
docker cp - "$leak:/tmp" < "$out/source.tar"
status=0; docker start -a "$leak" || status=$?
docker cp "$leak:/tmp/gitleaks.json" "$out/gitleaks.json"
[ "$status" -eq 0 ] || exit "$status"
jar=$(find build/libs -maxdepth 1 -name '*-SNAPSHOT.jar' ! -name '*-plain.jar')
[ -n "$jar" ] && [ "$(printf '%s\n' "$jar" | wc -l | tr -d ' ')" -eq 1 ]
docker volume create baotin-security-trivy-cache >/dev/null
# rootfs enables packaged-JAR analysis; fs can return zero language files for the same JAR.
docker create --name "$dep" --cap-drop ALL --security-opt no-new-privileges \
    -v baotin-security-trivy-cache:/root/.cache/trivy "$TRIVY_IMAGE" rootfs /tmp \
    --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --output /tmp/dependencies.json >/dev/null
docker cp "$jar" "$dep:/tmp/app.jar"
status=0; docker start -a "$dep" || status=$?
docker cp "$dep:/tmp/dependencies.json" "$out/dependencies.json"
[ "$status" -eq 0 ] || exit "$status"
grep -Eq '"Type":[[:space:]]*"jar"' "$out/dependencies.json" || exit 3
# Scan the exact built image archive without exposing a Docker socket to the scanner.
docker save -o "$out/image.tar" "$IMAGE_REFERENCE"
docker create --name "$scan" --cap-drop ALL --security-opt no-new-privileges \
    -v baotin-security-trivy-cache:/root/.cache/trivy "$TRIVY_IMAGE" image --input /tmp/image.tar \
    --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json --output /tmp/image.json >/dev/null
docker cp "$out/image.tar" "$scan:/tmp/image.tar"
status=0; docker start -a "$scan" || status=$?
docker cp "$scan:/tmp/image.json" "$out/image.json"
[ "$status" -eq 0 ] || exit "$status"
grep -Eq '"Type":[[:space:]]*"jar"' "$out/image.json" || exit 3
grep -Eq '"Class":[[:space:]]*"os-pkgs"' "$out/image.json" || exit 3
docker create --name "$sbom" --cap-drop ALL --security-opt no-new-privileges \
    -v baotin-security-trivy-cache:/root/.cache/trivy "$TRIVY_IMAGE" image --input /tmp/image.tar \
    --format cyclonedx --output /tmp/sbom.cdx.json >/dev/null
docker cp "$out/image.tar" "$sbom:/tmp/image.tar"
docker start -a "$sbom"
docker cp "$sbom:/tmp/sbom.cdx.json" "$out/sbom.cdx.json"
grep -Eq '"bomFormat":[[:space:]]*"CycloneDX"' "$out/sbom.cdx.json" || exit 3
echo 'SECURITY_GATES PASS secrets=0 dependencies_high_critical=0 image_high_critical=0 sbom=generated'
