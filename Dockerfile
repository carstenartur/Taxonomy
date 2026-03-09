# ---- build stage ----
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven
COPY pom.xml .
# Pre-fetch dependencies so they are cached in a separate layer
RUN mvn -q dependency:go-offline -B
COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/taxonomy-*.jar app.jar
RUN mkdir -p /app/data
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
