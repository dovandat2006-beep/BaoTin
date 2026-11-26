#!/usr/bin/env bash
# =============================================================================
#  BaoTin — deploy một service
#
#  Dùng chung cho GitHub Actions và Jenkins (cả hai đều gọi qua SSH).
#
#  Usage: ./deploy.sh <service> <image-tag-or-digest> [staging|prod]
#
#  Ví dụ:
#    ./deploy.sh auth-service ghcr.io/baotin/auth-service:2024abc staging
#    ./deploy.sh auth-service build-a1b2c3d prod
#
#  Yêu cầu trên host:
#    - docker + docker compose v2
#    - file .env cùng thư mục (copy từ .env.example)
#    - quyền ghi .env (script tự cập nhật IMAGE_TAG_<SERVICE>)
# =============================================================================
set -euo pipefail

SERVICE="${1:?Usage: deploy.sh <service> <tag> [env]}"
TAG="${2:?Thiếu image tag}"
TARGET_ENV="${3:-staging}"

cd "$(dirname "$0")"

COMPOSE_FILE="docker-compose.prod.yml"
ENV_FILE=".env"

log()  { printf '\033[0;36m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '\033[0;33m[%s] WARN\033[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf '\033[0;31m[%s] ERROR\033[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

[ -f "$COMPOSE_FILE" ] || die "Không tìm thấy $COMPOSE_FILE. Script phải chạy trong thư mục chứa docker-compose prod."
[ -f "$ENV_FILE" ]     || die "Không tìm thấy $ENV_FILE. Chạy: cp .env.example $ENV_FILE rồi điền secrets."

# Tên service trong .env dùng ENV_KEY: auth-service -> AUTH_SERVICE
ENV_KEY="$(printf '%s' "$SERVICE" | tr '[:lower:]-' '[:upper:]_' | tr -cd 'A-Z0-9_')"
TAG_VAR="IMAGE_TAG_${ENV_KEY}"
PORT_VAR="PORT_${ENV_KEY}"

# --- Validate ---------------------------------------------------------------
if ! docker compose version >/dev/null 2>&1; then
  die "Cần docker compose v2 (docker compose, không phải docker-compose)."
fi

# Chấp nhận cả dạng đầy đủ (ghcr.io/owner/svc:tag) và dạng ngắn (tag / sha)
if [[ "$TAG" == *:* ]]; then
  REF="$TAG"
else
  REF="ghcr.io/${GITHUB_OWNER:-baotin}/${SERVICE}:${TAG}"
fi

# --- Backup trạng thái hiện tại để rollback --------------------------------
STATE_FILE=".deploy-state/${SERVICE}.prev"
mkdir -p .deploy-state
if grep -qE "^${TAG_VAR}=" "$ENV_FILE"; then
  grep -E "^${TAG_VAR}=" "$ENV_FILE" > "$STATE_FILE"
else
  echo "${TAG_VAR}=<none>" > "$STATE_FILE"
fi

# --- Cập nhật .env ----------------------------------------------------------
log "Gán ${TAG_VAR}=${REF}"
if grep -qE "^${TAG_VAR}=" "$ENV_FILE"; then
  # Dùng | làm delimiter vì ref chứa ký tự đặc biệt
  sed -i.bak "s|^${TAG_VAR}=.*|${TAG_VAR}=${REF}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
else
  printf '\n# --- Ghi đè tự động bởi deploy.sh (lúc %s) ---\n%s=%s\n' \
    "$(date -u +%FT%TZ)" "$TAG_VAR" "$REF" >> "$ENV_FILE"
fi

# --- Pull -------------------------------------------------------------------
log "Pull image: $REF"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull "$SERVICE"

# --- Recreate ---------------------------------------------------------------
ENV_SECTION="env: ${TARGET_ENV}"
if [ -f "docker-compose.${TARGET_ENV}.yml" ]; then
  log "Dùng overlay docker-compose.${TARGET_ENV}.yml"
  COMPOSE_FILES="-f $COMPOSE_FILE -f docker-compose.${TARGET_ENV}.yml"
else
  warn "Không có docker-compose.${TARGET_ENV}.yml, dùng base config."
  COMPOSE_FILES="-f $COMPOSE_FILE"
fi

log "Recreate container $SERVICE ($TARGET_ENV)"
# shellcheck disable=SC2086
docker compose --env-file "$ENV_FILE" $COMPOSE_FILES up -d --no-deps --force-recreate "$SERVICE"

# --- Health check -----------------------------------------------------------
PORT="$(grep -oE "^${PORT_VAR}=[0-9]+" "$ENV_FILE" | cut -d= -f2 || true)"
if [ -z "$PORT" ]; then
  warn "Không tìm thấy ${PORT_VAR} trong .env, bỏ qua health check."
  exit 0
fi

log "Health check http://127.0.0.1:${PORT}/actuator/health (tối đa 30 lần × 10s)"
HEALTHY=0
for i in $(seq 1 30); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/actuator/health" || echo 000)"
  if [ "$CODE" = "200" ]; then
    log "✅ Healthy sau $((i * 10))s"
    HEALTHY=1
    break
  fi
  # Nếu container đã chết thì fail sớm thay vì chờ hết 5 phút
  if [ "$(docker inspect -f '{{.State.Status}}' "$(docker compose --env-file "$ENV_FILE" $COMPOSE_FILES ps -q "$SERVICE")" 2>/dev/null)" = "exited" ]; then
    warn "Container đã thoát. 20 dòng log gần nhất:"
    # shellcheck disable=SC2086
    docker compose --env-file "$ENV_FILE" $COMPOSE_FILES logs --tail=20 "$SERVICE" >&2 || true
    die "Container $SERVICE thoát ngay sau khi start."
  fi
  sleep 10
done

if [ "$HEALTHY" -ne 1 ]; then
  warn "Không healthy sau 5 phút. Log gần nhất:"
  # shellcheck disable=SC2086
  docker compose --env-file "$ENV_FILE" $COMPOSE_FILES logs --tail=50 "$SERVICE" >&2 || true

  # STATE_FILE giữ nguyên cả dòng "IMAGE_TAG_X=<ref cũ>".
  PREV_LINE="$(cat "$STATE_FILE")"
  PREV_VALUE="${PREV_LINE#*=}"
  if [ "$PREV_VALUE" = "<none>" ] || [ -z "$PREV_VALUE" ]; then
    die "Deploy thất bại và chưa từng có bản chạy trước đó để rollback. Xem log phía trên."
  fi

  warn "Tự động rollback về ${PREV_LINE}"
  sed -i.bak "s|^${TAG_VAR}=.*|${PREV_LINE}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
  # shellcheck disable=SC2086
  docker compose --env-file "$ENV_FILE" $COMPOSE_FILES up -d --no-deps --force-recreate "$SERVICE" || true
  die "Deploy thất bại, đã rollback về ${PREV_VALUE}."
fi

log "🎉 Deploy $SERVICE lên $TARGET_ENV thành công ($REF)"