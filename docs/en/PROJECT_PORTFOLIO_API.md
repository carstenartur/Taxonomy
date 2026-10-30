# Project Portfolio API

This reference is for integrations, automation, testing and custom clients. End users should use the GUI-first [Project Portfolio Guide](PROJECT_REQUIREMENT_PORTFOLIO.md).

## Authentication and write protection

All endpoints require an authenticated user. In the local form-login profile, browser sessions require CSRF tokens for writes. Explicit HTTP Basic or Bearer clients are treated as stateless API clients.

Roles:

- `USER`, `ARCHITECT`, `ADMIN`: read portfolio resources and start permitted analyses;
- `ARCHITECT`, `ADMIN`: mutate projects, requirements, solutions, products, reviews, conflicts and Git state;
- `ADMIN`: administrative APIs outside the portfolio.

Unknown API write methods are denied by default. Workspace-bound operations fail closed when no isolated workspace can be resolved, unless an explicitly configured legacy shared mode is active.

## Error contract

Portfolio validation, not-found and conflict errors use RFC 9457-style `ProblemDetail` JSON. Clients should primarily inspect `status`, `title` and `detail` rather than parsing human-readable text.

Lifecycle/editor command errors also expose stable `code` values and, where
applicable, `field`, dependency or revision details. Use that contract to decide
whether to refresh, preview again or resume; do not parse translated error text.

<a id="architecture-lifecycle-api"></a>
## Architecture lifecycle, intake and realization

