# Saved requirement reformulation offers

A reformulation offer is a separate proposal journal. Creating an offer or saving a
draft does not change requirement text, version history, the active version,
architecture snapshot, or review status. The requirement detail page shows saved
offers with their original text, source version, draft revision and questions.

Creating an offer stores a source-preserving draft and queues formulation through
the existing bounded executor. Phase A freezes independent taxonomy drafts; Phase B
reviews directed boundaries, shared information and cross-cutting constraints in
at most two rounds, rewording affected sections and their ancestor summaries.
Stored proposal text includes process/capability-led headings, parent summaries,
labelled statement provenance and a visible verbatim original remainder. An empty
question list or a completed run never means adoption or expert approval.

## API

Under `/api/projects/{projectId}/requirements/{requirementId}/reformulations`:

- `POST`: `{ "sourceVersionId": 123, "snapshotId": "...", "language": "de" }`
  creates an offer and its first revision, returning 202 and an ETag.
- `GET`: list offers for the exact selected requirement and workspace scope.
- `GET /{proposalId}`: read the immutable baseline and current draft revision.
- `GET /{proposalId}/revisions/{revision}`: read an exact historical revision.
- `POST /{proposalId}/synthesis-runs` with `If-Match` queues a new run.
- `GET /{proposalId}/synthesis-runs` reads persisted status and candidate evidence.
- `POST /{proposalId}/revisions`: `{ "text": "...", "rationale": "..." }` with
  `If-Match: "1"` appends a draft revision. Missing If-Match is 428; stale is 412.

The source version must belong to the selected requirement. The existing analysis
snapshot must reference that exact version in the same repository, workspace,
branch and project. Explicitly selecting an older version is supported and does not
switch the active version. Actor and tenant scope are resolved from authentication
and workspace context, never accepted from JSON.

## Frozen evidence

The baseline contains the exact source text and SHA-256, source version, snapshot
analysis payload and content captured for the offer: snapshot catalogue tree,
direct/propagated mappings, directed relationships with their review evidence,
project metadata, source reference, current workspace DSL and semantic revision,
and current prompt template contents. Snapshot content is retained separately from
current workspace and prompt context; current context does not retroactively claim
to describe the old analysis. Dirty/conflict state is explicitly `UNKNOWN` because
the read-only workspace contract does not expose authoritative values.

Proposals and immutable revisions persist in the application database independently
of Git. The proposal has only a proposal-revision pointer. Composite foreign keys
bind its source version and snapshot to the same requirement and tenant. No Git
checkpoint is created when a draft is saved.

## Reconciliation contracts

Question merging requires the exact subject, dimension and scope plus compatible
answer kinds/options/unit/range. Similar wording alone never merges scopes or
answered decisions. Existing canonical IDs take precedence; aliases and immutable
origin records preserve all discoveries, dependencies and original human-answer
references. Conflicting answers stay `CONFLICT` and are visible in the text.

A question explicitly settled by the original is `ANSWERED` with typed
`sourceResolutions` (values, exact source spans and rationale). It creates no human
`DecisionAnswer`. Evidence origin and lifecycle state remain independent. Repeated
model origins do not become independent confirmation. Source entailment and model
additions remain reviewable; mechanical coverage checks cannot certify semantics.

Each run persists the exact Phase B prompt and schema instructions in
`reconcileContext` before Phase A starts, including offers created before Phase B
existed. Versions are `reformulation-reconcile-v1` / `reconcile-response-v1`, with
schema instructions in `prompts/reformulation-reconcile.txt`. Calls receive the
FULL exact original and only scoped frozen node/mapping/edge data; live workspace
DSL and archive-only snapshots never enter formulation prompts. One schema repair
per call is bounded separately from the maximum two semantic rounds.

The persisted candidate document carries its immutable Phase A node results,
round count and affected section IDs in `reconciliation`. Publication checks all
references and verified question lineage while keeping previous statement/source
and human-answer evidence unchanged. Invalid candidates remain `PARTIAL`; late
results and human-edited drafts remain protected. Original requirement/version,
active architecture and review state are never modified by these operations.
