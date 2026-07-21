# syntax=docker/dockerfile:1

# ---- Builder stage: resolve dependencies and build the fat jar ----
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline

COPY backend/src ./src
RUN mvn -B -q package -DskipTests

# ---- Runtime stage: slim JRE with just the built jar ----
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    update-ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN groupadd --system app && useradd --system --gid app --home-dir /app app

ENV PORT=8080

WORKDIR /app

COPY --from=builder /build/target/smartroute-backend.jar app.jar

RUN chown -R app:app /app

USER app

EXPOSE 8080

# Cloud Run injects PORT at runtime (defaults to 8080 locally); Spring Boot
# reads it via application.yml's server.port: ${PORT:8080}. This probe is
# for local `docker run` / non-Cloud Run orchestrators, since Cloud Run
# uses its own HTTP startup/liveness probes against the same endpoint.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/health" || exit 1

# -XX:TieredStopAtLevel=1 trades peak throughput for faster JIT warmup,
# worthwhile on Cloud Run where min-instances=0 means frequent cold starts.
CMD exec java -XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1 -jar app.jar
