# Saved requirement reformulation offers

A reformulation offer is a separate proposal journal. Creating an offer or saving a
draft does not change requirement text, version history, the active version,
architecture snapshot, or review status. The requirement detail page shows saved
offers with their original text, source version, draft revision and questions.

The initial implementation stores a source-preserving draft. It does not yet call a
model, synthesize wording, decide questions or adopt a requirement version. Source
coverage is explicitly marked as unevaluated. An empty question list does not mean
approval. The detail surface is read-only and supports English and German.

## API

Under `/api/projects/{projectId}/requirements/{requirementId}/reformulations`:

- `POST`: `{ "sourceVersionId": 123, "snapshotId": "...", "language": "de" }`
  creates an offer and its first revision, returning 202 and an ETag.
- `GET`: list offers for the exact selected requirement and workspace scope.
- `GET /{proposalId}`: read the immutable baseline and current draft revision.
- `GET /{proposalId}/revisions/{revision}`: read an exact historical revision.
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
