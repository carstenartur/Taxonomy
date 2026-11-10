# Selected graph queries and evidence

Graph exploration reads the existing canonical relation projection and the DSL at
one selected commit. It does not use a parallel graph store or silently substitute
the primary repository, catalogue labels or unscoped relationship rows.

## Selection

All `/api/graph/*` endpoints require the following query selection:

| Field | Meaning |
|---|---|
| `repositoryId` | Existing authorized architecture repository |
| `workspaceId` | Explicit workspace; the empty string selects central scope |
| `branch` | Exact selected branch |
| `commitId` | Full 40-character commit identity |

The existing `X-Taxonomy-Workspace-Id` header can supply the workspace selection
instead of the query field. Existing repository/workspace resolution owns access
checks. A read does not create a workspace. The selected commit must still be the
branch head, and the relation projection must be proven ready for that commit.
Historical editor selection does not imply a historical graph projection exists.
Head movement, missing canonical source and unproved projection state fail closed.

The same selection applies to gap, recommendation and APQC inventory requests;
their additional contracts are described in [obligation profiles](obligation-profiles.md).

## Response evidence

`evidence` contains the repository, workspace identity/scope, branch, commit,
read authority and readiness state. It also records:

- The query policy and requested/effective hop limits.
- Minimum relevance, hop decay, relationship weights and anchor thresholds.
- The number of relationships in the selected projection.
- Completeness and the scope within which an absence statement is meaningful.

The versioned traversal policy is `git-present-weighted-bfs-v1`. Presence in the
selected Git model is distinct from human acceptance of a proposed relationship.
Each traversed edge retains its recorded status, provenance, causation reference,
commit and nullable confidence. The UI presents these as recorded evidence.

Node evidence distinguishes three sources:

| Source | Interpretation |
|---|---|
| `SELECTED_DSL_ELEMENT` | An element declared in the selected canonical revision |
| `SELECTED_RELATION_ENDPOINT` | A referenced endpoint without a corresponding declaration |
| `REQUEST_ANCHOR` | A relevance anchor supplied by the request, without canonical membership |

Only declared elements get their selected DSL title/type. Undeclared anchors and
endpoints remain ID-only; live catalogue labels cannot imply model membership.

`COMPLETE_WITHIN_QUERY_POLICY` means traversal completed within the declared
bounds. It does not establish enterprise completeness. An impact request without
eligible anchors returns `NOT_EVALUATED_NO_ANCHORS`. Empty paths therefore cannot
be treated as universal absence or a successful obligation assessment.

APQC hierarchy returns `{nodes, evidence}` from the same selection. Requirement
correlation in enriched impact uses selected DSL requirements and mappings. With
no relevant correlation evidence, assessment remains inconclusive and `riskScore`
is null. Partial affected-element correlation is `PARTIAL_RECORDED_MAPPINGS` and
also has null risk. `uncorrelatedElementIds` names the affected elements whose
requirement evidence is still open. Unrelated mappings elsewhere in the model do
not establish a zero risk score.

## Browser and API break

The named `AnalysisApi` captures the visible selected saved model and checks any
active lifecycle/working selection for agreement. Suspended overviews cannot
supply a hidden fallback. Graph navigation preserves an explicitly visible saved
working source. On the analysis page, **Use selected saved model** activates that
existing source visibly without changing retained relevance-analysis evidence.
Consumers compare both the returned proof and the still-current selection before
rendering. Live obligation results also bind profile/version and evidence authority;
changing source or assessment settings invalidates them. Labelled saved reports
remain readable as retained evidence. A late response cannot replace a newer selection.

## Pattern detection uses the same source boundary

Both `GET /api/patterns/detect?nodeCode=...` and `POST /api/patterns/detect`
require the explicit repository, workspace, branch and full commit selection.
POST keeps the `{scores, minScore}` body. Pattern traversal uses the declared
element types and accepted relations in one frozen selected snapshot, with a
final head check. Catalogue-wide relationship rows cannot supply a missing link.

`PatternDetectionView.evidence` records the source tuple, canonical authority,
read readiness, template version, minimum relevance and eligible anchor count.
Its completeness is bounded by the named templates: `COMPLETE_WITHIN_PATTERN_TEMPLATES`,
`NOT_EVALUATED_NO_ANCHORS`, or `INCOMPLETE_CANONICAL_EVIDENCE`. Unknown canonical
types and unresolved endpoints remain explicit. A missing chain is not a business
obligation. `patternCoverage` is null when no applicable pattern was evaluated.

The standalone pattern button and live Copilot validate this proof and invalidate
pending and displayed results on source changes. Pure retained Copilot reports
continue to evaluate their original frozen evidence. An ad hoc report without a
selected snapshot cannot infer pattern presence from global catalogue relations.

Unscoped graph requests, the old APQC array response and the old gap/recommendation
DTOs are intentionally replaced. Copilot assessments use schema version `2`.
There is no compatibility adapter for earlier saved Copilot reports; the UI asks
for a new assessment rather than reconstructing missing provenance.

## Verification scope

Java regressions cover branch isolation, readiness, moving heads, frozen labels,
traversal bounds, edge evidence and requirement correlation. Maven-owned browser
contracts cover request selection, contradictory workspace evidence, stale response
fences, profile judgments and the distinction between matches and canonical members.
These tests establish the software contract; they do not measure live-model or
human architecture quality.
