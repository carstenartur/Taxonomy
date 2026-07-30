# OpenTelemetry performance verification

This document defines the reproducible performance evidence for the optional OpenTelemetry Java agent. The German operational summary follows the English procedure.

## Scope

The harness builds the application test image **before any timing starts**, performs one unmeasured application lifecycle to prime Docker filesystem/page caches, and then starts the same packaged Taxonomy application JAR in three otherwise identical pinned runtime containers:

1. **baseline** — no Java agent is attached;
2. **agent-always-on** — the bundled agent exports every sampled trace to the test Collector;
3. **agent-sampled-10-percent** — the agent uses `parentbased_traceidratio` with `0.10`.

The eager image build is essential: without it, the first baseline start would include Testcontainers' one-off Docker build while later agent modes reused the finished image. The unmeasured lifecycle similarly prevents a first-ever container start from being mistaken for application or agent overhead.

All measured modes use the HSQLDB and observability Spring profiles, mock LLM operation, disabled embeddings, the same HTTP client, the same `/api/relations` workload and the same host. The OpenTelemetry Collector configuration is the privacy-filtered integration-test configuration already used by `ObservabilityContainerIT`.

The report records:

- application startup duration until `/actuator/health` is ready, excluding the Docker image build;
- cgroup memory peak;
- cgroup CPU consumption per measured request;
- HTTP p50 and p95 latency;
- exported spans per request;
- the difference between no sampling, full sampling and 10-percent sampling;
- an explicit flag proving image build and runtime prewarming happened before timing.

## Run locally

Docker must be available. Execute from any repository directory:

```bash
bash .github/scripts/run-observability-performance.sh
```

The script is Maven-owned and runs only the relevant observability unit tests plus `ObservabilityPerformanceIT`. The integration test is guarded by the system property `taxonomy.observability.performance.enabled`; therefore ordinary `./mvnw verify` and release builds discover but skip the expensive multi-container measurement.

Outputs:

```text
target/observability-performance/report.json
target/observability-performance/report.md
```

The JSON schema version is included in the report so later tooling can reject incompatible evidence. The Markdown report is also printed in the Maven log so the measured values remain reviewable even if artifact publication fails.

## Repetition and workload size

The default workload uses 12 warm-up requests and 80 measured requests per mode. Override it without changing source code:

```bash
TAXONOMY_OBSERVABILITY_PERFORMANCE_WARMUP_REQUESTS=20 \
TAXONOMY_OBSERVABILITY_PERFORMANCE_MEASURED_REQUESTS=200 \
bash .github/scripts/run-observability-performance.sh
```

For release decisions, repeat the run on the intended production CPU architecture and container runtime. CI evidence is a regression signal, not a substitute for capacity testing with representative search, analysis, repository and export workloads.

## Budgets

Two levels are deliberately separated:

- **investigation threshold:** more than 10 percent always-on p95 overhead is reported as `investigationRequired=true`;
- **hard regression ceiling:** CI fails only when the relative increase is also operationally material, avoiding a false failure caused by a one-millisecond baseline.

Default hard ceilings are:

- p95: more than 100 percent **and** more than 30 ms absolute increase;
- startup: more than 100 percent **and** more than 30 s absolute increase;
- memory: more than 256 MiB peak increase.

Override the hard ceilings with the documented system properties when a deployment has stricter budgets. Set `TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE=false` to collect evidence without enforcing the hard ceiling; CI uses enforcement.

A result above the 10-percent investigation threshold must be reviewed even when it remains below the hard ceiling. Prefer reducing span volume, instrumentation scope or sampling ratio before increasing Collector queues or memory.

## CI behavior

The canonical `.github/workflows/ci-cd.yml` remains the single workflow authority. It detects changes to observability runtime configuration, the performance harness, its runner or this document and then executes the targeted Maven command before the normal canonical verification. The generated JSON and Markdown are copied into the existing `quality-reports` artifact; no separate workflow is introduced.

## Interpretation cautions

- Container start time includes container creation and JVM startup, but not application-image construction or the unmeasured cache-priming lifecycle.
- cgroup memory peak covers startup and workload execution, which is intentional for deployment sizing.
- exported span counts include all spans produced by the measured HTTP requests, not only HTTP root spans.
- the 10-percent sampler is probabilistic; compare span volume over the complete request set rather than expecting exactly eight sampled requests out of eighty.
- negative overhead is possible due to host noise and does not prove that instrumentation improves performance.
- CI runners are noisy. A result close to a budget should be repeated before changing instrumentation or policy.

---

# Deutsche Betriebszusammenfassung

Der Test baut das Anwendungs-Image zuerst **außerhalb jeder Zeitmessung**, startet die Anwendung einmal ungemessen zum Vorwärmen der Docker-Dateisystem-/Seitencaches und misst danach dasselbe gepackte Taxonomy-JAR in drei Konfigurationen: ohne Agent, mit vollständigem Sampling und mit zehn Prozent Sampling. Er misst Startzeit, cgroup-Speicherspitze, CPU je Anfrage, p50/p95 sowie exportierte Spans je Anfrage.

Diese Reihenfolge verhindert, dass der einmalige Docker-Build fälschlich der Baseline zugerechnet wird und die Agent-Varianten dadurch künstlich günstiger aussehen.

Ausführung:

```bash
bash .github/scripts/run-observability-performance.sh
```

Ergebnisse:

```text
target/observability-performance/report.json
target/observability-performance/report.md
```

Mehr als zehn Prozent p95-Overhead wird immer als untersuchungsbedürftig markiert. Der harte CI-Grenzwert greift erst bei zugleich relativ und absolut erheblicher Verschlechterung: standardmäßig über 100 Prozent und über 30 ms p95-Zuwachs, über 100 Prozent und über 30 Sekunden zusätzliche Startzeit oder über 256 MiB zusätzliche Speicherspitze.

Die CI verwendet weiterhin ausschließlich den kanonischen Maven-Workflow. Bei Änderungen am Observability-Bereich wird der gezielte Vergleich zusätzlich ausgeführt und der Bericht in das bestehende `quality-reports`-Artefakt aufgenommen.