The architecture repository is independent of project completion. A follow-up
project contributes to the same architecture while the original project-qualified
requirement identity remains stable. See the [user workflow](PROJECT_REQUIREMENT_PORTFOLIO.md#architecture-lifecycle).

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/architecture/lifecycle/architectures` | List readable active architectures and the caller's own incomplete creations. |
| POST | `/api/architecture/lifecycle/architectures` | Create or resume the exact owned private architecture creation. |
| GET | `/api/architecture/lifecycle/architectures/{repositoryId}/workspaces` | Recover the caller's existing private working copies for that architecture. |
| GET | `/api/architecture/lifecycle` | Read the exact selected architecture stand and its requirement realization assessments. |
| GET | `/api/architecture/lifecycle/history` | Search actual reachable Git history with bounded coverage. |
| POST | `/api/architecture/lifecycle/intake-review` | Review incoming text against authorized candidates without writing. |
| POST | `/api/architecture/editor/requirements` | Apply an explicit `NEW`, `CHANGE` or `LINK` choice and prepare/publish its checkpoint. |
| POST | `/api/architecture/lifecycle/realizations/preview` | Preview a review of an exact requirement version and architecture dependencies. |
| POST | `/api/architecture/lifecycle/realizations` | Accept the exact review preview and prepare/publish its checkpoint. |

The overview accepts optional `repositoryId`, `workspaceScopeKey` and `branch`
assertions against the authorized context. They cannot grant access to another
workspace. Select `commit` (full reachable Git SHA) **or** semantic `revision`,
never both. The query selector is not inferred from a command's `context.revision`.

| Selection | Source and behavior |
|---|---|
| Private workspace: no selector, or explicitly its current revision | `WORKSPACE_REVISION`; current portfolio contributions may be overlaid. Editing still requires the appropriate role and ready projection. |
| Earlier retained semantic revision | `WORKSPACE_REVISION`, `projectionState=HISTORICAL`, `mayEdit=false`; stored source without today's portfolio overlay. |
| Reachable Git commit | `GIT_CHECKPOINT`, `projectionState=HISTORICAL`, `mayEdit=false`; exact committed source. |
| Central/fork read context without a selector | `GIT_CHECKPOINT` at its current HEAD, `mayEdit=false`; no private semantic journal revision or current portfolio overlay. |

Invalid, missing or inaccessible selections fail; they do not fall back to current
data. The response contains `context`, `architectureTitle`, `source`, `projectionState`,
`publicationState`, `mayEdit`, `projects`, `requirements` and selectable `dependencies`.
Each requirement's `version`, `versionId`, `contentHash` and text describe that
selected model stand; a historical row does not overwrite the current portfolio
requirement header. `realization`, `contributors` and `links` retain review and
contribution provenance. Historical portable sources need not have current database IDs.

### Independent architecture creation and exact recovery

Creation takes `{displayName, slug, description}`: a nonblank name of at most 255
characters, a stable slug matching `[a-z0-9][a-z0-9-]{0,99}` and an optional description
of at most 1,000 characters. A new creation returns 201; exact recovery returns 200.
Keep the same slug, name and description after an interrupted response. Recovery
only matches the same caller's original private creation identity. A matching
`ACTIVE` entry is reused; a matching `FAILED` entry can resume provisioning under
the same repository ID. A `PROVISIONING` entry returns 409: observe/reload its status
before retrying. A different owner, identity or creation payload conflicts rather
than exposing or taking over another creation.

The architecture response includes `repositoryId`, `displayName`, `slug`,
`defaultBranch`, `owned`, `description` and `lifecycleState`. The list includes the
caller's own `FAILED`/`PROVISIONING` creations so a lost response does not require
inventing a new identity. Recovery verifies and retains an already-written initial
Git commit; it does not delete or reinitialize logical Git storage. Different retained
Git content returns 409 for explicit review. Recover an existing private working
copy through the workspace-list route before creating another one. Its entries
identify `workspaceId`, `sourceRepositoryId`, `sourceBranch`, `currentBranch`,
`displayName` and `provisioningStatus`.

### Exact context, input and publication

Mutating lifecycle commands and realization previews require
`If-Match: "workspace-revision-N"` matching the body `context.revision`; do not send
`If-None-Match`. Missing preconditions return 428, malformed preconditions 400 and
a moved semantic revision 412. Context/version conflicts require a fresh comparison.
The context contains `repositoryId`, `workspaceScopeKey`, `branch`, `commit`,
`revision`, `actor` and `writeMode`. Reads return an ETag, semantic revision and
no-store caching policy; the overview also identifies its source in `X-Taxonomy-Source`.

`metadata` contains stable UUID `commandId`, `correlationId`, `causationId` and a
rationale. Retain the exact request and command identity for a transport retry.
Reusing a command identity with changed intent is rejected. A successful retry
does not create another requirement version or repeat an accepted contribution.

The requirement command contains `context`, `metadata`, `choice`, `projectId`,
`newProject`, `contributorProjectId`, `requirementId`, `requirement`,
`expectedVersionId`, `expectedContentHash` and `inputHash`. `inputHash` is SHA-256
of the exact UTF-8 requirement text, without trimming or normalization.

| Choice | Required intent |
|---|---|
| `NEW` | Exactly one existing `projectId` or `newProject`, requirement creation fields and no existing `requirementId`. |
| `CHANGE` | Existing project/requirement and exact current version ID/hash; creates a new immutable version under the original identity. |
| `LINK` | Existing project/requirement and exact current version ID/hash; records the contribution without replacing stored text. |

For `CHANGE`/`LINK`, `contributorProjectId` or a `newProject` can identify a follow-up
project. The original project-qualified requirement remains the target. Candidate
suggestions do not authorize cross-workspace writes. A changed current requirement
version returns `REQUIREMENT_VERSION_CHANGED` instead of silently choosing the latest.

The analysis action for saving a new requirement version additionally requires the
loaded source context's `requirementVersionId` to match the current version. Missing
or stale source IDs stop the action with an explicit notice before submission.
The resulting command still binds `expectedVersionId` and `expectedContentHash`
for server-side validation. Text typed during submission remains a separate draft;
the saved result can be opened in another tab without discarding it. See the
[analysis promotion workflow](USER_GUIDE.md#analysis-requirement-promotion).

Realization requests contain `projectId`, `requirementId`, `expectedVersionId`,
`expectedContentHash`, `contributorProjectId`, `status` (`OPEN`, `PARTIAL`, `COMPLETE`)
and `dependencyIds`, alongside context and metadata. Acceptance also supplies the
preview's `fingerprint` as `previewFingerprint`. Evidence uses existing elements,
relations or mappings; relation endpoints and mapping targets are included in the
assessment. Partial/complete reviews require evidence and the dependency set is
bounded. Text or dependency changes yield `REVIEW_REQUIRED`; invalid evidence yields
`INVALID_EVIDENCE`. The earlier reviewed version remains available. AI relevance
scores do not determine these states.

Requirement responses contain `projectId`, `requirementId`, `requirement` and
`publication`; realization acceptance returns the publication directly. Inspect
`publication.state`: only `CHECKPOINTED` establishes a completed checkpoint.
`CHECKPOINT_PENDING` and `RECONCILIATION_REQUIRED` retain an accepted contribution
without proving completed Git publication. The publication contains `context`,
`checkpoint`, `code` and `detail`. The editor's `document.pendingCheckpointId`
identifies a prepared write for the existing resume route. Ordinary project CRUD
routes below remain draft operations without this lifecycle checkpoint guarantee.

### Intake coverage and provider outcomes

The read-only intake request requires `text`; `projectId`, `title` and
`analysisLanguage` are optional. Its response contains `status`, `code`, `inputHash`, `scope`,
`provider`, `coverage`, `candidates` and `manualReviewRequired`. Coverage reports
`scanned`, `admitted` and `truncated`; project, requirement, repository, document and
evidence-size limits bound discovery. Another user's private workspace is not a
candidate source. Candidate actions reflect their actual authorized scope.

`scope` identifies `repositoryId`, `workspaceId`, `workspaceScopeKey` and `branch`.
Candidate kinds are `REQUIREMENT`, `PROJECT`, `ARCHITECTURE` and
`ARCHITECTURE_REQUIREMENT`; requirement candidates retain source IDs, text version
and content hash. The provider compares bounded authorized text candidates directly.
The taxonomy semantic index is not a requirement-vector index.

`status` is `COMPLETE`, `PARTIAL`, `UNAVAILABLE` or `FAILED`. Candidate classification
is `DUPLICATE`, `CHANGE`, `ADDITION`, `CONFLICT` or `NONE`, with rationale and source
quotes when assessed. A complete assessment only covers admitted candidates;
unassessed candidates retain a null classification.
`NONE`, truncation or an empty result is not a global novelty guarantee. Exact
stored-text equality can be recognized deterministically even without provider
success. Missing configuration, project provider policy, local/mock/replay providers,
timeout and rate limits are distinct from valid negative findings. Invalid or
incomplete model output is a failed review, not an empty successful one. Manual
review and explicit saving remain separate from model availability.

### Native Git history and continuation

History accepts context assertions plus paired `projectKey`/`requirementKey`,
optional `text`, `expectedHead`, `offset` (default 0), `limit` (default 25, maximum
100) and `scanLimit` (default 100, maximum 500). A positive offset requires the
captured `expectedHead`. Both query keys must be supplied together. The search uses
native JGit over the existing `jgit-storage-hibernate` repository, not a shell Git
command or a parallel audit store. Only ancestry reachable from the selected branch
HEAD is searched. Changes compare each commit with its first parent while entries
retain all actual parents; merge topology is not flattened into fabricated history.

The page contains `head`, `entries`, `offset`, `nextOffset`, `scanned`, `coverage`
and `source=LIVE_GIT`. Entries contain `commitId`, `parents`, `author`, `timestamp`,
the full `message`, signature evidence and `changes`. Kinds include `ADDED`,
`REMOVED`, `REQUIREMENT_CHANGED`, `REQUIREMENT_METADATA_CHANGED`, `DEPENDENCIES_CHANGED`,
`REALIZATION_CHANGED`, `MERGE` and `GIT_CHANGE`. Coverage is `COMPLETE`, `PARTIAL`
or `LIMIT_REACHED`. Continue with the returned offset and same HEAD. A changed HEAD
returns HTTP 409 `HISTORY_HEAD_CHANGED`, with `branch`, `expectedHead` and `currentHead`;
restart from the first page instead of continuing the old offset. Bounded scans may
return no matches while more history remains.
Authorization, storage and source-reading failures must not be presented as empty history.

<a id="editor-version-reconciliation-api"></a>
### Reconcile local contributions with a changed Git HEAD

| Method | Route | Contract |
|---|---|---|
| POST | `/api/architecture/editor/versions/reconciliation/preview` | Compare base, accepted local and incoming Git sources without writing. |
| POST | `/api/architecture/editor/versions/reconciliation/accept` | Accept the exact resolved preview and complete its checkpoint. |
| POST | `/api/architecture/editor/checkpoints/resume` | Finish a prepared checkpoint after an interrupted Git write. |

Both reconciliation routes require the exact revision `If-Match`. Their request is
`{context, metadata, resolutions, headCommit, previewFingerprint}`. `resolutions`
maps returned conflict IDs to `LOCAL` or `INCOMING`. Each selects a whole source
block; the reserved `$document` conflict selects document metadata outside blocks.
Preview returns `context`, `headCommit`, `fingerprint`, `baseDsl`, `localDsl`,
`incomingDsl`, `mergedDsl`, `changes`, `conflicts` and `canAccept`. Each conflict has
`id`, `reason`, `base`, `local`, `incoming` and `resolution`. Missing choices make
`canAccept=false`; the combined model must also pass validation. After choosing,
preview again. Acceptance binds the same choices, metadata, context, captured HEAD
and `fingerprint` as `previewFingerprint`. Changed inputs require a new preview.

Read-only portfolio-owner validation checks managed canonical title, text, `x-version`
and `x-content-hash` against the portable business record and current immutable
version. Existing local `requirementVersion` source cannot be rewritten; changed
text requires a new version. Invalid combinations are surfaced as source conflicts
with explicit `LOCAL`/`INCOMING` choices. Retaining the consistent local blocks
provides the path to create a new version. Git omission cannot delete managed
business history: choose `LOCAL` and use explicit archival. Incompatible choices
return HTTP 409 `PORTFOLIO_RECONCILIATION_REQUIRED`.

Compatibility also checks the exact target database's retained immutable versions,
including versions absent from the local DSL. Conflicting text, hashes or supplied
source provenance, duplicate version numbers and known content under a different
version number are rejected rather than rewritten or renumbered. This preflight
runs before the proposed integration advances target model or tracking references;
matching portable source blocks alone are not sufficient.

On acceptance, changed managed portfolio blocks are materialized through
`WorkspacePortfolioGitPort` inside the journal transaction, before checkpoint intent
and Git publication. Portfolio validation/materialization conflicts roll back that
acceptance without new database changes, journal operations or Git checkpoints.
An exact retry uses the retained operation without a second materialization. A later
Git storage failure still uses pending-checkpoint recovery. Success returns existing
`CheckpointAccepted` (`context`, `commandId`, `commitId`, `fromRevision`,
`throughRevision`, `commitCreated`, `replayed`), not a lifecycle publication wrapper.
Moved HEAD/revision requires a fresh preview; repeating saves does not resolve a
branch conflict. `/api/architecture/editor/versions/recover` remains for completed
version writes without newer uncheckpointed local changes. See the
[editor workflow](ARCHITECTURE_EDITOR.md#editor-version-reconciliation).

## Projects

```text
POST   /api/projects
GET    /api/projects
GET    /api/projects/{projectId}
PATCH  /api/projects/{projectId}
```

Create a project:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects \
  -H 'Content-Type: application/json' \
  -d '{
    "projectKey": "P-001",
    "title": "Secure collaboration platform",
    "description": "Architecture project for the target platform",
    "status": "ACTIVE"
  }'
```

## Requirements and immutable versions

```text
POST   /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements/{requirementId}
PATCH  /api/projects/{projectId}/requirements/{requirementId}
POST   /api/projects/{projectId}/requirements/{requirementId}/versions
GET    /api/projects/{projectId}/requirements/{requirementId}/versions
```

Create one requirement:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements \
  -H 'Content-Type: application/json' \
  -d '{
    "requirementKey": "REQ-001",
    "title": "Encrypted communication",
    "text": "The platform shall encrypt all user communication.",
    "status": "DRAFT",
    "priority": 80,
    "criticality": "HIGH",
    "requirementType": "SECURITY",
    "reviewStatus": "PROPOSED",
    "changeReason": "Initial version",
    "source": {
      "sourceArtifactId": 41,
      "sourceVersionId": 42,
      "sourceFragmentIds": [101, 102],
      "sectionReference": "Section 5.2",
      "pageNumber": 17,
      "originalText": "All communication shall be encrypted."
    }
  }'
