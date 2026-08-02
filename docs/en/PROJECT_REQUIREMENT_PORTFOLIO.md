# Project Requirement, Solution and Product Portfolio

## Purpose

The project portfolio turns the existing single-requirement analysis into a traceable multi-requirement workflow. It answers four different questions without conflating them:

1. **Requirement relevance:** Which taxonomy elements are relevant to each requirement?
2. **Architecture impact:** Which related elements and relations form the requirement-specific target view?
3. **Realisation decision:** Is an element already satisfied, reusable, changeable, new, procurable or organizational?
4. **Product decision:** Which sourced product/version is a reviewed candidate for a solution?

The dedicated workspace is available at:

```text
/projects
```

The existing `/` analysis workspace and `POST /api/analyze` remain available for ad-hoc analyses.

## Core rule: requirements are analyzed separately

A project can contain any number of stable requirements:

```text
P-001
├── REQ-001
├── REQ-002
└── REQ-003
```

`Analyze all` creates one independent analysis job item and one immutable result snapshot per requirement. It does **not** concatenate the texts. A failure for `REQ-002` does not discard the successful snapshots for `REQ-001` and `REQ-003`.

## Concepts

### Project

A workspace-scoped business container for requirements, current solution decisions, product candidates, conflict reviews and consolidated matrices.

A workspace may contain several projects. Reusable solutions and products can be shared by projects in the same workspace.

### Requirement and requirement version

A requirement has a stable key such as `REQ-001`. Its text is not overwritten. A changed text creates an immutable numbered version with:

- SHA-256 content fingerprint,
- author and timestamp,
- change reason,
- optional source artifact, source version and source fragment references,
- section, page and original text.

Submitting identical text selects the existing version instead of creating a duplicate.

### Analysis job and item

A job describes one requested batch. Every requirement is represented by its own item with status:

- `PENDING`
- `RUNNING`
- `SUCCESS`
- `PARTIAL`
- `FAILED`
- `CANCELLED`

Failed items can be retried without repeating successful items.

### Analysis snapshot

A successful or partial item creates an immutable snapshot containing:

- exact requirement version,
- complete score set,
- architecture view,
- relation hypotheses,
- gap analysis,
- pattern detection,
- architecture recommendation,
- provider and optional model,
- prompt and taxonomy fingerprints,
- workspace, branch and Git commit,
- warnings, runtime and author.

A later reanalysis creates another snapshot. Historical results remain replayable and comparable.

### Taxonomy mapping

A snapshot maps the requirement to concrete taxonomy nodes. Every mapping records:

- direct score,
- derived relevance,
- confidence,
- mapping origin,
- hierarchy path,
- human-readable presence reason,
- impact selection flag,
- review and action decision.

Mapping origins include direct scores, propagation and enriched leaves. Review decisions do not mutate the snapshot payload; they annotate the queryable mapping.

### Action status

Taxonomy relevance does not prove what must be built or bought. The default is therefore `UNDECIDED`.

A reviewer may classify a mapping or project solution as:

| Action | Meaning |
|---|---|
| `SATISFIED_AS_IS` | Existing solution already satisfies the requirement |
| `REUSE` | Existing solution can be reused |
| `CHANGE` | Existing solution must be modified |
| `CREATE` | New solution must be developed |
| `PROCURE` | A solution/product must be procured |
| `ORGANIZATIONAL` | Organizational measure rather than a technical product |
| `RETIRE_OR_REPLACE` | Existing solution must be retired or replaced |
| `UNDECIDED` | No reviewed decision yet |

### Solution definition and project solution

A `SolutionDefinition` is a reusable way to realize one or more taxonomy elements. It may describe a service, application, platform, process, data solution, infrastructure component or organizational measure.

It stores operating model, lifecycle, maturity, ownership, cost, risk and lead-time metadata.

A `ProjectSolution` is the project-specific decision to evaluate, select or implement that reusable definition. Requirement coverage is stored separately for every requirement and snapshot.

