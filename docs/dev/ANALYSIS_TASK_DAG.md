# Analysis task DAG contracts

Status: the opt-in Artemis execution path implements durable root and relation
fan-out, frozen catalogue shards, cross-pod progress/cancellation and provider
concurrency permits for [#1161](https://github.com/carstenartur/Taxonomy/issues/1161).
`ArtemisClusterExecutionConfiguration` binds the production handlers; `local`
remains the compatibility default and neither starts nor requires a broker.
Existing resumable Copilot checkpoint operations retain their continuation path.
Implementation and environment-dependent acceptance evidence are recorded
separately in [the cluster verification record](../testing/analysis-cluster-acceptance.md).

## What exists

Package `com.taxonomy.analysis.dag` (JDK-only, enforced by
`AnalysisDagBoundaryTest`):

| Type | Purpose |
|---|---|
| `TaxonomyShardRoot` | Validated root identity (`BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA` by default). Catalogue membership is still validated by `AnalysisScope.validateRoots`. |
| `AnalysisTaskId` | Deterministic root/preparation identity; schema-2 relation grants additionally carry a bounded ordinal, for example `<operationId>:relation:CP.w000003`. |
| `AnalysisSourceAuthority` | Exact repository/workspace/branch/source commit. Routing/provenance only, never authorization. |
| `RequirementReference` | Project/requirement/snapshot identity plus SHA-256 of the exact requirement text. Messages never carry the text or a prompt. |
| `AnalysisEnvelope` | Schema-versioned header (`schemaVersion`, message/task type, operation/task ids, authority, requirement, roots, attempt, causation/correlation ids, created/deadline). |
| `SubtaxonomyAnalysisTask`, `SubtaxonomyAnalysisCompleted`, `RelationAnalysisTask`, `RelationAnalysisCompleted`, `AnalysisProgressEvent`, `AnalysisCancellationEvent` | The six message contracts. Outcomes are explicit (`COMPLETED`, `PARTIAL`, `SKIPPED`, `FAILED`, `STOPPED`); a missing root is never a zero score. |
| `AnalysisTaskGraph` | Topologically ordered plan: one sub-taxonomy task per selected root and, unless the scope is taxonomies-only, a relation task whose prerequisites are those root tasks. |
| `AnalysisTaskPublisher`, `AnalysisCompletionPublisher`, `AnalysisEventPublisher`, `AnalysisTaskHandler` | Ports for transports. |
| `AnalysisTaskHandlers`, `AnalysisWorkerShards` | Production root/relation bindings and the validated root set a worker consumes (blank = all eight roots). No handler means no consumption. |
| `AnalysisTaskCompletionStore` | Port of the durable, first-writer-wins completion ledger keyed by task id. |
| `AnalysisTransportUnavailableException` | Signals that the broker did not accept a message; the dispatch intent stays recoverable. |

`com.taxonomy.analysis.dag.json.AnalysisMessageCodec` is the versioned JSON
contract: the record is chosen only from the envelope's `messageType`; unknown
schema versions, unknown types/properties, trailing content and payloads above
64 KiB are rejected with a typed `AnalysisMessageFormatException`. No Java
serialization and no class-name metadata are used.

## In-process execution (default, single node)

`InProcessAnalysisOperation` is a thread-bound coordinator. It dispatches ready
tasks in deterministic plan order through `InProcessAnalysisTaskTransport` on the
calling thread, so request-scoped provider selection, `AnalysisRunControl`
cancellation/memory checks and checkpoint sessions behave as before. Completions
are recorded idempotently by task id and a cooperative stop ends dispatching.

- `AnalyzeRequirementUseCase` opens the operation (operation id = the active
  progress run id when present), resolves the exact read authority once and runs
  the relation phase as the `RelationAnalysisTask`.
- `LlmService.analyzeWithBudget` plans the graph and executes each root as a
  `SubtaxonomyAnalysisTask` in the existing priority order. Direct callers
  without a coordinating use case get a process-local ephemeral operation whose
  authority is `WorkspaceContext.SHARED` (the reviewed extra
  `analysis.service -> workspace.service` edge in
  `.github/architecture-dependency-baseline.json`).

Provider call count, prompt text, rate-limit skipping and stop semantics are
unchanged; `AnalysisTaskDagEquivalenceTest` checks this and asserts that no task
metadata reaches a prompt.

## Durable dispatch intent (P03)

Package `com.taxonomy.analysis.dispatch` (JDK + JPA + Spring transactions; no JMS,
no scheduler — enforced by `AnalysisDispatchNoPollingTest`):

| Type | Purpose |
|---|---|
| `AnalysisDispatchIntent` (`analysis_dispatch_intent`) | One row per task id (`id` = SHA-256 of the task id): the encoded task, routing root, status, attempt count and failure kind. |
| `AnalysisTaskCompletionRecord` (`analysis_task_completion`) | Idempotency ledger; the first recorded completion for a task id wins. |
| `AnalysisDispatchStore`, `JpaAnalysisTaskCompletionStore` | Persistence; status changes are compare-and-set updates in their own transaction. |
| `AnalysisDispatchService` | `dispatch(tasks)` records intents in the caller's transaction and publishes **after commit**; `recover(trigger)` and `repair()` republish recoverable intents in bounded, keyset-paginated batches. |

States: `DISPATCH_PENDING` → `DISPATCHED`, or `WAITING_FOR_BROKER` when the broker
does not accept the send, or `DISPATCH_FAILED` when the stored message cannot be
decoded (never retried automatically). Crash windows:

| Crash point | Result |
|---|---|
| before the operation transaction commits | no intent, no message |
| after commit, before the send | intent stays `DISPATCH_PENDING`; the next recovery trigger publishes it |
| after the send, before the acknowledgement update | intent republished by recovery; the worker deduplicates by task id |
| broker unavailable | intent `WAITING_FOR_BROKER`; recovery stops at the first refused send |

Recovery triggers (`AnalysisDispatchRecoveryTrigger`) are exactly: `STARTUP` (the
first broker connection after start), `BROKER_RECONNECT` and `EXPLICIT_REPAIR`
(`POST /api/admin/analysis/dispatch/repair`, ADMIN only; `GET` on the same path
returns the backlog counts). Concurrent triggers are coalesced into one run with
one follow-up. There is **no fixed-rate database polling**. Both tables are
`TRANSIENT` in the backup inventory; PostgreSQL uses migration `V31`.

## Artemis transport (P02, opt-in)

Package `com.taxonomy.composition.analysis.artemis` in `taxonomy-app` is the only
code that uses JMS; it is active only with `taxonomy.analysis.transport.mode=artemis`
(see the configuration reference). Taxonomy never embeds a broker in production.

| Destination (default prefix `taxonomy.analysis`) | Kind | Use |
|---|---|---|
| `subtaxonomy.<ROOT>` | anycast, persistent | sub-taxonomy tasks of one root |
| `relation.<ROOT>` | anycast, persistent | relation tasks targeting one root |
| `relation.general` | anycast, persistent | shared relation preparation; consumed by `all`/`coordinator` roles using frozen input |
| `completion` | anycast, persistent | completion messages |
| `progress`, `control` | multicast, non-persistent | live fan-out only; never replay authority |
| `rejected` | anycast | malformed, unknown-schema and misrouted messages (`taxonomyRejection`, `taxonomyOrigin` properties) |
| `expiry`, `dlq` | anycast | expired/exhausted tasks; coordinators settle durable work explicitly |
| `failed` | anycast | bounded, content-free coordinator diagnostics |

Worker protocol (`ArtemisAnalysisWorker`): receive in a transacted session →
decode and verify family/root → replay a recorded completion, or execute the
handler and record its completion idempotently → send the completion and commit
in the same JMS transaction. Any failure rolls back, so the broker redelivers up
to its `max-delivery-attempts` and then dead-letters the task; other tasks keep
flowing. `_AMQ_DUPL_ID` is set to the task id only as a broker-side optimization.
The client never prefetches (`consumerWindowSize=0`), reconnects indefinitely in
the background, and requires TLS (`sslEnabled=true`) for `tcp://` unless
explicitly disabled. Web readiness remains independent of broker availability;
dedicated worker readiness includes `analysisBroker`.

`ArtemisAnalysisTransportTest` runs these scenarios against a real, disposable
persistent Artemis broker on loopback: competing workers (each task applied once),
crash after the durable record but before acknowledgement (replay without a second
effect), broker restart with queued tasks and reconnect event, rejection of invalid
messages, and poison isolation in the dead-letter queue.

## Durable production execution

`DurableClusterAnalysisExecution` freezes the effective provider, request, exact
workspace source and required catalogue roots before committing admission. The
official catalogue generation is recorded separately from the architecture Git
commit. See [frozen catalogue snapshots](FROZEN_CATALOGUE_SNAPSHOTS.md) and
[frozen ONNX data](FROZEN_ONNX_SHARDS.md) for capture and cache boundaries.

`ClusterAnalysisStore` persists runs, frozen inputs, work results and monotonic
events in four `analysis_cluster_*` tables (PostgreSQL migration `V32`). A short
per-operation write transaction serializes durable transitions. Provider calls
and result rendering run outside that transaction. Completion effects join the
task ledger transaction, and completion redelivery can finish an interrupted
aggregation without repeating a provider request.

Roots execute independently. Relation preparation uses the persisted scored
source cohort once, preserving source ranking, mixed-root prompt batching and
the global call budget. Once that required cohort exists, target grants execute
in deterministic bounded waves; no unrelated target scoring is required.
Budget-constrained prefixes remain serial when parallel grants could change
which relation branches receive the remaining calls. Missing, failed and
stopped work produces explicit partial evidence.

The lifecycle is `QUEUED` → `RUNNING` → optional `RELATIONS` → optional
`FINALIZING` → `COMPLETED`/`PARTIAL`. Cancellation fences every later effect.
`FINALIZING` keeps a result nonterminal until the frozen architecture view is
durable. Broker expiry/dead-letter processing also settles known work, so these
failures do not silently leave an operation running.

Web pods subscribe to live multicast progress/control. SSE clients reconcile
the latest durable revision on connect, reconnect and live hints; duplicate,
reverse and foreign-scope events cannot mutate the view. Saved-run recovery
loads the original authorized request and result without starting another run.
Disconnecting an observer does not cancel accepted work. Workers reconcile
missed terminal state once on broker reconnect, with no periodic database scan.

Cluster result/input/event records are portable backup data. Restoring an
unfinished run preserves committed evidence and marks the work interrupted;
dispatch intents, delivery attempts and provider permits are never restored as
executable work. See [backup records](../architecture/backup-current-records.md).

[Provider permits](../testing/analysis-provider-permits.md) are a separate opt-in
capacity bound. [Application metrics](../testing/analysis-cluster-metrics.md)
use bounded labels and the existing Micrometer export path. External-broker,
worker-set, TLS, NetworkPolicy and HA operation are documented in the
[operations guide](../en/OPERATIONS_GUIDE.md).

### Reviewed package boundaries

The dependency ratchet registers the concrete production wiring without changing
its rules or selectors. The `analysis.cluster` package uses architecture rendering
ports, immutable catalogue snapshots and the existing exact workspace/backup
contracts. It contains application execution and persistence services, distinct
from the JDK-only `analysis.dag` contracts. Shared relation preparation adds the
existing compatibility matrix dependency; admission adds a workspace read port.

`composition.analysis.artemis` binds those services, transport contracts, codecs,
dispatch, provider permits and metrics. JMS remains in this composition package.
Report metadata is applied through `ArchitectureReportMetadataPort.applyTo`, so
cluster execution does not introduce an analysis-to-export dependency cycle.
Every new package edge and class count is explicit in the baseline; no new cycle
exception or generic allowance is added.
