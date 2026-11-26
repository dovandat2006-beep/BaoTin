#!/usr/bin/env sh
# Build tools may run in a container; the deployed artifact is a static web archive.
set -eu
: "${EXPO_PUBLIC_API_URL:?EXPO_PUBLIC_API_URL must identify the public HTTPS Gateway origin for release}"
NODE_IMAGE='node:24-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20'
name="baotin-web-build-$$"
mkdir -p build
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; rm -f build/source.tar; }
trap cleanup EXIT
# Git archive avoids node_modules, .env and operator files, even with remote dind.
git archive --format=tar HEAD > build/source.tar
android_snapshot=''
if [ -n "${BAOTIN_ANDROID_DOWNLOAD_DIR:-}" ]; then
    printf '%s\n' "$BAOTIN_ANDROID_DOWNLOAD_DIR" | grep -Eq '^/var/jenkins_home/android-releases/[a-f0-9]{64}$' || { echo 'ANDROID_DOWNLOAD ERROR exact external source-snapshot directory required' >&2; exit 2; }
    [ -d "$BAOTIN_ANDROID_DOWNLOAD_DIR" ] && [ ! -L "$BAOTIN_ANDROID_DOWNLOAD_DIR" ] || exit 2
    android_snapshot="${BAOTIN_ANDROID_DOWNLOAD_DIR##*/}"
fi
docker create --name "$name" --cap-drop ALL --security-opt no-new-privileges \
    --memory 2g --cpus 2 --workdir /work --env CI=1 --env EXPO_NO_TELEMETRY=1 --env EXPO_PUBLIC_API_URL="$EXPO_PUBLIC_API_URL" --env BAOTIN_ANDROID_DOWNLOAD_ID="$android_snapshot" \
    "$NODE_IMAGE" sh -c 'node ci/release-api-config.cjs && npm ci && npm run typecheck && if [ -d /tmp/android-download ]; then node scripts/stage-android-download.cjs /tmp/android-download /work/public/downloads "$BAOTIN_ANDROID_DOWNLOAD_ID"; fi && npm run build:web && node ci/release-api-config.cjs --verify-export && tar -czf /tmp/web.tar.gz -C dist .' >/dev/null
docker cp - "$name:/work" < build/source.tar
if [ -n "${BAOTIN_ANDROID_DOWNLOAD_DIR:-}" ]; then docker cp "$BAOTIN_ANDROID_DOWNLOAD_DIR" "$name:/tmp/android-download"; fi
docker start -a "$name"
docker cp "$name:/tmp/web.tar.gz" build/web.tar.gz
test -s build/web.tar.gz
echo 'WEB_BUILD PASS typecheck=true tests=external_verify artifact=build/web.tar.gz runtime=static_web'
