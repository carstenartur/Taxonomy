# ADR 0001: Project–Requirement–Solution–Product Portfolio

- **Status:** Accepted
- **Date:** 2026-08-02
- **Decision owners:** Taxonomy maintainers
- **Related issue:** [#546](https://github.com/carstenartur/Taxonomy/issues/546)

## Context

Taxonomy already transformed one free-text business requirement into scored taxonomy nodes, an architecture view, relation hypotheses, gap analysis, patterns, recommendations and exports. That workflow was suitable for an ad-hoc analysis, but it did not preserve a stable business identity for the requirement and could not answer project-level questions such as:

- Which exact taxonomy elements are required by `REQ-001`, `REQ-002` and `REQ-003` separately?
- Which solution is reused by several requirements?
- Which relevant element already exists, must change, must be created or should be procured?
- Which concrete, sourced product version is a candidate for a solution?
- Which statements are AI suggestions and which have been reviewed by a person?
- Can a historical result still be interpreted after taxonomy, prompt, provider or relation changes?

The document import UI previously combined selected candidates into one `businessText`. Analysis scores and architecture views were request-local. Coverage could be recorded manually after an analysis, but there was no immutable project analysis history or project aggregation.

## Decision

### 1. Workspace and project boundaries

A workspace can contain multiple projects. A project is the business boundary for requirements, analysis snapshots, project solutions, product candidates, conflicts and reporting.

Reusable **solution definitions** and **product catalogue entries** are workspace-scoped rather than project-scoped. Their project-specific selection and status are represented by separate entities.

All repository reads are constrained by a normalized `scopeKey` derived from the request-bound `WorkspaceContext`. Services do not resolve the current workspace implicitly.

### 2. Stable requirements and immutable text versions

`ProjectRequirement` is a stable business identity with a project-unique key such as `REQ-001`.

Changing requirement text never overwrites history. It creates a `ProjectRequirementVersion` with:

- monotonically increasing version number,
- SHA-256 content hash,
- author, timestamp and change reason,
- source artifact/version/fragment IDs,
- section, page and original source text.

Submitting an identical text selects the existing version instead of creating a duplicate.

### 3. Independent analyses

Each requirement version is submitted separately to the existing `AnalyzeRequirementUseCase`. The portfolio does not introduce a competing scoring pipeline.

A batch consists of a persisted `RequirementAnalysisJob` and one `RequirementAnalysisJobItem` per requirement. A failed item does not abort the remaining requirements. Failed items can be retried independently against the requirement's current version.

Long-running LLM calls execute outside persistence transactions. Job and item state are committed before and after each call.

### 4. Immutable and reproducible snapshots

Every successful or partial item creates an immutable `RequirementAnalysisSnapshot`. The snapshot stores:

- the exact requirement version,
- the complete typed `AnalysisResult`,
- architecture view, gap analysis, pattern detection and recommendation,
- provider and optional model name,
- taxonomy and prompt SHA-256 fingerprints,
- workspace, branch and commit context,
- warnings, duration and author,
- a stable analysis session ID.

Queryable element and relation mappings are persisted separately but always reference the immutable snapshot. Reanalysis creates another snapshot and moves only the requirement's `currentAnalysisSnapshotId` pointer.

### 5. Human decisions are separate from generated evidence

The following concepts are never inferred solely from requirement relevance:

- `SATISFIED_AS_IS`
- `REUSE`
- `CHANGE`
- `CREATE`
- `PROCURE`
- `ORGANIZATIONAL`
- `RETIRE_OR_REPLACE`

The default is `UNDECIDED`. An element or relation mapping remains `PROPOSED` until reviewed. A deterministic suggestion may create a candidate, but it cannot confirm an action, solution or product.

### 6. Solutions are distinct from taxonomy elements

A taxonomy element states **what architectural capability, process, service, application or information product is relevant**.

A `SolutionDefinition` states **how one or more taxonomy elements can be realized**. It can be reused by multiple projects and carries operating model, lifecycle, maturity, ownership, costs, risks and lead time.

`ProjectSolution` represents the project's decision about that reusable definition. `RequirementSolutionLink` keeps coverage and evidence separate for every requirement and snapshot.

### 7. Products are sourced, dated candidates

A product is a concrete manufacturer product/version, not the taxonomy root `IP` (Information Products).

A `ProductCatalogEntry` is invalid without a non-empty source reference and verification timestamp. Product-to-taxonomy coverage and solution-product candidacy carry evidence, review status and coverage.

A candidate cannot become `SELECTED` unless:

- its review status is `CONFIRMED`, and
- it has no hard exclusion.

The system records a selection decision but does not perform procurement.

### 8. Aggregation preserves provenance

Project aggregation is calculated only from each requirement's current snapshot. Historical snapshots remain accessible but do not silently affect current matrices.

The project view exposes:

- deduplicated taxonomy nodes with originating requirement keys and snapshot IDs,
- requirement–taxonomy matrix,
- solution–requirement matrix,
- solution–product matrix,
- requirements without a confirmed solution,
- action status counts,
- selected products,
- open conflicts and stale snapshots.

No aggregation is allowed to discard the requirement and snapshot identities that caused a value.

### 9. Conflicts are hypotheses

The first conflict detector uses deterministic, explainable rules for hosting, data location, lifecycle, availability and platform statements. It stores evidence, input version fingerprints and confidence.

Every detected conflict starts as `PROPOSED`. A person must confirm, reject or resolve it.

### 10. Backward compatibility

`POST /api/analyze` remains available for ad-hoc analysis. `AnalyzeRequirementCommand` retains a backward-compatible constructor; project provenance is optional.

The portfolio is exposed through a dedicated `/projects` GUI and `/api/projects`, `/api/solutions` and `/api/products` resources. This avoids coupling the new workflow to the existing single-analysis browser state.

## Consequences

### Positive

- Second, third and subsequent requirements are no longer blended into one score set.
- Every project statement can be traced to requirement, text version, source and snapshot.
- Reanalysis is auditable rather than destructive.
- Shared solutions are deduplicated without losing per-requirement coverage.
- Product claims are visibly sourced and dated.
- AI-generated and deterministic suggestions cannot be mistaken for approved architecture decisions.
- The model supports later queue/SSE execution without changing domain identities.

### Costs and trade-offs

- More entities and joins are required than for an ad-hoc analysis.
- Full JSON snapshots consume additional storage, deliberately trading space for replayability.
- Current aggregation uses the latest snapshot pointer; users must explicitly reanalyse after a changed requirement version.
- Product quality depends on maintained source evidence and verification dates.
- The initial conflict detector is conservative and does not replace professional requirements review.

## Rejected alternatives

### Combine all requirements into one prompt

Rejected because attribution is lost, contradictory requirements contaminate the same score set and no per-requirement solution coverage can be proven.

### Treat workspace as project

Rejected because one user or team must be able to manage several projects while reusing the same solution and product catalogues.

### Persist only scores

Rejected because architecture propagation, relation provenance, prompts and taxonomy versions are necessary to interpret historical results.

### Let the LLM decide CREATE versus PROCURE

Rejected because relevance is not evidence of the current estate, procurement constraints, costs, lifecycle or organizational responsibility.

### Store products as free text on taxonomy nodes

Rejected because manufacturer, version, evidence date, lifecycle and project selection are separate concerns and must be independently reviewable.

## Follow-up decisions

Future ADRs may refine:

- asynchronous queue implementation and concurrency policy,
- retention/archival policies for snapshot payloads,
- product source refresh and expiry policy,
- external requirement/product catalogue import formats,
- project portfolio export and electronic signatures,
- conflict rule DSL or model-assisted conflict proposals.
