FROM maven:3.10.0-eclipse-temurin-25 AS build

WORKDIR /workspace

COPY pom.xml .
COPY src/main ./src/main

RUN mvn --batch-mode clean package -DskipTests

FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 appgroup \
    && useradd --uid 10001 --gid appgroup \
        --home-dir /app --no-create-home \
        --shell /usr/sbin/nologin appuser \
    && mkdir -p /app \
    && chown appuser:appgroup /app

WORKDIR /app

COPY --from=build --chown=appuser:appgroup \
    /workspace/target/appointment-reservation-service-*.jar \
    /app/appointment-reservation-service.jar

EXPOSE 8082

USER appuser

HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 4 \
        "http://127.0.0.1:${SERVER_PORT:-8082}/actuator/health" > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/appointment-reservation-service.jar"]
