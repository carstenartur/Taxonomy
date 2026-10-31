# syntax=docker/dockerfile:1.7

# ---- build stage ----
# Tag retained for readability and automated update discovery; digest is authoritative.
FROM maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
WORKDIR /workspace

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
COPY taxonomy-reporting-api/pom.xml taxonomy-reporting-api/pom.xml
COPY taxonomy-dsl/pom.xml taxonomy-dsl/pom.xml
COPY taxonomy-export/pom.xml taxonomy-export/pom.xml
COPY taxonomy-extension-api/pom.xml taxonomy-extension-api/pom.xml
COPY taxonomy-extension-runtime/pom.xml taxonomy-extension-runtime/pom.xml
COPY plugins/taxonomy-mermaid-plugin/pom.xml plugins/taxonomy-mermaid-plugin/pom.xml
COPY taxonomy-workspace/pom.xml taxonomy-workspace/pom.xml
COPY taxonomy-templates-api/pom.xml taxonomy-templates-api/pom.xml
COPY taxonomy-templates/pom.xml taxonomy-templates/pom.xml
COPY taxonomy-interop/pom.xml taxonomy-interop/pom.xml
COPY taxonomy-knowledge/pom.xml taxonomy-knowledge/pom.xml
COPY taxonomy-architecture/pom.xml taxonomy-architecture/pom.xml
COPY taxonomy-reporting/pom.xml taxonomy-reporting/pom.xml
COPY taxonomy-analysis/pom.xml taxonomy-analysis/pom.xml
COPY taxonomy-portfolio/pom.xml taxonomy-portfolio/pom.xml
COPY taxonomy-app/pom.xml taxonomy-app/pom.xml
COPY taxonomy-coverage/pom.xml taxonomy-coverage/pom.xml
COPY taxonomy-build/pom.xml taxonomy-build/pom.xml

# Unlike a RUN cache mount, these dependency bytes are part of the exported
# BuildKit layer. Source-only changes can reuse them on a fresh hosted runner.
# Exclude reactor siblings: they are built from this checkout by package below.
# Prefetch is optional; missing/unavailable seed dependencies fall back to the
# real reactor build, which remains the authority for successful packaging.
RUN ./mvnw -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline \
      -DexcludeReactor=true -DexcludeGroupIds=com.taxonomy \
    || echo 'Dependency prefetch incomplete; reactor package will resolve required dependencies.' >&2

# Copy all inputs required by the packaged application. In particular, the app
# Maven module embeds Markdown help, screenshots and legal notices in the JAR.
COPY taxonomy-tooling/src taxonomy-tooling/src
COPY taxonomy-domain/src taxonomy-domain/src
COPY taxonomy-reporting-api/src taxonomy-reporting-api/src
COPY taxonomy-dsl/src taxonomy-dsl/src
COPY taxonomy-export/src taxonomy-export/src
COPY taxonomy-extension-api/src taxonomy-extension-api/src
COPY taxonomy-extension-runtime/src taxonomy-extension-runtime/src
COPY plugins/taxonomy-mermaid-plugin/src plugins/taxonomy-mermaid-plugin/src
COPY taxonomy-workspace/src taxonomy-workspace/src
COPY taxonomy-templates-api/src taxonomy-templates-api/src
COPY taxonomy-templates/src taxonomy-templates/src
COPY taxonomy-interop/src taxonomy-interop/src
COPY taxonomy-knowledge/src taxonomy-knowledge/src
COPY taxonomy-architecture/src taxonomy-architecture/src
COPY taxonomy-reporting/src taxonomy-reporting/src
COPY taxonomy-analysis/src taxonomy-analysis/src
COPY taxonomy-portfolio/src taxonomy-portfolio/src
COPY taxonomy-app/src taxonomy-app/src
COPY docs docs
COPY LICENSE NOTICE THIRD-PARTY-NOTICES.md ./

# Volatile image identity must not invalidate the dependency seed layer.
ARG BUILD_DATE=unknown
ARG VCS_REF=unknown

# The Docker context intentionally excludes .git. Materialize the immutable
# source revision supplied by the delivery workflow so Spring Boot's GitInfoContributor
# and /api/about still report the exact code running in the container.
RUN printf 'git.branch=container\ngit.commit.id=%s\ngit.commit.id.abbrev=%s\ngit.build.time=%s\n' \
      "$VCS_REF" "$VCS_REF" "$BUILD_DATE" \
      > taxonomy-app/src/main/resources/git.properties

# Resolve anything not covered by the seed and always build the actual checkout.
# Keep the repository in the layer, so external exporters retain its bytes.
RUN ./mvnw -q -DskipTests package

