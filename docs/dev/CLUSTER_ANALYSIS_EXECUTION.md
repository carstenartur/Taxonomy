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

The #808 question-continuation protocol now has an explicit distributed binding.
`AnalysisContinuationService.beginDistributed` freezes request, catalogue, provider
and scoring/relation policy identity, then binds the durable claim to the admitted
cluster operation. Actual workers reconstruct checkpoint sessions from that
binding and their exact task grant. Saved validated question answers replay without
HTTP. A worker interrupted after durable question START leaves an uncertain window;
redelivery does not blindly issue another provider request. The scoped owner must
submit an explicit RETRY or SKIP decision with the current recovery version.
Operation ownership is checked atomically under the continuation lock at attach,
prepare and finish. Cancellation fences late results. Closing the HTTP/SSE admission
scope leaves an attached distributed claim alive; losing its observer is not
cancellation. The local checkpoint path remains available for local execution.

The optional OpenAI Responses token contract is described in the
[operator guide](../en/OPERATIONS_GUIDE.md#supported-openai-responses-tpm-adapter).
Its immutable snapshot/schema/input bound/output cap enter provider and recovery
fingerprints. Count and generation are separately admitted physical operations;
Count always retains UNKNOWN usage and never becomes a semantic checkpoint answer.