```

Create a new immutable text version:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements/11/versions \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "The platform shall use approved end-to-end encryption for all user communication.",
    "changeReason": "Clarified the encryption requirement",
    "source": {
      "sectionReference": "Section 5.2.1",
      "pageNumber": 18,
      "originalText": "Approved end-to-end encryption is mandatory."
    }
  }'
```

## Document parsing and extraction

The guided GUI uses the existing provenance endpoints:

```text
POST /api/documents/upload       multipart/form-data
POST /api/documents/extract-ai   multipart/form-data
```

Upload and parse a source:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/documents/upload \
  -F 'file=@requirements.pdf' \
  -F 'title=Platform requirements' \
  -F 'sourceType=REGULATION'
```

The response contains source artifact/version identifiers, deterministic candidates, page information and warnings.

## Atomic reviewed import

The mixed reviewed-import endpoint applies new requirements and new versions in one transaction:

```text
POST /api/projects/{projectId}/requirements/import-review
```

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements/import-review \
  -H 'Content-Type: application/json' \
  -d '{
    "analyzeAfterImport": true,
    "maxArchitectureNodes": 25,
    "idempotencyKey": "source-42-reviewed-2026-08-04",
    "items": [
      {
        "decision": "NEW_REQUIREMENT",
        "requirementKey": "REQ-002",
        "title": "Audit history",
        "text": "The platform shall retain an auditable decision history.",
        "requirementType": "FUNCTIONAL",
        "priority": 70,
        "criticality": "HIGH",
        "source": {
          "sourceArtifactId": 41,
          "sourceVersionId": 42,
          "sectionReference": "Section 7",
          "pageNumber": 22,
          "originalText": "An auditable history shall be retained."
        }
      },
      {
        "decision": "NEW_VERSION",
        "targetRequirementId": 11,
        "title": "Encrypted communication",
        "text": "The platform shall use approved end-to-end encryption.",
        "requirementType": "SECURITY",
        "priority": 80,
        "criticality": "HIGH",
        "source": {
          "sourceArtifactId": 41,
          "sourceVersionId": 42,
          "sectionReference": "Section 5.2.1",
          "pageNumber": 18,
          "originalText": "Approved end-to-end encryption is mandatory."
        }
      }
    ]
  }'
```

