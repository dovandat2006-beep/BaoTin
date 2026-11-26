# Tested JAR artifact only; never copies source configuration or secret directories.
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN addgroup -S -g 1001 baotin && adduser -S -u 1001 -G baotin baotin
WORKDIR /app
COPY app.jar /app/app.jar
RUN chmod 0444 /app/app.jar
USER 1001:1001
ENV SERVER_PORT=8080 SPRING_PROFILES_ACTIVE=team
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=45s --retries=20 CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-Xms64m","-Xmx256m","-jar","/app/app.jar"]
