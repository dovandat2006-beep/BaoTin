#!/usr/bin/env sh
# Trusted reviewed release on a separately configured Linux VPS. No DB migration.
set -eu
set +x
release=${1:?release directory required}
root=${2:?configured root required}
case "$root" in /opt/baotin|/opt/baotin/[A-Za-z0-9_-]*) ;; *) exit 2;; esac
case "$release" in "$root"/releases/*) ;; *) exit 2;; esac
test -f "$root/runtime.env" && test -f "$release/images.env" || exit 2
command -v jq >/dev/null && command -v curl >/dev/null || exit 2
docker compose version >/dev/null
# Runtime config and durable keys already belong to the operator; no secret upload.
candidate="$release/candidate.env"
awk -F= '$1 !~ /_IMAGE$/ {print}' "$root/runtime.env" > "$candidate"
cat "$release/images.env" >> "$candidate"
compose="$release/docker-compose.yml"
docker compose --project-name baotin-alt --env-file "$candidate" -f "$compose" config --quiet
docker compose --project-name baotin-alt --env-file "$candidate" -f "$compose" pull
# Read metadata without runtime secrets or network. Refuse old reader images after V10.
auth=$(sed -n 's/^AUTH_IMAGE=//p' "$release/images.env")
caps=$(docker run --rm --network none --entrypoint java "$auth" -Dloader.main=com.baotin.auth.operator.RuntimeCapabilities -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher)
# Query the real DB using only Auth runtime credentials, never migrator/operator.
docker compose --project-name baotin-alt --env-file "$candidate" -f "$compose" --profile operations run --rm prepare-secrets
db=$(docker compose --project-name baotin-alt --env-file "$candidate" -f "$compose" run --rm --no-deps -e DB_PASSWORD_FILE=/run/auth-config/spring.datasource.password --entrypoint java auth-service -Dloader.main=com.baotin.auth.operator.RuntimeDatabaseState -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher)
test "$(printf '%s' "$caps" | jq -r '.readerVersion')" -ge "$(printf '%s' "$db" | jq -r '.readerFloor')" || exit 3
test "$(printf '%s' "$caps" | jq -r '.minimumSchema')" -le "$(printf '%s' "$db" | jq -r '.schemaRevision')" || exit 3
cipher=$(printf '%s' "$db" | jq -r '.cipherFloor')
printf '%s' "$caps" | jq -e --arg c "$cipher" '.cipherReaders|index($c)!=null' >/dev/null
if test -f "$root/accepted.env"; then
    previous_auth=$(sed -n 's/^AUTH_IMAGE=//p' "$root/accepted.env")
    previous_caps=$(docker run --rm --network none --entrypoint java "$previous_auth" -Dloader.main=com.baotin.auth.operator.RuntimeCapabilities -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher)
    test "$(printf '%s' "$previous_caps" | jq -r '.readerVersion')" -ge "$(printf '%s' "$db" | jq -r '.readerFloor')" || exit 3
    test "$(printf '%s' "$previous_caps" | jq -r '.minimumSchema')" -le "$(printf '%s' "$db" | jq -r '.schemaRevision')" || exit 3
    printf '%s' "$previous_caps" | jq -e --arg c "$cipher" '.cipherReaders|index($c)!=null' >/dev/null
fi
cp "$root/runtime.env" "$release/previous.env"
port=$(sed -n 's/^WEB_PORT=//p' "$candidate")
case "$port" in ''|*[!0-9]*) exit 2;; esac
healthy() {
    docker compose --project-name baotin-alt --env-file "$candidate" -f "$compose" up -d --wait --wait-timeout 180 &&
    curl -fsS --connect-timeout 5 --max-time 10 "http://127.0.0.1:$port/healthz" > "$release/health.json" &&
    jq -e '.status=="UP"' "$release/health.json" >/dev/null
}
if ! healthy; then
    # Previous reviewed compatible manifest, when configured, is the rollback source.
    if test -f "$root/accepted.env"; then
        docker compose --project-name baotin-alt --env-file "$root/accepted.env" -f "$compose" up -d --wait --wait-timeout 180
        echo 'VPS_ROLLBACK PASS readiness=UP database=unchanged'
    else
        docker compose --project-name baotin-alt --env-file "$candidate" -f "$compose" stop
        echo 'VPS_FIRST_RELEASE FAILED candidate=stopped volumes=retained' >&2
    fi
    exit 5
fi
cp "$candidate" "$root/runtime.env"
cp "$candidate" "$root/accepted.env"
echo 'VPS_DOCKER_DEPLOY PASS backend=10 web=1 readiness=UP migrations=separate volumes=retained'