When analysis is requested, the endpoint returns `202 Accepted` and a canonical `Location` header for the persisted job. Otherwise it returns `201 Created`.

## Analysis jobs

```text
POST   /api/projects/{projectId}/analyses
POST   /api/projects/{projectId}/requirements/{requirementId}/analyses
GET    /api/projects/{projectId}/analysis-jobs
GET    /api/projects/{projectId}/analysis-jobs/{jobId}
POST   /api/projects/{projectId}/analysis-jobs/{jobId}/retry-failed
```

Analyse all current requirements separately:

```bash
curl -i -u analyst:password \
  -X POST http://localhost:8080/api/projects/1/analyses \
  -H 'Content-Type: application/json' \
  -d '{
    "all": true,
    "provider": "GEMINI",
    "maxArchitectureNodes": 25,
    "idempotencyKey": "P-001-baseline-2026-08-04"
  }'
```

Typical response:

```text
HTTP/1.1 202 Accepted
Location: /api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000
```

Poll the job:

```bash
curl -u analyst:password \
  http://localhost:8080/api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000
```

Job/item states:

```text
PENDING, RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED
```

Retry only failed or recoverable items:

```bash
curl -u analyst:password \
  -X POST http://localhost:8080/api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000/retry-failed \
  -H 'Content-Type: application/json' \
  -d '{}'
```

