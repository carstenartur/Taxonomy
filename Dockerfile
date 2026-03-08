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
# -XX:+UseSerialGC    : lower GC memory overhead than G1 for small (≤1 GB) heaps
# -Xss512k            : reduce per-thread stack size (default 1 MB is wasteful on constrained hosts)
# -XX:MaxRAMPercentage: auto-size heap to 65 % of the container's memory limit; the remaining
#                       headroom is needed for off-heap/native memory (Lucene mmap, ONNX Runtime)
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xss512k", "-XX:MaxRAMPercentage=65.0", "-jar", "app.jar"]
