# syntax=docker/dockerfile:1.7
# ---------- Stage 1: build ----------
FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS builder
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

# Build jar only; tests belong to the separate verify lane, not image packaging.
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    ./gradlew --no-daemon clean bootJar --max-workers=1 --console=plain -Dorg.gradle.caching=true && \
    JAR="$(ls -1 build/libs/*-SNAPSHOT.jar 2>/dev/null | grep -v -- '-plain.jar' | head -n 1)" && \
    test -n "$JAR" && cp "$JAR" /workspace/app.jar && \
    echo "Built: $JAR"

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0
WORKDIR /app

# JVM nhận biết giới hạn container (Docker Desktop đặt RAM/CPU limit)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod

RUN apt-get update && apt-get install -y --no-install-recommends curl tzdata libgomp1 && rm -rf /var/lib/apt/lists/* && \
    cp /usr/share/zoneinfo/Asia/Ho_Chi_Minh /etc/localtime && \
    echo "Asia/Ho_Chi_Minh" > /etc/timezone && \
    groupadd -g 1001 baotin && \
    useradd -u 1001 -g baotin -d /app -s /usr/sbin/nologin baotin

COPY --from=builder /workspace/app.jar ./app.jar
RUN chown -R baotin:baotin /app && chmod 0444 /app/app.jar

USER 1001:1001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# exec form: signal handling đúng, không qua shell
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]