# syntax=docker/dockerfile:1.7

# ---- build stage ----
# Tag retained for readability and automated update discovery; digest is authoritative.
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /workspace
ARG BUILD_DATE=unknown
ARG VCS_REF=unknown

# The Maven Wrapper validates the ZIP distribution. The minimal Maven builder
# image does not provide unzip, and mvnw would otherwise silently switch to the
# tar.gz archive while retaining the ZIP checksum.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

# The checked-in wrapper is the single Maven authority for contributors, CI and
# container builds. The builder image supplies only Java and bootstrap utilities.
COPY mvnw .
COPY .mvn/wrapper .mvn/wrapper
RUN chmod +x mvnw

# Copy the reactor descriptors first so dependency downloads remain cacheable.
COPY pom.xml .
COPY taxonomy-tooling/pom.xml taxonomy-tooling/pom.xml
COPY taxonomy-domain/pom.xml taxonomy-domain/pom.xml
COPY taxonomy-dsl/pom.xml taxonomy-dsl/pom.xml
COPY taxonomy-export/pom.xml taxonomy-export/pom.xml
COPY taxonomy-extension-api/pom.xml taxonomy-extension-api/pom.xml
COPY taxonomy-app/pom.xml taxonomy-app/pom.xml
COPY taxonomy-coverage/pom.xml taxonomy-coverage/pom.xml
COPY taxonomy-build/pom.xml taxonomy-build/pom.xml

# Copy all inputs required by the packaged application and every main-source
# reactor module. In particular, the app Maven module embeds Markdown help,
# screenshots and legal notices in the JAR.
COPY taxonomy-tooling/src taxonomy-tooling/src
COPY taxonomy-domain/src taxonomy-domain/src
COPY taxonomy-dsl/src taxonomy-dsl/src
COPY taxonomy-export/src taxonomy-export/src
COPY taxonomy-extension-api/src taxonomy-extension-api/src
COPY taxonomy-app/src taxonomy-app/src
COPY docs docs
COPY LICENSE NOTICE THIRD-PARTY-NOTICES.md ./

# The Docker context intentionally excludes .git. Materialize the immutable
# source revision supplied by the delivery workflow so Spring Boot's GitInfoContributor
# and /api/about still report the exact code running in the container.
RUN printf 'git.branch=container\ngit.commit.id=%s\ngit.commit.id.abbrev=%s\ngit.build.time=%s\n' \
      "$VCS_REF" "$VCS_REF" "$BUILD_DATE" \
      > taxonomy-app/src/main/resources/git.properties

# Do not run dependency:go-offline against this multi-module reactor before its
# internal SNAPSHOT artifacts exist. A single reactor package resolves and builds
# sibling modules correctly while the BuildKit Maven cache retains all downloads.
RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw -q -DskipTests package

# ---- optional OpenTelemetry Java agent stage ----
# Tag retained for automated update discovery; the multi-platform index digest is
# authoritative. The agent is copied into the runtime image but is never attached
# by default. Operators opt in explicitly through JAVA_TOOL_OPTIONS.
FROM otel/autoinstrumentation-java:2.28.1@sha256:41b92978e61d13d4f32c6eb20c6ae7821a73ffdec8539bc6a73858e884b411d8 AS opentelemetry

# ---- runtime stage ----
# Tag retained for readability; digest prevents mutable-tag supply-chain drift.
FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13
# OCI Image Specification labels (https://github.com/opencontainers/image-spec/blob/main/annotations.md)
LABEL org.opencontainers.image.title="Taxonomy Architecture Analyzer" \
      org.opencontainers.image.description="Spring Boot web application that loads a C3-taxonomy catalogue and provides full-text search, KNN vector search, architecture-overlay DSL editing, and LLM-assisted analysis." \
      org.opencontainers.image.url="https://github.com/carstenartur/Taxonomy" \
      org.opencontainers.image.source="https://github.com/carstenartur/Taxonomy" \
      org.opencontainers.image.documentation="https://github.com/carstenartur/Taxonomy#readme" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="Carsten Hammer" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13"
ARG BUILD_DATE
ARG VCS_REF
ARG VERSION
LABEL org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.version="${VERSION}"

# A fixed numeric identity provides a secure portable default. Image paths are
# also root-group readable so OpenShift can inject an arbitrary non-root UID;
# only the explicit data directory becomes group-writable.
ARG TAXONOMY_UID=10001
ARG TAXONOMY_GID=10001
# curl is used only by the container-native healthcheck. The application itself
# remains reachable exclusively through the reverse proxy in production.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid "${TAXONOMY_GID}" taxonomy \
    && useradd --uid "${TAXONOMY_UID}" --gid "${TAXONOMY_GID}" \
         --no-create-home --home-dir /app --shell /usr/sbin/nologin taxonomy

WORKDIR /app
RUN mkdir -p /app/data /opt/opentelemetry \
    && chown -R taxonomy:taxonomy /app /opt/opentelemetry
COPY --from=build --chown=taxonomy:taxonomy /workspace/taxonomy-app/target/taxonomy-app-*.jar app.jar
COPY --from=opentelemetry --chown=taxonomy:taxonomy /javaagent.jar /opt/opentelemetry/opentelemetry-javaagent.jar
COPY --chown=taxonomy:taxonomy observability/javaagent.properties /opt/opentelemetry/javaagent.properties
# OpenShift runs arbitrary UIDs with root-group membership. Preserve the
# read-only modes of application code and the agent while granting group write
# only to the explicit data directory. /tmp is supplied as a writable volume.
RUN chgrp -R 0 /app /opt/opentelemetry \
    && chmod -R g=u /app/data

# Port 8080 is for INTERNAL communication only (e.g. Caddy or a Kubernetes Service).
# NEVER publish this port directly to the internet. Terminate TLS at a trusted proxy.
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
  CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1

# -XX:+UseSerialGC        : lower GC memory overhead than G1 for small (≤1 GB) heaps
# -Xss512k                : reduce per-thread stack size (default 1 MB is wasteful on constrained hosts)
# -XX:MaxRAMPercentage=50 : auto-size heap to 50 % of the container's memory limit; on Render's free
#                           tier (512 MB) this gives ~256 MB, but -Xmx220m takes precedence as the
#                           smaller (stricter) value, leaving ~292 MB for off-heap (Lucene mmap, metaspace, OS)
# -Xmx220m                : hard heap cap for 512 MB containers; prevents OOM kills on Render free tier
# -XX:+ExitOnOutOfMemoryError: terminate deterministically so the orchestrator can restart the pod
# -Djava.io.tmpdir=/tmp   : keep temporary writes on the explicitly writable Kubernetes emptyDir
# Override via JAVA_OPTS env var without rebuilding the image. The Helm chart uses
# percentage-based sizing appropriate for its larger default memory limit.
ENV HOME=/tmp \
    JAVA_OPTS="-XX:+UseSerialGC -Xss512k -XX:MaxRAMPercentage=50.0 -Xmx220m -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
USER 10001:10001
STOPSIGNAL SIGTERM
# exec replaces the shell with the JVM so Java becomes PID 1 and receives
# Docker's SIGTERM directly. This allows Spring, HikariCP, HSQLDB and Lucene to
# close cleanly before a replacement container opens the persisted data again.
# The OpenTelemetry agent is deliberately absent from this command. It is only
# attached when an operator sets JAVA_TOOL_OPTIONS=-javaagent:/opt/opentelemetry/opentelemetry-javaagent.jar.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
