#!/usr/bin/env bash
# Quay lui 1 service về tag đã lưu ở lần deploy trước.
#   ./rollback.sh auth-service
set -euo pipefail

SERVICE="${1:?Usage: rollback.sh <service>}"
cd "$(dirname "$0")"

ENV_KEY="$(printf '%s' "$SERVICE" | tr '[:lower:]-' '[:upper:]_' | tr -cd 'A-Z0-9_')"
TAG_VAR="IMAGE_TAG_${ENV_KEY}"

STATE_FILE=".deploy-state/${SERVICE}.prev"
ENV_FILE=".env"

[ -f "$STATE_FILE" ] || { echo "Không có trạng thái cũ cho $SERVICE (.deploy-state/${SERVICE}.prev)" >&2; exit 1; }

PREV="$(cut -d= -f2- "$STATE_FILE")"
if [ "$PREV" = "<none>" ]; then
  echo "Lần deploy trước không ghi nhận tag, không thể rollback." >&2
  exit 1
fi

echo ">>> Rollback $SERVICE về $PREV"
sed -i.bak "s|^${TAG_VAR}=.*|${TAG_VAR}=${PREV}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"

[ -f "docker-compose.prod.yml" ] || { echo "Thiếu docker-compose.prod.yml" >&2; exit 1; }
OVERLAY=""

# shellcheck disable=SC2086
docker compose --env-file "$ENV_FILE" $OVERLAY pull "$SERVICE"
# shellcheck disable=SC2086
docker compose --env-file "$ENV_FILE" $OVERLAY up -d --no-deps --force-recreate "$SERVICE"

echo "✅ Đã rollback $SERVICE về $PREV"