## Snapshots, mappings and review

```text
GET    /api/projects/{projectId}/requirements/{requirementId}/snapshots
GET    /api/projects/{projectId}/snapshots/{snapshotId}
GET    /api/projects/{projectId}/snapshots/diff?older=...&newer=...
PATCH  /api/projects/{projectId}/analysis-mappings/elements/{mappingId}
PATCH  /api/projects/{projectId}/analysis-mappings/relations/{mappingId}
```

Review one element mapping with actual evidence:

```bash
curl -u architect:password \
  -X PATCH http://localhost:8080/api/projects/1/analysis-mappings/elements/42 \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewStatus": "CONFIRMED",
    "actionStatus": "REUSE",
    "actionEvidence": "Service catalogue entry SOL-004 was verified against the requirement.",
    "comment": "Reviewed by the project architect."
  }'
```

Action states:

```text
UNDECIDED
SATISFIED_AS_IS
REUSE
CHANGE
CREATE
PROCURE
ORGANIZATIONAL
RETIRE_OR_REPLACE
```

## Solutions

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

Create and add a solution:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/solutions \
  -H 'Content-Type: application/json' \
  -d '{
    "solutionKey": "SOL-001",
    "title": "Secure communication service",
    "description": "Reusable encrypted communication capability",
    "solutionType": "SERVICE",
    "operatingModel": "PRIVATE_CLOUD",
    "lifecycleStatus": "PLANNED",
    "maturityLevel": 2,
    "extensionAttributes": {}
  }'
