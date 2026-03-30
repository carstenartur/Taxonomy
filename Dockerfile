# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
COPY pom.xml .
COPY taxonomy-domain/pom.xml taxonomy-domain/pom.xml
COPY taxonomy-dsl/pom.xml taxonomy-dsl/pom.xml
COPY taxonomy-export/pom.xml taxonomy-export/pom.xml
COPY taxonomy-app/pom.xml taxonomy-app/pom.xml
# Pre-fetch dependencies so they are cached in a separate layer
RUN mvn -q dependency:go-offline -B
COPY taxonomy-domain/src taxonomy-domain/src
COPY taxonomy-dsl/src taxonomy-dsl/src
COPY taxonomy-export/src taxonomy-export/src
COPY taxonomy-app/src taxonomy-app/src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/taxonomy-app/target/taxonomy-app-*.jar app.jar
RUN mkdir -p /app/data
# Port 8080 is for INTERNAL communication only (e.g. Caddy reverse proxy inside Docker network).
# NEVER publish this port to the internet. Use docker-compose.prod.yml for HTTPS on port 443.
EXPOSE 8080
# -XX:+UseSerialGC        : lower GC memory overhead than G1 for small (≤1 GB) heaps
# -Xss512k                : reduce per-thread stack size (default 1 MB is wasteful on constrained hosts)
# -XX:MaxRAMPercentage=50 : auto-size heap to 50 % of the container's memory limit; on Render's free
#                           tier (512 MB) this gives ~256 MB, but -Xmx220m takes precedence as the
#                           smaller (stricter) value, leaving ~292 MB for off-heap (Lucene mmap, metaspace, OS)
# -Xmx220m                : hard heap cap for 512 MB containers; prevents OOM kills on Render free tier
# Override via JAVA_OPTS env var (e.g. in render.yaml or docker run -e JAVA_OPTS=...) without
# rebuilding the image.
ENV JAVA_OPTS="-XX:+UseSerialGC -Xss512k -XX:MaxRAMPercentage=50.0 -Xmx220m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
