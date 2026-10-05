# Durable analysis execution

The optional `ClusterAnalysisExecution` port routes ordinary requirement analyses
through the durable coordinator when Artemis mode supplies its bean. The HTTP
adapter captures the authorized workspace, requirement identity, operation ID and
view context before leaving the request thread. Admission resolves the effective
provider once, stores its name without credentials, freezes the catalogue input,
and rejects a branch or source commit that moved before admission.

Scoring tasks persist and load only their selected root. Relation searches and
architecture finalization also retain the eight target roots without adding
scoring tasks for those roots. An operation's architecture Git authority and the
shared official catalogue's recorded provenance are separate identities: assigning
an operation to a Git commit does not reconstruct a historical catalogue version.

`FrozenClusterAnalysisComputation` binds one saved root, the saved provider and
worker cancellation/memory controls around the existing `LlmService` scoring API.
Scoring runs outside database transactions. It cannot fall back to a global
catalogue or index when input is absent. `MOCK` is a scoped provider choice even
when the worker's default provider is live. `LOCAL_ONNX` admission atomically
captures all eight roots to resolve cross-root relation endpoint names. It requires
a ready scoped relation projection at the exact architecture commit, freezes the
enriched texts and actual model artifact/configuration identity, and persists only
the required roots. The worker validates that evidence and its model before scoring
from its bound root. Missing projection evidence, missing worker support or a model
mismatch fails closed. No global embedding index is used. Relation generation
remains explicitly `GENERATION_UNSUPPORTED` for this embedding-only provider.

Observation subscribes before admission, reads the durable initial snapshot and
then reads again only after matching progress events or reconnect notifications.
It does not poll the database. A disconnected HTTP/SSE observer detaches; explicit
cancellation remains a separate authorized operation. SSE serializes the stored
result and revision directly, including frozen score semantics and architecture,
without using the local mapper's current-catalogue cache.

Architecture requests remain in `FINALIZING` until their view is persisted. The
completion consumer may redeliver this stage safely. Existing relation evidence
uses the evidence projector. With hierarchical relation search disabled, the
existing score-only generator uses effective scores and compatibility rules,
without issuing relation LLM calls. Its provisional hypotheses remain explicitly
unverified and render as `SUGGESTED_CANDIDATE` edges between selected frozen nodes.
Results without relationship evidence use a bounded hierarchy-only view. Global
relation propagation is never used. Deterministic rendering failures finish with explicit
`ARCHITECTURE_VIEW_INCOMPLETE` partial evidence; database failures remain retryable.

The existing #808 question-continuation protocol is an explicit compatibility
boundary. Requests with `resumable=true` continue through
`AnalysisContinuationService`, including START, decisions, replay and cancellation.
An active `AnalysisCheckpointSession` keeps `AnalyzeRequirementUseCase` on that
existing implementation. Ordinary requests and SSE use the cluster path when it
is configured. This choice is made before execution and is not a fallback after a
cluster failure. With no cluster bean, all existing local routes are unchanged.
