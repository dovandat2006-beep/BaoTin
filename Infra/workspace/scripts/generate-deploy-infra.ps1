# =============================================================================
#  BaoTin — Generator hạ tầng deploy
#
#  Sinh:
#    Infra/deploy/deploy.sh                 script deploy dùng chung (GHA + Jenkins)
#    Infra/deploy/docker-compose.prod.yml   stack production, pull từ ghcr.io
#    Infra/deploy/.env.example
#    Infra/deploy/rollback.sh               quay lui 1 phiên bản
#
#  Chạy:  pwsh -File scripts/generate-deploy-infra.ps1
# =============================================================================

[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$GitHubOwner = 'baotin'
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'service-map.ps1')

$Root = $script:BaoTinRoot
$DeployDir = Join-Path $Root 'Infra\deploy'

$Written = 0
function Write-File {
    param([string]$Path, [string]$Content)
    $rel = $Path.Substring($Root.Length + 1).Replace('\', '/')
    if ($DryRun) {
        Write-Host "  [DRY] $rel" -ForegroundColor DarkGray
        return
    }
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    if ((Test-Path $Path) -and ((Get-Content $Path -Raw) -eq $Content)) {
        Write-Host "  [=]    $rel (unchanged)" -ForegroundColor DarkGray
        return
    }
    Set-Content -Path $Path -Value $Content -NoNewline -Encoding UTF8
    Write-Host "  [+]    $rel" -ForegroundColor Green
    $script:Written++
}

# =============================================================================
#  deploy.sh — chạy TRÊN HOST ĐÍCH, gọi bởi GitHub Actions hoặc Jenkins qua SSH
# =============================================================================

$DEPLOY_SH = @'
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
'@

# =============================================================================
#  rollback.sh
# =============================================================================

$ROLLBACK_SH = @'
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
'@

# =============================================================================
#  docker-compose.prod.yml
# =============================================================================

$svcBlocks = New-Object System.Collections.Generic.List[string]
$portLines = New-Object System.Collections.Generic.List[string]
$tagLines  = New-Object System.Collections.Generic.List[string]

foreach ($svc in $script:BaoTinServices) {
    $k = $svc.EnvKey

    $portLines.Add("PORT_$k=$($svc.Port)          # $($svc.Description)")
    $tagLines.Add("IMAGE_TAG_$k=ghcr.io/$GitHubOwner/$($svc.Image):main")

    # Head: image + port mapping
    $head = @"
  $($svc.Image):
    # Trong CI/CD image KHÔNG được build tại chỗ — luôn pull từ ghcr.io
    image: `${IMAGE_TAG_$k}
    container_name: baotin-$($svc.Image)
    restart: unless-stopped
    ports:
      - "`${PORT_$k}:8080"
"@

    if ($svc.Schema) {
        $tail = @"
    environment:
      - SPRING_PROFILES_ACTIVE=`${SPRING_PROFILES_ACTIVE:-prod}
      - SPRING_DATASOURCE_URL=jdbc:postgresql://`${POSTGRES_HOST:-postgres}:5432/`${POSTGRES_DB:-baotin}?currentSchema=$($svc.Schema)
      - SPRING_DATASOURCE_USERNAME=`${POSTGRES_USER:-postgres}
      - SPRING_DATASOURCE_PASSWORD=`${$($svc.DbPass)}
      - SPRING_REDIS_HOST=`${REDIS_HOST:-redis}
      - SPRING_KAFKA_BOOTSTRAP_SERVERS=`${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}
      - TZ=Asia/Ho_Chi_Minh
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
      kafka:
        condition: service_started
"@
    } else {
        $tail = @"
    environment:
      - SPRING_PROFILES_ACTIVE=`${SPRING_PROFILES_ACTIVE:-prod}
      - SPRING_REDIS_HOST=`${REDIS_HOST:-redis}
      - SPRING_KAFKA_BOOTSTRAP_SERVERS=`${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}
      - TZ=Asia/Ho_Chi_Minh
    depends_on:
      redis:
        condition: service_started
"@
    }

    # Here-string của PowerShell bỏ newline ngay trước terminator, phải join tay
    $svcBlocks.Add(($head.TrimEnd() + "`n" + $tail.TrimEnd()) + "`n")
}

$COMPOSE = @"
# =============================================================================
#  BaoTin — Production stack
#
#  Được sinh tự động bởi scripts/generate-deploy-infra.ps1 — đừng sửa tay,
#  hãy sửa service-map.ps1 rồi chạy lại generator.
#
#  Deploy:  ./deploy.sh <service> <tag> <env>
#  Rollback: ./rollback.sh <service>
# =============================================================================

name: baotin-prod

services:
  # ===========================================================================
  # INFRA
  # ===========================================================================
  postgres:
    image: postgres:15-alpine
    container_name: baotin-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: `${POSTGRES_DB:-baotin}
      POSTGRES_USER: `${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: `${POSTGRES_PASSWORD:?Cần set POSTGRES_PASSWORD trong .env}
      TZ: Asia/Ho_Chi_Minh
    volumes:
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql:ro
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U `${POSTGRES_USER:-postgres} -d `${POSTGRES_DB:-baotin}"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks: [ baotin-net ]

  redis:
    image: redis:7-alpine
    container_name: baotin-redis
    restart: unless-stopped
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5
    networks: [ baotin-net ]

  kafka:
    image: bitnami/kafka:3.6
    container_name: baotin-kafka
    restart: unless-stopped
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 0@kafka:9093
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "true"
      ALLOW_PLAINTEXT_LISTENER: "yes"
    volumes:
      - kafka_data:/bitnami/kafka
    healthcheck:
      test: ["CMD-SHELL", "kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"]
      interval: 15s
      timeout: 10s
      retries: 10
      start_period: 30s
    networks: [ baotin-net ]

  minio:
    image: minio/minio:latest
    container_name: baotin-minio
    restart: unless-stopped
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: `${MINIO_ROOT_USER:?Cần set MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: `${MINIO_ROOT_PASSWORD:?Cần set MINIO_ROOT_PASSWORD}
    volumes:
      - minio_data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 15s
      timeout: 5s
      retries: 5
    networks: [ baotin-net ]

  # ===========================================================================
  # MICROSERVICES — image do CI/CD build và push, không build tại chỗ
  # ===========================================================================
$($svcBlocks -join "`n`n")

  # ===========================================================================
  # REVERSE PROXY (đặt trước gateway để nhận traffic)
  # ===========================================================================
  nginx:
    image: nginx:1.27-alpine
    container_name: baotin-nginx
    restart: unless-stopped
    ports:
      - "`${HTTP_PORT:-80}:80"
      - "`${HTTPS_PORT:-443}:443"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./certbot/conf:/etc/letsencrypt:ro
      - ./certbot/www:/var/www/certbot:ro
    depends_on:
      api-gateway:
        condition: service_started
    networks: [ baotin-net ]

  certbot:
    image: certbot/certbot:latest
    container_name: baotin-certbot
    restart: unless-stopped
    volumes:
      - ./certbot/conf:/etc/letsencrypt
      - ./certbot/www:/var/www/certbot
    entrypoint: >
      /bin/sh -c "trap exit TERM;
      while :; do certbot renew --webroot -w /var/www/certbot --quiet; sleep 12h & wait `$${!}; done"

volumes:
  postgres_data:
  redis_data:
  kafka_data:
  minio_data:

networks:
  baotin-net:
    name: baotin-prod-net
    driver: bridge
"@

# =============================================================================
#  .env.example
# =============================================================================

$ENV_EXAMPLE = @"
# =============================================================================
#  BaoTin — Production environment
#  cp .env.example .env  rồi điền giá trị thật.  KHÔNG commit file .env.
# =============================================================================

# --- Owner GitHub (dùng khi deploy bằng tag ngắn) ---
GITHUB_OWNER=$GitHubOwner
SPRING_PROFILES_ACTIVE=prod

# --- PostgreSQL ---
POSTGRES_HOST=postgres
POSTGRES_DB=baotin
POSTGRES_USER=postgres
POSTGRES_PASSWORD=CHANGE_ME_strong_password

# Mật khẩu từng service — PHẢI khớp với Infra/init.sql
$($script:BaoTinServices | Where-Object { $_.DbPass } | ForEach-Object { "$($_.DbPass)=CHANGE_ME" } | Join-String -Separator "`n")

# --- Redis / Kafka / MinIO ---
REDIS_HOST=redis
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
MINIO_ROOT_USER=baotin
MINIO_ROOT_PASSWORD=CHANGE_ME

# --- Ports public ---
HTTP_PORT=80
HTTPS_PORT=443

# --- Port từng service ---
$($portLines -join "`n")

# --- Image tag do deploy.sh ghi đè tự động ---
$($tagLines -join "`n")
"@

# =============================================================================
#  nginx.conf
# =============================================================================

$NGINX = @'
# Reverse proxy cho BaoTin. Chỉ gateway được expose ra ngoài.
upstream api_gateway { server api-gateway:8080; }

server {
    listen 80;
    server_name _;

    # ACME challenge cho Let's Encrypt
    location /.well-known/acme-challenge/ { root /var/www/certbot; }

    # Chuyển sang HTTPS sau khi có cert (bỏ comment khi prod thật)
    # location / { return 301 https://$host$request_uri; }

    location / {
        proxy_pass http://api_gateway;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade           $http_upgrade;   # WebSocket
        proxy_set_header Connection        "upgrade";
        proxy_read_timeout 60s;
    }
}

# --- HTTPS (bật sau khi certbot đã cấp cert) ---
# server {
#     listen 443 ssl http2;
#     server_name baotin.vn;
#     ssl_certificate     /etc/letsencrypt/live/baotin.vn/fullchain.pem;
#     ssl_certificate_key /etc/letsencrypt/live/baotin.vn/privkey.pem;
#     location / { proxy_pass http://api_gateway; proxy_set_header Host $host; proxy_set_header X-Forwarded-Proto $scheme; }
# }
'@

# =============================================================================
#  Ghi file
# =============================================================================

Write-Host ""
Write-Host "==> Sinh hạ tầng deploy (Infra/deploy)" -ForegroundColor Cyan

Write-File -Path (Join-Path $DeployDir 'deploy.sh')               -Content $DEPLOY_SH
Write-File -Path (Join-Path $DeployDir 'rollback.sh')             -Content $ROLLBACK_SH
Write-File -Path (Join-Path $DeployDir 'docker-compose.prod.yml') -Content $COMPOSE
Write-File -Path (Join-Path $DeployDir '.env.example')            -Content $ENV_EXAMPLE
Write-File -Path (Join-Path $DeployDir 'nginx.conf')              -Content $NGINX

# init.sql cần nằm cạnh compose để mount được
$initSql = Join-Path $Root 'Infra\init.sql'
$targetSql = Join-Path $DeployDir 'init.sql'
if ((Test-Path $initSql) -and -not (Test-Path $targetSql)) {
    if ($DryRun) {
        Write-Host "  [DRY] Infra/deploy/init.sql (copy từ Infra/init.sql)" -ForegroundColor DarkGray
    } else {
        Copy-Item $initSql $targetSql
        Write-Host "  [+]    Infra/deploy/init.sql (copy)" -ForegroundColor Green
    }
}

# Bit +x cho script shell
if (-not $DryRun) {
    if (Test-Path (Join-Path $DeployDir 'deploy.sh'))   { & icacls (Join-Path $DeployDir 'deploy.sh')   /grant 'Everyone:(RX)' 2>$null | Out-Null }
    if (Test-Path (Join-Path $DeployDir 'rollback.sh')) { & icacls (Join-Path $DeployDir 'rollback.sh') /grant 'Everyone:(RX)' 2>$null | Out-Null }
}

Write-Host ""
if ($DryRun) { Write-Host "DRY-RUN: chưa ghi file nào." -ForegroundColor Yellow }
else { Write-Host "Infra/deploy: $Written file cập nhật." -ForegroundColor Green }