# =============================================================================
#  BaoTin — CI/CD Generator
#
#  Sinh ra, cho TỪNG backend service:
#    <Service>/.github/workflows/ci-cd.yml   -> GitHub Actions (build, test, push ghcr.io)
#    <Service>/Jenkinsfile                  -> Jenkins pipeline (build, test, push, deploy)
#    <Service>/Dockerfile                   -> multi-stage, non-root, layer-cache
#    <Service>/.dockerignore
#
#  Và cho hạ tầng:
#    Infra/deploy/docker-compose.prod.yml   -> stack production, pull image từ ghcr.io
#    Infra/deploy/.env.example
#    Infra/deploy/deploy.sh                 -> script deploy dùng chung (GHA + Jenkins)
#
#  Chạy:  pwsh -File scripts/generate-cicd.ps1
#  Dry-run: pwsh -File scripts/generate-cicd.ps1 -DryRun
# =============================================================================

[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$GitHubOwner = 'baotin',
    [string]$DeployHost = 'deploy.baotin.vn',
    [string]$DeployUser = 'deploy',
    [string]$DeployPath = '/opt/baotin'
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'service-map.ps1')

$Root = $script:BaoTinRoot
$Written = New-Object System.Collections.Generic.List[string]

function Write-File {
    param([string]$Path, [string]$Content)
    $rel = $Path.Substring($Root.Length + 1).Replace('\', '/')
    if ($DryRun) {
        Write-Host "  [DRY] $rel" -ForegroundColor DarkGray
        return
    }
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    # Chỉ ghi khi nội dung thay đổi, để không phá timestamp
    if ((Test-Path $Path) -and ((Get-Content $Path -Raw) -eq $Content)) {
        Write-Host "  [=]    $rel (unchanged)" -ForegroundColor DarkGray
        return
    }
    Set-Content -Path $Path -Value $Content -NoNewline -Encoding UTF8
    Write-Host "  [+]    $rel" -ForegroundColor Green
    $Written.Add($rel)
}

# =============================================================================
#  1. Dockerfiles + .dockerignore
# =============================================================================

$DOCKERFILE = @'
# syntax=docker/dockerfile:1.7
# ---------- Stage 1: build ----------
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Gradle wrapper + build script trước để cache layer dependency
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x ./gradlew

# Tải dependency trước, không phụ thuộc source -> rebuild nhanh khi chỉ sửa code
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    ./gradlew --no-daemon dependencies --configuration runtimeClasspath || true

COPY src ./src

# Build jar (CI đã chạy test ở stage trước, ở đây chỉ cần artifact)
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    ./gradlew --no-daemon clean bootJar -x test -Dorg.gradle.caching=true && \
    JAR="$(ls -1 build/libs/*-SNAPSHOT.jar 2>/dev/null | grep -v -- '-plain.jar' | head -n 1)" && \
    test -n "$JAR" && cp "$JAR" /workspace/app.jar && \
    echo "Built: $JAR"

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# JVM nhận biết giới hạn container (Docker Desktop đặt RAM/CPU limit)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod

RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Ho_Chi_Minh /etc/localtime && \
    echo "Asia/Ho_Chi_Minh" > /etc/timezone && \
    addgroup -S -g 1001 baotin && \
    adduser -S -u 1001 -G baotin -h /app baotin

COPY --from=builder /workspace/app.jar ./app.jar
RUN chown -R baotin:baotin /app && chmod 0444 /app/app.jar

USER 1001:1001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# exec form: signal handling đúng, không qua shell
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
'@

$DOCKERIGNORE = @'
# Build output
build/
out/
bin/
.gradle/

# VCS & IDE
.git/
.gitignore
.idea/
*.iml
.vscode/

# CI config không cần trong image context
.github/
.gitlab-ci.yml
Jenkinsfile
docs/
*.md
!README.md

# Secrets & env
.env
.env.*
!.env.example

# Test & tooling
src/test/
**/*.log
'@

# =============================================================================
#  2. GitHub Actions workflow
# =============================================================================

$GH_WORKFLOW = @'
# =============================================================================
#  __IMAGE__ — GitHub Actions CI/CD
#
#  1. build-test : Gradle compile + test + jacoco
#  2. docker     : build & push image lên ghcr.io (GHA cache)
#  3. deploy     : SSH sang host staging/prod, pull image & recreate container
#
#  Branch `__DEPLOY_BRANCH__` -> build + push + deploy. PR -> chỉ build.
#
#  Secrets cần set trong repo Settings > Secrets and variables > Actions:
#    GHCR_TOKEN          (packages:write)  — fallback về GITHUB_TOKEN
#    DEPLOY_HOST          host staging/prod
#    DEPLOY_USER          user SSH
#    DEPLOY_SSH_KEY       private key (OpenSSH, không mật khẩu)
#    DEPLOY_PATH          thư mục chứa docker-compose.prod.yml (mặc định /opt/baotin)
#    DEPLOY_ENV           staging | prod
# =============================================================================

name: CI/CD __IMAGE__

on:
  push:
    branches: [ '__DEPLOY_BRANCH__', 'develop' ]
    tags: [ 'v*' ]
  pull_request:
    branches: [ '__DEPLOY_BRANCH__', 'develop' ]
  workflow_dispatch:
    inputs:
      deploy_env:
        description: 'Môi trường đích khi chạy tay'
        type: choice
        options: [ none, staging, prod ]
        default: none
      skip_tests:
        description: 'Bỏ qua unit test'
        type: boolean
        default: false

concurrency:
  group: __IMAGE__-${{ github.ref }}
  cancel-in-progress: true

env:
  SERVICE: __IMAGE__
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository_owner }}/__IMAGE__

permissions:
  contents: read
  packages: write

jobs:
  # ===========================================================================
  build-test:
    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Setup Gradle (cache)
        uses: gradle/actions/setup-gradle@v4

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
            **/build/libs
          key: __IMAGE__-gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle', 'gradle/wrapper/gradle-wrapper.properties') }}
          restore-keys: |
            __IMAGE__-gradle-${{ runner.os }}-

      - name: Unit test + build jar
        if: ${{ !inputs.skip_tests }}
        run: ./gradlew --no-daemon clean build --stacktrace

      - name: Build jar (skip test)
        if: ${{ inputs.skip_tests }}
        run: ./gradlew --no-daemon clean bootJar -x test --stacktrace

      - name: Upload jar artifact
        uses: actions/upload-artifact@v4
        with:
          name: __IMAGE__-jar
          path: build/libs/*-SNAPSHOT.jar
          retention-days: 7
          if-no-files-found: warn

  # ===========================================================================
  docker:
    name: Docker Image
    needs: build-test
    runs-on: ubuntu-latest
    outputs:
      tags: ${{ steps.meta.outputs.tags }}
      digest: ${{ steps.meta.outputs.digest }}
      tag: ${{ steps.deploytag.outputs.value }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Free disk space
        run: |
          sudo rm -rf /usr/share/dotnet /opt/ghc /usr/local/lib/android /opt/hostedtoolcache/CodeQL || true
          df -h

      # Tag dùng để deploy: đúng MỘT giá trị, khác với `meta.outputs.tags`
      # (danh sách nhiều dòng) nên không thể truyền thẳng cho deploy.sh.
      #
      # Phải khớp CHÍNH XÁC với các tag mà metadata-action tạo ra ở step dưới.
      # Nếu suy ra sai, máy deploy sẽ pull phải tag không tồn tại:
      #   - git tag        -> {{version}} của semver (bỏ tiền tố 'v')
      #   - nhánh deploy   -> chính tên nhánh (type=raw)
      #   - nhánh develop  -> chính tên nhánh (type=raw)
      #   - nhánh khác     -> build-<7 ký tự đầu của github.sha> (type=sha,short)
      - name: Xác định tag để deploy
        id: deploytag
        env:
          REF_TYPE: ${{ github.ref_type }}
          REF_NAME: ${{ github.ref_name }}
          SHA_SHORT: ${{ github.sha }}
          DEPLOY_BRANCH: __DEPLOY_BRANCH__
        run: |
          if [ "$REF_TYPE" = "tag" ]; then
            echo "value=${REF_NAME#v}" >> "$GITHUB_OUTPUT"
          elif [ "$REF_NAME" = "$DEPLOY_BRANCH" ] || [ "$REF_NAME" = "develop" ]; then
            echo "value=${REF_NAME}" >> "$GITHUB_OUTPUT"
          else
            echo "value=build-$(printf '%s' "$SHA_SHORT" | cut -c1-7)" >> "$GITHUB_OUTPUT"
          fi

      - name: Compute image metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}
          tags: |
            type=raw,value=__DEPLOY_BRANCH__,enable=${{ github.ref == 'refs/heads/__DEPLOY_BRANCH__' }}
            type=raw,value=develop,enable=${{ github.ref == 'refs/heads/develop' }}
            type=semver,pattern={{version}}
            type=semver,pattern={{major}}.{{minor}}
            type=raw,value=latest,enable=${{ github.ref == 'refs/heads/__DEPLOY_BRANCH__' }}
            type=sha,format=short,prefix=build-

      - name: Set up QEMU
        uses: docker/setup-qemu-action@v3

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Login ghcr.io
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GHCR_TOKEN || github.token }}

      - name: Build & push image
        id: build
        uses: docker/build-push-action@v6
        with:
          context: .
          file: ./Dockerfile
          platforms: linux/amd64
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: |
            org.opencontainers.image.title=__IMAGE__
            org.opencontainers.image.version=${{ github.sha }}
            org.opencontainers.image.revision=${{ github.sha }}
            org.opencontainers.image.source=${{ github.server_url }}/${{ github.repository }}
          cache-from: type=gha,scope=__IMAGE__
          cache-to: type=gha,mode=max,scope=__IMAGE__
          build-args: |
            BUILDKIT_INLINE_CACHE=1

      - name: Ghi kết quả ra job summary
        env:
          DIGEST: ${{ steps.build.outputs.digest }}
          TAGS: ${{ steps.meta.outputs.tags }}
        run: |
          {
            echo "### 🐳 Image __IMAGE__"
            echo ""
            echo "**Digest:** \`${DIGEST}\`"
            echo ""
            echo "**Tags:**"
            echo '```'
            echo "${TAGS}"
            echo '```'
          } >> "$GITHUB_STEP_SUMMARY"

  # ===========================================================================
  deploy:
    name: Deploy
    # Chỉ deploy khi push lên nhánh chính, có tag v1, hoặc chạy tay VÀ chọn
    # môi trường thật (chọn "none" = chỉ build + push image).
    if: >-
      (github.event_name == 'workflow_dispatch' && inputs.deploy_env != 'none') ||
      github.ref == 'refs/heads/__DEPLOY_BRANCH__' ||
      startsWith(github.ref, 'refs/tags/v')
    needs: [build-test, docker]
    runs-on: ubuntu-latest
    environment:
      # Environment name phải tính được NGAY khi job bắt đầu, trước khi step
      # chạy — nên không dùng được output của step `target` bên dưới.
      name: ${{ inputs.deploy_env || (github.ref == 'refs/heads/__DEPLOY_BRANCH__' && 'prod' || 'staging') }}
      url: https://${{ secrets.DEPLOY_HOST }}
    steps:
      - name: Xác định môi trường đích
        id: target
        env:
          DISPATCH_CHOICE: ${{ inputs.deploy_env || 'none' }}
          GH_BRANCH: ${{ github.ref_name }}
        run: |
          if [ "$DISPATCH_CHOICE" != "none" ] && [ -n "$DISPATCH_CHOICE" ]; then
            ENV_NAME="$DISPATCH_CHOICE"
          elif [ "$GH_BRANCH" = "__DEPLOY_BRANCH__" ]; then
            ENV_NAME="prod"
          else
            ENV_NAME="staging"
          fi
          echo "env=$ENV_NAME" >> "$GITHUB_OUTPUT"
          echo "Deploy tới môi trường: $ENV_NAME"

      - name: Deploy + smoke test qua SSH
        uses: appleboy/ssh-action@v1.2.2
        with:
          host: ${{ secrets.DEPLOY_HOST }}
          username: ${{ secrets.DEPLOY_USER }}
          key: ${{ secrets.DEPLOY_SSH_KEY }}
          port: 22
          script: |
            set -euo pipefail
            cd "${{ secrets.DEPLOY_PATH || '/opt/baotin' }}"
            ./deploy.sh __IMAGE__ "${{ needs.docker.outputs.tag }}" "${{ steps.target.outputs.env }}"
'@

# =============================================================================
#  3. Jenkinsfile
# =============================================================================

$JENKINSFILE = @'
// =============================================================================
//  __IMAGE__ — Jenkins pipeline
//
//  Chạy trên agent `__AGENT_LABEL__` (Java 21 + Docker CLI), build image qua
//  Docker-outside-of-Docker (dind), push lên ghcr.io rồi deploy qua SSH.
//
//  Credentials cần có trong Jenkins (được tạo tự động bởi
//  Infra/jenkins/init/baotin-ci.groovy từ init/baotin-ci.properties):
//    github-scm           : Username/Password — checkout source từ GitHub
//    ghcr-registry        : Username/Password — user GitHub + PAT (push image)
//    deploy-ssh-key       : SSH Username with private key — user deploy
//
//  Biến môi trường toàn cục (cũng do script trên tạo):
//    DEPLOY_HOST, DEPLOY_USER, DEPLOY_PATH, GITHUB_OWNER, TZ
//
//  Parameters:
//    SKIP_TESTS, DEPLOY_ENV, IMAGE_TAG
// =============================================================================

pipeline {
    agent { label '__AGENT_LABEL__' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '5'))
        timeout(time: 45, unit: 'MINUTES')
        ansiColor('xterm')
    }

    parameters {
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Bỏ qua unit test (chỉ build jar)'
        )
        choice(
            name: 'DEPLOY_ENV',
            choices: ['none', 'staging', 'prod'],
            description: 'Môi trường deploy. "none" = chỉ build + push image.'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: '',
            description: 'Ghi đè tag image (mặc định suy ra từ branch/commit)'
        )
    }

    environment {
        SERVICE        = '__IMAGE__'
        IMAGE          = 'ghcr.io/__GITHUB_OWNER__/__IMAGE__'
        GHCR           = 'ghcr.io'
        DEPLOY_BRANCH  = '__DEPLOY_BRANCH__'
        # DEPLOY_HOST, DEPLOY_USER, DEPLOY_PATH lấy từ biến môi trường toàn cục
        # của Jenkins — xem Infra/jenkins/init/baotin-ci.groovy.
    }

    stages {
        // =====================================================================
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_SHORT   = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.GIT_BRANCH  = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.SERVICE} [${env.GIT_BRANCH}@${env.GIT_SHORT}]"
                    currentBuild.description = "Deploy=${params.DEPLOY_ENV}"
                }
            }
        }

        // =====================================================================
        stage('Build & Test') {
            steps {
                sh '''
                    set -eu
                    java -version
                    ./gradlew --version
                    if [ "${SKIP_TESTS}" = "true" ]; then
                        ./gradlew --no-daemon clean bootJar -x test --stacktrace
                    else
                        ./gradlew --no-daemon clean build --stacktrace
                    fi
                    ls -lh build/libs/
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'build/test-results/**/TEST-*.xml'
                    archiveArtifacts artifacts: 'build/reports/**/*,build/libs/*-SNAPSHOT.jar',
                                     allowEmptyArchive: true, fingerprint: true
                }
            }
        }

        // =====================================================================
        stage('Docker Image') {
            steps {
                script {
                    if (env.IMAGE_TAG?.trim()) {
                        env.RESOLVED_TAG = env.IMAGE_TAG.trim()
                    } else if (env.GIT_BRANCH == env.DEPLOY_BRANCH) {
                        env.RESOLVED_TAG = env.DEPLOY_BRANCH
                    } else {
                        env.RESOLVED_TAG = "build-${env.GIT_SHORT}"
                    }
                    env.FULL_IMAGE = "${env.IMAGE}:${env.RESOLVED_TAG}"
                }

                withDockerRegistry([credentialsId: 'ghcr-registry', url: "https://${env.GHCR}"]) {
                    sh '''
                        set -eu

                        # Dùng builder mặc định của dind (BuildKit). Không tạo
                        # builder docker-container riêng: nhiều service build
                        # song song sẽ tranh nhau cùng một tên builder.
                        TAGS="--tag ${FULL_IMAGE}"
                        if [ "${GIT_BRANCH}" = "${DEPLOY_BRANCH}" ]; then
                            # Tag 'latest' chỉ dành cho nhánh chính — tránh
                            # staging đè lên bản đang chạy production.
                            TAGS="${TAGS} --tag ${IMAGE}:latest"
                        fi

                        # shellcheck disable=SC2086  (cần tách $TAGS thành nhiều tham số)
                        docker buildx build \
                            --file Dockerfile \
                            --platform linux/amd64 \
                            --build-arg BUILDKIT_INLINE_CACHE=1 \
                            ${TAGS} \
                            --push \
                            .
                    '''
                }
            }
        }

        // =====================================================================
        stage('Deploy') {
            when {
                expression { params.DEPLOY_ENV != 'none' && params.DEPLOY_ENV != null }
            }
            steps {
                sshagent(credentials: ['deploy-ssh-key']) {
                    sh '''
                        set -eu

                        # Biến toàn cục do Infra/jenkins/init/baotin-ci.groovy tạo.
                        # Báo lỗi rõ ràng nếu thiếu thay vì "unbound variable".
                        : "${DEPLOY_HOST:?Chưa cấu hình DEPLOY_HOST trong Jenkins (global env)}"
                        : "${DEPLOY_USER:?Chưa cấu hình DEPLOY_USER trong Jenkins (global env)}"
                        : "${DEPLOY_PATH:=/opt/baotin}"

                        ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 \
                            "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            "cd '${DEPLOY_PATH}' && ./deploy.sh '${SERVICE}' '${RESOLVED_TAG}' '${DEPLOY_ENV}'"
                    '''
                }
            }
        }
    }

    // =========================================================================
    post {
        always {
            cleanWs(notFailBuild: true, deleteDirs: true)
        }
        success {
            echo "✅ ${env.SERVICE} build + push image thành công: ${env.FULL_IMAGE ?: env.IMAGE}"
        }
        failure {
            echo "❌ ${env.SERVICE} pipeline thất bại (build #${env.BUILD_NUMBER})"
        }
        unstable {
            echo "⚠️  ${env.SERVICE} pipeline không ổn định"
        }
    }
}
'@