```

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions \
  -H 'Content-Type: application/json' \
  -d '{
    "solutionId": 21,
    "status": "PROPOSED",
    "actionStatus": "REUSE",
    "priority": 70,
    "rationale": "Candidate for REQ-001 and REQ-002"
  }'
```

Add confirmed taxonomy coverage:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/solutions/21/taxonomy-coverage \
  -H 'Content-Type: application/json' \
  -d '{
    "nodeCode": "CR-1047",
    "coveragePercent": 90,
    "evidence": "Verified solution architecture description, version 3.1",
    "reviewStatus": "CONFIRMED"
  }'
```

Link the project solution to one exact requirement snapshot:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions/31/requirements \
  -H 'Content-Type: application/json' \
  -d '{
    "requirementId": 11,
    "snapshotId": "snapshot-uuid",
    "coveragePercent": 85,
    "role": "USES",
    "reviewStatus": "CONFIRMED",
    "evidence": "Snapshot and solution capability were reviewed together."
  }'
```

## Products and candidates

```text
POST   /api/products
GET    /api/products
GET    /api/products/{productId}
PATCH  /api/products/{productId}
POST   /api/products/{productId}/taxonomy-coverage

POST   /api/projects/{projectId}/solutions/{projectSolutionId}/products
GET    /api/projects/{projectId}/solutions/{projectSolutionId}/products
```

Create a sourced product:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{
    "productKey": "PRD-001",
    "manufacturer": "Example Vendor",
    "productFamily": "Secure Collaboration",
    "productName": "Collaboration Server",
    "editionVersion": "4.2",
    "productStatus": "CANDIDATE",
    "operatingModel": "PRIVATE_CLOUD",
    "sourceReference": "Vendor architecture guide 4.2, chapter 6",
    "verifiedAt": "2026-08-04T08:00:00Z"
  }'
```

Add or update a candidate:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions/31/products \
  -H 'Content-Type: application/json' \
  -d '{
    "productId": 51,
    "coveragePercent": 85,
    "hardExclusions": null,
    "strengths": "Strong identity and encryption integration",
    "weaknesses": "Additional migration work",
    "openEvidence": "Performance benchmark remains open",
    "confidence": 0.82,
    "reviewStatus": "CONFIRMED",
    "selectionStatus": "SHORTLISTED"
  }'
```

A product may be `SELECTED` only with `reviewStatus=CONFIRMED` and without a hard exclusion.

## Conflict registry

```text
POST   /api/projects/{projectId}/conflicts/detect
GET    /api/projects/{projectId}/conflicts
PATCH  /api/projects/{projectId}/conflicts/{conflictId}
```

Resolve a reviewed conflict:

```bash
curl -u architect:password \
  -X PATCH http://localhost:8080/api/projects/1/conflicts/71 \
  -H 'Content-Type: application/json' \
  -d '{
    "status": "RESOLVED",
    "resolutionNote": "The approved private cloud profile satisfies both requirements."
  }'
```

Conflict states:

```text
PROPOSED, CONFIRMED, REJECTED, RESOLVED
```

## Consolidated portfolio and matrices

```text
GET /api/projects/{projectId}/portfolio
```

The response contains project data, requirements, aggregated taxonomy nodes, solutions, conflicts, metrics and these matrix objects:

- `requirementTaxonomyMatrix`
- `requirementSolutionMatrix`
- `solutionProductMatrix`

There are no separate `/matrices/...` endpoints. Each matrix contains `rows`, `columns` and a nested `values` map.

## Git projection and collaboration

```text
GET    /api/projects/git/export
POST   /api/projects/git/commit
GET    /api/projects/git/materialize-preview?branch=...
POST   /api/projects/git/materialize
POST   /api/projects/git/merge?source=...&target=...
```

Export the current workspace portfolio as canonical TaxDSL:

```bash
curl -u architect:password \
  http://localhost:8080/api/projects/git/export
```