### Product catalogue entry

A product entry represents a concrete manufacturer product and version. It is not the taxonomy category `IP`.

Every product entry requires:

- manufacturer and product name,
- source reference,
- verification timestamp.

It can additionally contain version, family, lifecycle/end of support, licence model, operating model, supported platforms, security/compliance features and cost basis.

A product candidate can only become `SELECTED` when its review status is `CONFIRMED` and it has no hard exclusion.

### Conflict hypothesis

Conflict detection creates explainable candidates for review. Initial rules cover hosting, data location, lifecycle, availability and platform constraints.

Every result starts as `PROPOSED`; a reviewer must confirm, reject or resolve it. Conflict detection is an aid, not a substitute for professional requirements engineering.

## GUI workflow

### 1. Create a project

Open `/projects`, choose **New project**, and provide a project key, title and optional description.

Project keys are unique inside the current workspace.

### 2. Add requirements

Use **New requirement** for manual capture. Every new requirement receives its own stable identity and initial text version.

Document-derived candidates can be imported through the project API as separate requirements. The caller must choose explicitly when candidates should be merged into one requirement; the portfolio never merges them automatically.

### 3. Analyze requirements

Use **Analyze** for one requirement or **Analyze all** for the project.

The resulting summary shows successful, partial and failed items. Open **Snapshots** from a requirement row to inspect history, scores, mappings, fingerprints and warnings.

### 4. Review taxonomy mappings

Inside a snapshot, choose the action status for a taxonomy mapping and confirm it. This records a human decision separately from the generated score and explanation.

### 5. Create or propose solutions

A solution can be entered manually and assigned to the project.

After confirmed solution-to-taxonomy coverage has been recorded, **Propose solutions** matches reusable solutions against current requirement mappings. These links remain `PROPOSED`; they are not automatic architecture approval.

Confirm each requirement–solution link after reviewing its evidence and coverage.

### 6. Maintain products

Create a sourced product entry in the **Products** tab. Add evidence-backed product-to-taxonomy coverage where appropriate.

A product can then be added as a candidate to a project solution. Shortlisting and selection remain explicit review actions.

### 7. Detect and review conflicts

Choose **Detect conflicts**. Review each hypothesis in the Conflicts tab and confirm, reject or resolve it. A resolved conflict can carry a resolution note.

### 8. Read consolidated matrices

The portfolio presents:

- requirement–taxonomy matrix,
- solution–requirement matrix,
- solution–product matrix.

Values are percentages. Empty cells mean no stored relationship, not a zero-score evaluation.

## REST API

### Projects and requirements

```text
POST   /api/projects
GET    /api/projects
GET    /api/projects/{projectId}
PATCH  /api/projects/{projectId}

POST   /api/projects/{projectId}/requirements
POST   /api/projects/{projectId}/requirements/import
GET    /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements/{requirementId}
PATCH  /api/projects/{projectId}/requirements/{requirementId}
POST   /api/projects/{projectId}/requirements/{requirementId}/versions
GET    /api/projects/{projectId}/requirements/{requirementId}/versions
```

### Analysis and snapshots

```text
POST   /api/projects/{projectId}/analyses
POST   /api/projects/{projectId}/requirements/{requirementId}/analyses
GET    /api/projects/{projectId}/analysis-jobs
GET    /api/projects/{projectId}/analysis-jobs/{jobId}
POST   /api/projects/{projectId}/analysis-jobs/{jobId}/retry-failed

GET    /api/projects/{projectId}/requirements/{requirementId}/snapshots
GET    /api/projects/{projectId}/snapshots/{snapshotId}
GET    /api/projects/{projectId}/snapshots/diff?older=...&newer=...

PATCH  /api/projects/{projectId}/analysis-mappings/elements/{mappingId}
PATCH  /api/projects/{projectId}/analysis-mappings/relations/{mappingId}
```

### Solutions

