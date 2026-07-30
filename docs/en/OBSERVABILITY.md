# Observability with OpenTelemetry

Taxonomy supports an **optional, vendor-neutral tracing layer** based on the OpenTelemetry Java agent and OTLP. The normal JVM and Docker startup remain unchanged: the agent is present in the runtime image but is not attached unless an operator explicitly enables it.

The first implementation deliberately keeps the existing metrics path intact:

```text
Taxonomy application
  ├─ Spring Boot Actuator and Micrometer → /actuator/prometheus
  ├─ optional OpenTelemetry Java agent
  │    ├─ HTTP, Spring, Hibernate, JDBC, HikariCP and HTTP-client spans
  │    └─ selected Taxonomy method spans
  └─ OTLP traces
       ↓
OpenTelemetry Collector
  ├─ memory limiting and batching
  ├─ privacy/content filtering
  └─ Jaeger (local example)
```

OpenTelemetry is not a required runtime service and Taxonomy does not depend on Jaeger, Grafana, Tempo or a commercial monitoring vendor.

## What is instrumented

The Java agent automatically covers supported technical boundaries such as:

- incoming Spring MVC requests;
- outgoing HTTP requests, including calls made by supported LLM HTTP clients;
- Hibernate ORM and JDBC operations;
- HikariCP and executor/context propagation;
- exceptions and request outcomes.

`observability/javaagent.properties` also adds coarse internal spans for boundaries that generic framework instrumentation cannot identify reliably:

- workspace resolution and logical repository routing;
- JGit repository reads, commits, diffs, branches and merges;
- DSL parsing and materialisation;
- Hibernate Search queries;
- requirement and child-node analysis;
- LLM orchestration calls;
- framework preview/import;
- Visio, ArchiMate, Mermaid and Structurizr exports.

Method instrumentation records method duration and exceptions. It does **not** capture invocation arguments or return values.

## Local trace stack

Start the application, Collector and Jaeger with:

```bash
docker compose -f docker-compose.observability.yml up --build
```

Then open:

- Taxonomy: `http://localhost:8080`
- Jaeger: `http://localhost:16686`

Select the `taxonomy` service in Jaeger and execute a search, analysis, import, export or DSL operation in Taxonomy. A trace should contain an HTTP root span, framework/database child spans where applicable, and selected Taxonomy method spans.

The local stack publishes only the application and Jaeger UI on loopback. OTLP ports 4317 and 4318 remain inside the Compose network.

Stop the stack with:

```bash
docker compose -f docker-compose.observability.yml down
```

To remove the local Taxonomy database as well:

```bash
docker compose -f docker-compose.observability.yml down --volumes
```

## Normal startup remains unchanged

These commands do not attach the agent:

```bash
./mvnw -pl taxonomy-app -am spring-boot:run
docker build -t taxonomy .
docker run --rm taxonomy
```

The Docker entrypoint intentionally contains no `-javaagent` option. Merely building or running the image does not require a Collector and produces no OTLP traffic.

## Using an external Collector

Attach the bundled agent explicitly and provide an OTLP endpoint:

```bash
docker run --rm \
  -e JAVA_TOOL_OPTIONS=-javaagent:/opt/opentelemetry/opentelemetry-javaagent.jar \
  -e OTEL_JAVAAGENT_CONFIGURATION_FILE=/opt/opentelemetry/javaagent.properties \
  -e SPRING_PROFILES_ACTIVE=hsqldb,observability \
  -e OTEL_SERVICE_NAME=taxonomy \
  -e OTEL_TRACES_EXPORTER=otlp \
  -e OTEL_METRICS_EXPORTER=none \
  -e OTEL_LOGS_EXPORTER=none \
  -e OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=https://collector.example.invalid:4318 \
  taxonomy
```

Use deployment secret storage for OTLP authentication headers or client certificates. Do not commit credentials to Compose files or repository configuration.

For a remote Collector, configure TLS and authentication. The included local Collector deliberately uses an unencrypted connection only inside the private Compose network.

## Sampling

The local example uses full sampling so every exercised workflow is visible. Production deployments should use configurable parent-based ratio sampling, for example:

```text
OTEL_TRACES_SAMPLER=parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG=0.10
```

A value of `0.10` samples approximately ten percent of new root traces while respecting an upstream sampling decision. Sampling reduces trace volume and overhead; it does not change Prometheus metric aggregation.

## Metrics

Taxonomy continues to expose Micrometer metrics at:

```text
/actuator/prometheus
```

The Java-agent example sets:

```text
OTEL_METRICS_EXPORTER=none
```