# =============================================================================
#  4. Render từng service
# =============================================================================

Write-Host ""
Write-Host "==> Sinh file CI/CD cho $($script:BaoTinServices.Count) backend service" -ForegroundColor Cyan
Write-Host "    GitHub owner : $GitHubOwner"
Write-Host "    Deploy host  : ${DeployUser}@${DeployHost}:${DeployPath}"
Write-Host ""

foreach ($svc in $script:BaoTinServices) {
    if($svc.Dir -in @('Auth-Service','API-Gateway')){
        Write-Host "  $($svc.Dir): maintained by generate-auth-gateway-cicd.ps1; Dockerfiles preserved"
        continue
    }
    Write-Host "  $($svc.Dir)" -ForegroundColor Yellow

    Write-File -Path (Join-Path $Root "$($svc.Dir)\Dockerfile") -Content $DOCKERFILE
    Write-File -Path (Join-Path $Root "$($svc.Dir)\.dockerignore") -Content $DOCKERIGNORE

    $wf = $GH_WORKFLOW.
        Replace('__IMAGE__',         $svc.Image).
        Replace('__DEPLOY_BRANCH__', $script:DeployBranch)
    Write-File -Path (Join-Path $Root "$($svc.Dir)\.github\workflows\ci-cd.yml") -Content $wf

    $jf = $JENKINSFILE.
        Replace('__IMAGE__',        $svc.Image).
        Replace('__GITHUB_OWNER__', $GitHubOwner).
        Replace('__AGENT_LABEL__',  $script:AgentLabel).
        Replace('__DEPLOY_BRANCH__', $script:DeployBranch)
    Write-File -Path (Join-Path $Root "$($svc.Dir)\Jenkinsfile") -Content $jf
}

Write-Host ""
Write-Host "==> Sinh hạ tầng deploy" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'generate-auth-gateway-cicd.ps1') -GitHubOwner $GitHubOwner -DryRun:$DryRun
& (Join-Path $PSScriptRoot 'generate-deploy-infra.ps1') -DryRun:$DryRun

Write-Host ""
if ($DryRun) {
    Write-Host "DRY-RUN: chưa ghi file nào." -ForegroundColor Yellow
} else {
    Write-Host "Hoàn tất: $($Written.Count) file được cập nhật." -ForegroundColor Green
}
