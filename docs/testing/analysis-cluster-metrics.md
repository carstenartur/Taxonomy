# Cluster analysis metrics

`ArtemisAnalysisMetrics` measures existing task, coordinator, dispatch and provider-permit callbacks. It introduces no database scan or scheduled polling. Metrics are process observations; the database remains the authority for operation state and the external Artemis broker for pending deliveries.

All names below have the prefix `taxonomy.analysis.`. Timers export durations in seconds through Prometheus. Counters reset on process restart; use rates or increases across scrapes.

| Metric suffix | Type | Meaning |
| --- | --- | --- |
| `task.queue.wait` | Timer | Task envelope creation to preparation start. Includes dispatch delay; a redelivered task retains its original creation time. |
| `task.execution` | Timer | Preparation duration, including provider wait and execution, tagged with the computed outcome. Excludes the durable result callback. |
| `task.outcomes` | Counter | Preparation outcomes: `COMPLETED`, `PARTIAL`, `FAILED`, or `STOPPED`. These are not committed-result counts. |
| `task.running` | Gauge | Preparations currently running in this process. |
| `task.concurrency.peak` | Gauge | Highest simultaneous preparations observed since process start. |
| `task.dispatches` | Counter | Successful or failed broker publication attempts. Recovery can republish an existing task. |
| `relation.dispatched` | Counter | Successful relation publication attempts, including preparation and target work. |
| `operation.events` | Counter | Typed durable progress events observed at after-commit publication. Do not invoke this hook for multicast delivery or replay. |
| `completion.queue.wait` | Timer | Completion envelope creation to coordinator callback start, including result commit and broker transit. |
| `coordination.duration` | Timer | Coordinator callback duration, tagged `COMPLETED` or `FAILED`; includes immediate after-commit dispatch when performed. |
| `completion.to.dispatch` | Timer | Completion creation to successful downstream publication for the same operation on the coordinator thread. Startup/repair publication and terminal callbacks produce no invented sample. |
| `cancellation.observed` | Timer | Live cancellation-control publication to the worker's first cooperative cancellation checkpoint. Negative clock skew is recorded as zero. Reconnect reconciliation has no request timestamp and contributes no sample. |
| `worker.executions` | Function counter | Worker preparations completed, as reported by the transport. |
| `worker.replays` | Function counter | Deliveries answered from the durable completion ledger. |
| `worker.rejected` | Function counter | Malformed, unsupported or misrouted messages rejected by workers. |
| `worker.rollbacks` | Function counter | Worker delivery failures triggering JMS rollback. |
| `coordinator.accepted` | Function counter | Completions with a newly accepted coordinator effect. |
| `coordinator.failures` | Function counter | Coordinator diagnostic-send attempts; this is not a count of failed operations. |
| `coordinator.redeliveries` | Function counter | Coordinator delivery failures triggering rollback. |
| `provider.wait` | Timer | Actual permit acquisition wait, with `ACQUIRED`, `STOPPED`, or `FAILED` outcome. |
| `provider.waiting` | Gauge | Calls currently waiting at the permit port. |
| `provider.inflight` | Gauge | Acquired permits still held by this process. |
| `provider.concurrency.peak` | Gauge | Highest simultaneous held permits observed since process start. |
| `provider.lease` | Timer | Acquisition through permit return, with `RETURNED` or `FAILED` outcome. Return failure still releases the local observation. |

Labels are bounded: `task_type`, the eight catalogue `root` codes plus `GENERAL`/`OTHER`, typed `outcome`/`phase`, provider enum, and explicitly configured `quota_group`. Operation/task IDs, usernames, repository/workspace IDs, prompts, responses, URLs and credentials are never labels. Provider aliases sharing a quota group retain their provider label; sum `provider.inflight` by quota group across pods to observe their combined holders. Gauges are per process, and peak gauges are not a cluster-wide peak history.

## Export and verification

The application includes Micrometer/Prometheus and exposes `/actuator/prometheus` in its default Actuator configuration:

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.prometheus.enabled=true
```

Use the existing Actuator machine-token configuration for authenticated scrapes. Helm's `serviceMonitor.enabled=true` selects coordinator and worker metrics services; `serviceMonitor.authorization` references the distinct `ADMIN_TOKEN` Secret key. Keep that token separate from the interactive login password. External broker queue-depth, consumer, DLQ and expiry metrics require the broker's own exporter.

Prometheus normalizes dots to underscores: for example, `taxonomy_analysis_task_execution_seconds_count` and `_sum`, and `taxonomy_analysis_worker_replays_total`. Histogram buckets require explicit configuration, for example:

```properties
management.metrics.distribution.percentiles-histogram.taxonomy.analysis.task.execution=true
```

`ArtemisAnalysisMetricsTest` uses `SimpleMeterRegistry` and a controlled clock to verify durations, nested concurrency, failure cleanup, changing transport counters, cancellation clock skew, and bounded labels. Run it with the application's selected Maven reactor tests. These tests validate instrumentation; broker delivery and durable-state acceptance remain separate integration tests.
