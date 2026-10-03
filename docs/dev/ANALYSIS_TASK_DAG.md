# Analysis task DAG contracts

Status: **P01 of [#1161](https://github.com/carstenartur/Taxonomy/issues/1161)
implemented** — transport-neutral contracts plus the in-process adapter. The
broker transport, durable dispatch intent, cluster fan-out, data sharding,
relation fan-out, live event bridge, provider permits and deployment packages
(P02–P10) are separate follow-up changes; nothing here starts or requires a broker.

## What exists

Package `com.taxonomy.analysis.dag` (JDK-only, enforced by
`AnalysisDagBoundaryTest`):

| Type | Purpose |
|---|---|
| `TaxonomyShardRoot` | Validated root identity (`BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA` by default). Catalogue membership is still validated by `AnalysisScope.validateRoots`. |
| `AnalysisTaskId` | Deterministic task identity: `<operationId>:subtaxonomy:<root>` or `<operationId>:relation:<sorted roots>` (`*` = the operation's complete scored evidence). |
| `AnalysisSourceAuthority` | Exact repository/workspace/branch/source commit. Routing/provenance only, never authorization. |
| `RequirementReference` | Project/requirement/snapshot identity plus SHA-256 of the exact requirement text. Messages never carry the text or a prompt. |
| `AnalysisEnvelope` | Schema-versioned header (`schemaVersion`, message/task type, operation/task ids, authority, requirement, roots, attempt, causation/correlation ids, created/deadline). |
| `SubtaxonomyAnalysisTask`, `SubtaxonomyAnalysisCompleted`, `RelationAnalysisTask`, `RelationAnalysisCompleted`, `AnalysisProgressEvent`, `AnalysisCancellationEvent` | The six message contracts. Outcomes are explicit (`COMPLETED`, `PARTIAL`, `SKIPPED`, `FAILED`, `STOPPED`); a missing root is never a zero score. |
| `AnalysisTaskGraph` | Topologically ordered plan: one sub-taxonomy task per selected root and, unless the scope is taxonomies-only, a relation task whose prerequisites are those root tasks. |
| `AnalysisTaskPublisher`, `AnalysisCompletionPublisher`, `AnalysisEventPublisher`, `AnalysisTaskHandler` | Ports for later transports. |

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
  without a coordinating use case get a process-local ephemeral operation.

Provider call count, prompt text, rate-limit skipping and stop semantics are
unchanged; `AnalysisTaskDagEquivalenceTest` checks this and asserts that no task
metadata reaches a prompt.
