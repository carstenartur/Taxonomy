# Saved requirement reformulation offers

A reformulation offer is a separate proposal journal. Creating an offer or saving a
draft does not change requirement text, version history, the active version,
architecture snapshot, or review status. The requirement detail page shows saved
offers with their original text, source version, draft revision and questions.

## Synthesis and review boundary

Creating an offer first stores a source-preserving draft and starts an asynchronous
synthesis run through the existing configured model-provider gateway. The initial
202 response describes the stored draft, not a completed synthesis. A run may
complete, fail, or retain a late result for separate review; clients must inspect
the recorded run and current revision rather than assume that 202 means success.

Synthesis walks the selected analysis graph bottom-up. Each wording request receives
the original requirement text and the relevant frozen evidence; parent steps also
receive their children's formulations. Structured output retains statement and
question identities and source references. Invalid or incomplete output is rejected
rather than silently published. A result based on an older proposal revision must
not overwrite intervening manual edits.

The initial draft is explicitly marked as unevaluated. Synthesis can propose wording
and decision questions, but it does not answer those questions for the user or adopt
a new requirement version. An empty question list is not approval. Structural
validation and deterministic playback tests do not establish live-model language
quality or semantic completeness. The detail surface in this package remains
read-only and supports English and German; interactive decisions, recovery,
confirmed adoption and historical export are separate packages.

## API

Under `/api/projects/{projectId}/requirements/{requirementId}/reformulations`:

- `POST`: `{ "sourceVersionId": 123, "snapshotId": "...", "language": "de" }`
  creates an offer and its first revision, starts synthesis, and returns 202 and an
  ETag for that initial revision.
- `GET`: list lightweight offer metadata for the exact selected requirement and
  workspace scope. Each entry contains the offer ID, source version, snapshot,
  creator, creation time and numeric current revision, not frozen evidence or text.
- `GET /{proposalId}`: read the immutable baseline and current draft revision.
  The read-only offer card requests these details when opened.
- `GET /{proposalId}/revisions/{revision}`: read an exact historical revision.
- `POST /{proposalId}/revisions`: `{ "text": "...", "rationale": "..." }` with
  `If-Match: "1"` appends a draft revision. Missing If-Match is 428; stale is 412.
- `POST /{proposalId}/synthesis-runs` with the current quoted `If-Match` revision
  starts another synthesis run and returns 202 with the run record.
- `GET /{proposalId}/synthesis-runs`: read the recorded runs and their outcomes.

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
checkpoint is created when a draft is saved. The unevaluated placeholder finding
does not duplicate the original text in each revision; the exact source remains
available in the immutable baseline.