# ---- optional OpenTelemetry Java agent stage ----
# Tag retained for automated update discovery; the multi-platform index digest is
# authoritative. The agent is copied into the runtime image but is never attached
# by default. Operators opt in explicitly through JAVA_TOOL_OPTIONS.
FROM otel/autoinstrumentation-java:2.28.1@sha256:41b92978e61d13d4f32c6eb20c6ae7821a73ffdec8539bc6a73858e884b411d8 AS opentelemetry

# ---- optional pinned local model ----
# This stage is independent of the application build, so unchanged model bytes
# remain cacheable across application releases. The default target does not use it.
FROM eclipse-temurin:25-jre-jammy@sha256:f7f4c9bc54b8c1b734ddf49e056b4d158c08a6a234a7604a4b08c36b0c5ad68c AS onnx-model
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY .github/scripts/download-embedding-model.sh /usr/local/bin/download-embedding-model.sh
COPY deploy/container/onnx/ /opt/taxonomy/models/multilingual-minilm/
RUN MODEL_PROFILE=MULTILINGUAL_MINILM_L12 \
      MODEL_DIRECTORY=/opt/taxonomy/models/multilingual-minilm \
      bash /usr/local/bin/download-embedding-model.sh \
    && chmod -R a=rX /opt/taxonomy/models

# ---- runtime stage ----
# Tag retained for readability; digest prevents mutable-tag supply-chain drift.
FROM eclipse-temurin:25-jre-jammy@sha256:f7f4c9bc54b8c1b734ddf49e056b4d158c08a6a234a7604a4b08c36b0c5ad68c AS runtime
# OCI Image Specification labels (https://github.com/opencontainers/image-spec/blob/main/annotations.md)
LABEL org.opencontainers.image.title="Taxonomy Architecture Analyzer" \
      org.opencontainers.image.description="Spring Boot web application that loads a C3-taxonomy catalogue and provides full-text search, KNN vector search, architecture-overlay DSL editing, and LLM-assisted analysis." \
      org.opencontainers.image.url="https://github.com/carstenartur/Taxonomy" \
      org.opencontainers.image.source="https://github.com/carstenartur/Taxonomy" \
      org.opencontainers.image.documentation="https://github.com/carstenartur/Taxonomy#readme" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="Carsten Hammer" \
      org.opencontainers.image.base.name="eclipse-temurin:25-jre-jammy@sha256:f7f4c9bc54b8c1b734ddf49e056b4d158c08a6a234a7604a4b08c36b0c5ad68c"
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
    && apt-get install -y --no-install-recommends curl libssl3 openssl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid "${TAXONOMY_GID}" taxonomy \
    && useradd --uid "${TAXONOMY_UID}" --gid "${TAXONOMY_GID}" \
         --no-create-home --home-dir /app --shell /usr/sbin/nologin taxonomy

WORKDIR /app
RUN mkdir -p /app/data /opt/opentelemetry \
    && chown -R taxonomy:taxonomy /app /opt/opentelemetry
COPY --from=build --chown=taxonomy:taxonomy /workspace/taxonomy-app/target/taxonomy-app-*.jar app.jar
COPY --from=build --chown=taxonomy:taxonomy /workspace/taxonomy-app/target/plugins/ plugins/
COPY --from=build --chown=taxonomy:taxonomy /workspace/taxonomy-app/target/features/ features/
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

# ---- ready-to-use local ONNX variant: docker build --target onnx ----
# Model files are immutable; native libraries are extracted to the writable /tmp
# cache. Explicit CPU selection avoids a first-start CUDA-library download.
FROM runtime AS onnx
COPY --from=onnx-model /opt/taxonomy/models/ /opt/taxonomy/models/
LABEL org.opencontainers.image.description="Taxonomy Architecture Analyzer with bundled multilingual ONNX embeddings for offline semantic search and similarity scoring." \
      org.opencontainers.image.licenses="MIT AND Apache-2.0" \
      com.taxonomy.image.variant="onnx"
ENV LLM_PROVIDER=LOCAL_ONNX \
    TAXONOMY_EMBEDDING_ENABLED=true \
    TAXONOMY_EMBEDDING_MODEL_PROFILE=MULTILINGUAL_MINILM_L12 \
    TAXONOMY_EMBEDDING_MODEL_DIR=/opt/taxonomy/models/multilingual-minilm \
    TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false \
    RUST_FLAVOR=cpu \
    DJL_CACHE_DIR=/tmp/djl-cache \
    JAVA_OPTS="-XX:+UseSerialGC -Xss512k -Xmx768m -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

# Keep ordinary docker build and all existing unsuffixed tags on the standard
# variant. BuildKit skips the independent model stage unless onnx is requested.
FROM runtime AS standard
LABEL com.taxonomy.image.variant="standard"
