# Taxonomy observability deployment files

This directory contains the configuration used by the optional OpenTelemetry deployment:

- `javaagent.properties` — privacy-preserving Java-agent and Taxonomy method instrumentation;
- `otel-collector-config.yml` — bounded OTLP receiver, privacy filter and Jaeger exporter.

The runtime image bundles the OpenTelemetry Java agent but does not attach it by default. The normal Taxonomy runtime therefore remains independent of a Collector or trace backend.

Use the repository-root `docker-compose.observability.yml` to start the local stack.

Full operational documentation:

- [English](../docs/en/OBSERVABILITY.md)
- [Deutsch](../docs/de/OBSERVABILITY.md)

## Domain metrics

Taxonomy-owned service boundaries also create Micrometer observations with only fixed, low-cardinality tags. They remain on the existing `/actuator/prometheus` path and use these observation names:

```text
taxonomy.workspace.resolve
taxonomy.repository.route
taxonomy.repository.operation
taxonomy.search
taxonomy.analysis
taxonomy.llm
taxonomy.import
taxonomy.export
```

Every observation uses only:

```text
taxonomy.component=<fixed component>
taxonomy.operation=<fixed method name>
outcome=success|error
```

Do not add arguments, return values, user or workspace identifiers, filenames, taxonomy content, DSL text, prompts, responses or free-form query values as tags or span attributes.