Commit the reviewed projection:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/git/commit \
  -H 'Content-Type: application/json' \
  -d '{
    "branch": "draft",
    "message": "Reviewed portfolio after REQ-001 analysis"
  }'
```

Preview materialisation before applying it:

```bash
curl -u architect:password \
  'http://localhost:8080/api/projects/git/materialize-preview?branch=draft'
```

The preview contains the exact target HEAD, current and target fingerprints, line counts and bounded added/removed previews. Clients should repeat the preview immediately before applying and reject a changed `targetHead`.

Merge distinct branches:

```bash
curl -u architect:password \
  -X POST 'http://localhost:8080/api/projects/git/merge?source=feature-a&target=draft' \
  -H 'Content-Type: application/json' \
  -d '{"message":"Merge reviewed feature portfolio"}'
```

## Reports

```text
GET /api/projects/{projectId}/reports/{format}
```

Formats:

```text
markdown, html, docx, json, csv
```

Optional query parameters:

- `requirementId`: restrict the report to one requirement;
- `matrix`: for CSV, one of `taxonomy`, `solutions`, `products`.

Examples:

```bash
curl -u reader:password \
  -o P-001-report.docx \
  http://localhost:8080/api/projects/1/reports/docx
```

```bash
curl -u reader:password \
  -o REQ-001-report.json \
  'http://localhost:8080/api/projects/1/reports/json?requirementId=11'
```

```bash
curl -u reader:password \
  -o P-001-solution-product.csv \
  'http://localhost:8080/api/projects/1/reports/csv?matrix=products'
```

## Limits and operational configuration

| Property | Default | Purpose |
|---|---:|---|
| `taxonomy.portfolio.max-analysis-batch` | `100` | Maximum requirements in one analysis job |
| `taxonomy.portfolio.max-import-requirements` | `100` | Maximum reviewed items in one import |
| `taxonomy.portfolio.max-import-characters` | `500000` | Maximum combined reviewed text payload |
| `taxonomy.portfolio.analysis-claim-timeout-seconds` | `900` | Time before a running claim is recoverable |
| `taxonomy.portfolio.max-active-jobs-per-workspace` | implementation configuration | Bounds active job pressure per workspace |

See [Portfolio Operations](PROJECT_PORTFOLIO_OPERATIONS.md) for migration, backup, recovery and production deployment.

## Frozen snapshot Word reports

The architecture workbench offers **Architecture Word** and **Decision Word** for the selected saved analysis:

- `GET /api/projects/{projectId}/snapshots/{snapshotId}/architecture-report/docx?language=en`
- `GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/docx?language=en`

Both use the saved requirement version, decision evidence and persisted architecture graph. They do not recalculate the graph using current catalogue selections, preferences or proposals. Both contain the architecture overview, bounded detail figures, complete element/relation inventories, recommendation, recorded gaps and provenance. Decision Word also includes every parent/alternative in a linked tree overview and the complete decision chapters; missing scores remain “Not evaluated”, while zero means evaluated and rejected.

`X-Taxonomy-Snapshot-Id` and `X-Taxonomy-Graph-SHA256` identify the common source. The same values are embedded as `taxonomy.snapshot.id` and `taxonomy.graph.sha256` custom properties. The graph digest includes all frozen node/relation semantics and container metadata; it excludes mutable display titles and is distinct from the exchange formats’ semantic-only digest.

Word headings, ready-to-read linked contents, repeating table headers, numbered captions and image descriptions are native OOXML structures. The export materializes the requested contents depth and internal chapter links without an empty TOC field or automatic field refresh on opening; it does not guess page numbers. Details cover every graph node and relation, with at most 12 nodes and 18 relations per panel, and a minimum 8pt node-label reading scale. The document policy rejects more than 500 nodes, 1,000 relations or 250 detail panels with HTTP 409; it never silently truncates a graph. Unsupported or conflicting frozen evidence also returns 409.

Use `language=de` for generated German labels. Saved evidence and administrator-authored template text remain unchanged. The decision DOCX continues through the current versioned DOTX body-marker/decorator path; existing custom styles, headers, footers, logos and template identity are preserved. `/api/report/docx` remains the typed, structured **ad-hoc/live** report endpoint and does not assert frozen snapshot provenance.

The scenario acceptance pipeline checks source parity and semantic completeness, and renders both Word artifacts with LibreOffice/Poppler. This is not Microsoft Word product certification.
