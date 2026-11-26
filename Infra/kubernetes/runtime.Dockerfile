# Package only an already verified CI JAR. glibc/OpenMP is required by ONNX JNI.
FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl libgomp1 tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 1001 baotin && useradd -u 1001 -g baotin -d /app -s /usr/sbin/nologin baotin
WORKDIR /app
COPY --chown=1001:1001 app.jar /app/app.jar
RUN chmod 0444 /app/app.jar
USER 1001:1001
ENV SERVER_PORT=8080 SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java","-Xms32m","-Xmx256m","-XX:ActiveProcessorCount=2","-XX:+ExitOnOutOfMemoryError","-jar","/app/app.jar"]
