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

Architecture requests remain in `FINALIZING` until their view and server-owned
Copilot reports are persisted. The
completion consumer may redeliver this stage safely. Existing relation evidence
uses the evidence projector. With hierarchical relation search disabled, the
existing score-only generator uses effective scores and compatibility rules,
without issuing relation LLM calls. Its provisional hypotheses remain explicitly
unverified and render as `SUGGESTED_CANDIDATE` edges between selected frozen nodes.
Results without relationship evidence use a bounded hierarchy-only view. Global
relation propagation is never used. Deterministic rendering failures finish with explicit
`ARCHITECTURE_VIEW_INCOMPLETE` partial evidence; database failures remain retryable.

## Server-owned Copilot reports and browser drafts

The finalizer derives `AnalysisResult.copilot` from the retained result before
publishing terminal completion. The local resumable POST and portfolio snapshots
use the same report computation. It performs no additional provider requests and
does not read a newer global catalogue or embedding index. A report's processing
state is distinct from architectural completeness: unsearched or pruned relation
evidence cannot prove a missing relationship. Restricted scopes and partial input
have explicit skipped/partial states. Historical results may have no Copilot field.

Verified pattern paths preserve source and decision conditions, necessity and
alternative groups. Conditional paths are not unconditional completeness evidence.
Continuation completion reconciles the report after canonical result, coverage and
scope reconciliation, including cancellation fallback. Relation-extension assembly
clears an inherited report until the extended result has been finalized.

The managed browser flow renders this stored payload instead of chaining separate
gap, pattern and recommendation POSTs. The existing completion redelivery and
cancellation fences cover the reports together with the architecture. A failed
database write remains retryable; no successful browser draft PUT is required to
finish a server operation.

Working drafts with reconstructible server evidence store an `analysisReference`
and editable input/view/options instead of serializing another complete copy of
scores, reasons, architecture and relation reports. Restoration reads the owned
continuation or durable operation through the existing scoped APIs. It retains
edited draft text, observes load/reset/workspace guards and reports unavailable
references explicitly. Legacy and manually changed results retain their full
draft representation; an arbitrary local operation ID is not durable authority.
Late reads also fence manual score, reason and view edits. The unchanged draft
size limit can still reject large manual or historical full representations.

Artemis remains opt-in (`TAXONOMY_ANALYSIS_TRANSPORT_MODE=artemis`). This integration
does not turn the local process registry or legacy SSE endpoint into a restart-safe
distributed queue, and does not claim a new production HA verification result.

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