```text
POST   /api/solutions
GET    /api/solutions
GET    /api/solutions/{solutionId}
PATCH  /api/solutions/{solutionId}
POST   /api/solutions/{solutionId}/taxonomy-coverage

POST   /api/projects/{projectId}/solutions
GET    /api/projects/{projectId}/solutions
PATCH  /api/projects/{projectId}/solutions/{projectSolutionId}
POST   /api/projects/{projectId}/solutions/{projectSolutionId}/requirements
POST   /api/projects/{projectId}/solutions/propose-from-taxonomy
```

### Products

```text
POST   /api/products
GET    /api/products
GET    /api/products/{productId}
PATCH  /api/products/{productId}
POST   /api/products/{productId}/taxonomy-coverage

POST   /api/projects/{projectId}/solutions/{projectSolutionId}/products
GET    /api/projects/{projectId}/solutions/{projectSolutionId}/products
```

### Consolidation and conflicts

```text
GET    /api/projects/{projectId}/portfolio
POST   /api/projects/{projectId}/conflicts/detect
GET    /api/projects/{projectId}/conflicts
PATCH  /api/projects/{projectId}/conflicts/{conflictId}
```

All failures use RFC 9457 `ProblemDetail` responses.

## Examples

### Analyze all requirements independently

```bash
curl -X POST http://localhost:8080/api/projects/1/analyses \
  -H 'Content-Type: application/json' \
  -d '{
    "all": true,
    "provider": "GEMINI",
    "maxArchitectureNodes": 25,
    "idempotencyKey": "P-001-baseline-2026-08-02"
  }'
```

Reusing the same client idempotency key returns the existing job instead of creating duplicate snapshots.

### Import three candidates as three requirements

```bash
curl -X POST http://localhost:8080/api/projects/1/requirements/import \
  -H 'Content-Type: application/json' \
  -d '{
    "analyzeAfterImport": true,
    "requirements": [
      {"requirementKey":"REQ-001","title":"Secure voice","text":"..."},
      {"requirementKey":"REQ-002","title":"EU data residency","text":"..."},
      {"requirementKey":"REQ-003","title":"Offline operation","text":"..."}
    ]
  }'
```

### Review an element mapping

```bash
curl -X PATCH http://localhost:8080/api/projects/1/analysis-mappings/elements/42 \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewStatus":"CONFIRMED",
    "actionStatus":"REUSE",
    "actionEvidence":"Existing service catalogue entry SOL-004",
    "comment":"Reviewed by the project architect"
  }'
```

## Security

- Reads require authentication.
- Project analyses may be executed by `USER`, `ARCHITECT` or `ADMIN`.
- Project, requirement, solution, product and review mutations require `ARCHITECT` or `ADMIN`.
- Every application service receives an explicit request-bound `WorkspaceContext`.
- Resources in another workspace are returned as not found rather than exposing their existence.

## Reproducibility and staleness

A snapshot is marked stale in project metrics when:

- it is older than `taxonomy.portfolio.snapshot-stale-after-days` (default 30), or
- it analyzes a requirement version that is no longer current.

Stale does not mean invalid. It means the project should decide whether to reanalyse with the current requirement, taxonomy and prompt baseline.

Snapshot diff distinguishes:

- changed scores,
- added/removed elements,
- added/removed relations,
- taxonomy fingerprint change,
- prompt fingerprint change,
- provider change.

## Current limitations

- Job execution is synchronous from the HTTP caller's perspective, although durable item state and isolated retry are already implemented. The model is ready for a later queue/SSE executor.
- Product data is manually curated; no vendor catalogue feed is included.
- Conflict rules intentionally have limited scope and may produce false positives or miss semantic conflicts.
- The product portfolio records reviewed candidates and selections but does not execute procurement.
- A solution proposal requires confirmed solution-to-taxonomy coverage. The system does not invent a real solution merely from a taxonomy score.

## Architecture decision

See [ADR 0001](../adr/0001-project-requirement-solution-portfolio.md) for model boundaries, rejected alternatives and consequences.