This avoids creating a second metric pipeline or duplicating Spring Boot, JVM, Hibernate and database-pool metrics. Hibernate statistics are already enabled in the application and available through the existing Micrometer/Prometheus path.

Taxonomy-owned Micrometer observations add bounded domain timers to the same endpoint. Metric names are derived from the observation boundary, for example:

```text
taxonomy_workspace_resolve_seconds_count
```

Only fixed low-cardinality labels are used: `taxonomy.component`, `taxonomy.operation` and the normalized `outcome` (`success` or `error`). Usernames, workspace or repository names, queries, prompts, filenames and exception messages are never metric labels. The live container verification exercises `/api/relations` and requires the workspace-resolution timer with `component=workspace`, `operation=resolveCurrentContext` and `outcome=success`.

## Log correlation

The `observability` Spring profile formats the OpenTelemetry MDC fields as:

```text
[trace_id=<32 hexadecimal characters> span_id=<16 hexadecimal characters>]
```

Use those identifiers to locate the corresponding trace and span. When no span is active, the fields show `none`. Logs remain local/application-managed in this first implementation; OTLP log export is disabled.

Taxonomy can additionally emit one bounded DEBUG message at an observed domain boundary:

```text
Observed taxonomy operation component=workspace operation=resolveCurrentContext outcome=success
```

Enable it only when needed with `LOGGING_LEVEL_COM_TAXONOMY_OBSERVABILITY=DEBUG`. Normal INFO logging is unchanged. The message contains only fixed component/operation names and a normalized outcome; it never includes method arguments, return values, identities, content or exception messages. The live acceptance test requires this line to carry the same `trace_id` as the exported HTTP/domain trace.

## Data minimisation

Telemetry must not contain architecture or user content. The application/agent configuration avoids method argument capture, and the Collector applies a second privacy filter before export.

Forbidden telemetry includes:

- prompts and LLM responses;
- DSL source and generated architecture content;
- taxonomy titles, descriptions or imported cell values;
- workspace and repository names;
- usernames, email addresses, access tokens or roles;
- uploaded filenames and absolute paths;
- request query strings and arbitrary headers;
- SQL bind values.

The Collector removes known generative-AI content attributes, identity fields, URL query fields, Taxonomy content fields, exception messages and exception stack traces. It also limits attribute counts and lengths. Exception type and span error status remain available for operational diagnosis.

Do not add `@SpanAttribute`, request/response-header capture, request-parameter capture or free-form identifiers without a separate privacy and cardinality review.

## Resource and failure controls

The included Collector configuration uses:

- a 128 MiB memory limiter;
- bounded batches;
- a bounded 512-item exporter queue;
- bounded retry duration;
- no persistent queue;
- no publicly exposed ingestion port.

An unreachable Collector must not prevent Taxonomy from starting. The Java agent buffers within bounded limits and reports exporter failures in its own logs. Repeated exporter failures should be fixed rather than hidden by unbounded queues.

## Performance verification

Before enabling tracing for a production environment, compare the same representative workload with and without the agent. Record at least:

- startup duration;
- steady-state memory and CPU;
- p50 and p95 latency for search, analysis and repository operations;
- exported spans per request;
- impact of the intended sampling ratio.

Investigate more than ten percent p95 latency overhead under the representative workload. Reduce span volume or sampling before increasing Collector queues or memory.

## Troubleshooting

### No traces appear

Check that:

1. `JAVA_TOOL_OPTIONS` contains the bundled agent path;
2. `OTEL_TRACES_EXPORTER` is `otlp`;
3. the endpoint uses the correct Collector port and protocol;
4. the Collector configuration loaded without errors;
5. Jaeger lists the `taxonomy` service after a request has been executed.

For temporary diagnostics only, set `OTEL_JAVAAGENT_DEBUG=true`. Agent debug output is verbose and should not remain enabled in normal operation.

### Duplicate spans or metrics

Do not run a separately configured OpenTelemetry SDK/starter alongside the Java agent unless the overlap has been designed explicitly. Keep the agent Micrometer metric bridge and OTLP metric exporter disabled while Prometheus remains the metrics authority.

### Missing domain span after a refactoring

`ObservabilityConfigurationTest` verifies that every method named in `observability/javaagent.properties` still exists. Update the configuration and the documentation when a relevant class or method is renamed.

## Version and supply-chain policy

The runtime image uses a version-pinned OpenTelemetry Java-agent image stage. Collector and Jaeger images also use explicit versions. Upgrade them through a reviewed dependency update and rerun the configuration, container and performance checks. The copied agent is not a Maven runtime dependency, so it must also be considered during container SBOM and vulnerability scanning.
