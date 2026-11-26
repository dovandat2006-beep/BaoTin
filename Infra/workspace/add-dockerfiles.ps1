$services = @("API-Gateway", "Analytics-Service", "Auth-Service", "Campaign-Service", "Model-Inference-Service", "Notification-Service", "Report-Service", "Reputation-Service", "Search-Service", "Threat-Service")

$dockerfileContent = @"
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src
RUN chmod +x ./gradlew
RUN ./gradlew build -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
"@

foreach ($svc in $services) {
    $path = "M:\BaoTin\$svc\Dockerfile"
    Set-Content -Path $path -Value $dockerfileContent
    Write-Host "Created Dockerfile in $svc"
}
