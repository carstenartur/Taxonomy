# Collaborative project portfolios through Git

## Purpose

Several people can model requirements, solution decisions and product evaluations independently and integrate them into one architecture model without blending analyses or overwriting other contributors' work.

The workflow uses the existing workspace and branch model together with the Git-backed portfolio contract introduced by ADR 0002.

## Architecture lifetime, contributions and history

The repository identifies the continuing architecture, not one project's lifetime.
Completing a project does not close or replace the architecture. A follow-up project
can change or reuse an original requirement while retaining its project-qualified
identity and recording the later project as a contributor. `NEW`, `CHANGE` and
`LINK` are explicit [intake decisions](PROJECT_REQUIREMENT_PORTFOLIO.md#requirement-intake).

Current text, immutable analysis snapshots and reviewed realization are different
records. A realization review binds one exact text version/hash to architecture
dependency hashes, contributor, actor and rationale. Later text or dependency
changes require renewed review; the prior assessment remains traceable. An older
Git checkpoint or semantic revision is read-only and uses its own source without
today's portfolio overlay. It can show version 1 while the current requirement
header shows version 2.

The [requirement history](PROJECT_REQUIREMENT_PORTFOLIO.md#requirement-history)
searches reachable Git ancestry through native JGit and `jgit-storage-hibernate`.
It does not require product-side shell Git commands or a second audit database.
The response identifies the captured HEAD, actual parents, sources, signature
evidence and bounded coverage. Continue using the same HEAD; restart when it moves.
An empty limited page or unavailable storage is not proof of absent history.

Accepted lifecycle changes prepare a checkpoint in the same transaction as their
business contribution and editor journal. Git publication follows that transaction.
`CHECKPOINT_PENDING` retains the intent exposed by `pendingCheckpointId`; an exact
retry or the existing resume operation completes it without repeating the business
change. A changed Git HEAD can require explicit
[version reconciliation](ARCHITECTURE_EDITOR.md#editor-version-reconciliation).
Inspect base/local/incoming source, choose every conflict and preview the result
again before accepting. Repeated saves alone do not resolve conflicts.

Reconciliation preserves immutable requirement versions and validates canonical
title/text/version/hash against the portable current version. Changed managed
portfolio blocks materialize atomically with the editor journal before Git
publication; exact retries do not materialize twice. Unsupported omission of
business blocks requires retaining `LOCAL` and explicit archival. Conflicting
portfolio choices roll back acceptance; later Git storage interruption remains a
pending-checkpoint recovery case. See the [API contract](PROJECT_PORTFOLIO_API.md#editor-version-reconciliation-api).

## Recommended workflow

```text
Shared architecture branch
        │
        ├── Alice workspace / branch
        │     ├── P-001 / REQ-A-001
        │     ├── confirmed taxonomy mappings
        │     └── solution SOL-A / product candidate PRD-A
        │
        ├── Bob workspace / branch
        │     ├── P-001 / REQ-B-001
        │     ├── confirmed taxonomy mappings
        │     └── solution SOL-B / product candidate PRD-B
        │
        └── pull / review / semantic merge / publish
              └── one shared architecture and solution model
```

1. Each contributor synchronizes from the shared architecture.
2. Requirements, solution proposals and product evaluations are edited in the contributor's own workspace.
3. Before pull or publish, the application projects the relational portfolio into `architecture.taxdsl`.
4. Pull and publish use a three-way block-semantic merge.
5. The merged DSL is materialized into the destination projection.
6. Only genuine property conflicts require domain review.

## Git-backed blocks

The durable collaboration contract contains:

### Projects and requirements

- `project`
- `projectRequirement`
- `requirementVersion`
- canonical `requirement`
- canonical requirement-to-taxonomy `mapping`

### Solutions and products

- `solutionDefinition`
- `solutionTaxonomyCoverage`
- `projectSolutionDecision`
- `requirementSolutionDecision`
- `productDefinition`
- `productTaxonomyCoverage`
- `solutionProductDecision`

All references use stable business keys such as `P-001`, `REQ-A-001`, `SOL-001` and `PRD-001`. Database primary keys are not part of the Git contract.

Example:

```text
project P-001 {
  title: "Joint target architecture";
  status: ACTIVE;
  x-portfolio-managed: true;
}

projectRequirement P-001 REQ-A-001 {
  title: "Secure voice";
  owner: "alice";
  reviewStatus: CONFIRMED;
  currentVersionNumber: 2;
  x-portfolio-managed: true;
}

requirementVersion P-001 REQ-A-001 2 {
  text: "The solution shall provide encrypted voice communications.\nIt shall remain available during a network outage.";
  contentHash: "...";
  createdBy: "alice";
  x-portfolio-managed: true;
}

mapping P-001__REQ-A-001 -> CR-1047 {
  score: 83;
  source: "analysis-snapshot-id";
  x-review-status: "CONFIRMED";
  x-action-status: "REUSE";
  x-portfolio-managed: true;
}

solutionDefinition SOL-001 {
  title: "Secure communications service";
  solutionType: "SERVICE";
  lifecycleStatus: "ACTIVE";
  x-portfolio-managed: true;
}

projectSolutionDecision P-001 SOL-001 {
  status: "SELECTED";
  actionStatus: "REUSE";
  priority: 90;
  x-portfolio-managed: true;
}

productDefinition PRD-001 {
  manufacturer: "Example Vendor";
  productName: "Example Product";
  editionVersion: "2026.1";
  sourceReference: "Verified vendor documentation";
  verifiedAt: "2026-08-02T18:00:00Z";
  x-portfolio-managed: true;
}

solutionProductDecision P-001 SOL-001 PRD-001 {
  coveragePercent: 92;
  reviewStatus: "CONFIRMED";
  selectionStatus: "SELECTED";
  x-portfolio-managed: true;
}
```

Multiline text is stored with `\n`, `\r` and `\t` escapes on one physical DSL line and restored exactly on read. This keeps Git diffs stable without losing requirement content.

## Merge behavior

Independent requirements, solutions and products are combined automatically. Concurrent edits to different properties of the same block are also combined.

The following edits require review:

- two different texts for the same requirement version,
- deletion on one side and modification on the other,
- contradictory review or action decisions on the same mapping,
- different selections for the same product and project solution,
- different values for the same project, requirement, solution or product property.

Conflicts identify the exact block and property:

```text
projectRequirement P-001 REQ-A-001:title
mapping P-001__REQ-A-001 -> CR-1047:x-action-status
projectSolutionDecision P-001 SOL-001:actionStatus
solutionProductDecision P-001 SOL-001 PRD-001:selectionStatus
```

## Workspace pull and publish

For isolated workspace repositories, the application maintains a private `sync-base` branch. It records the last integrated semantic state and is used as the merge base for the next pull or publish.

A pull computes:

```text
base   = workspace/sync-base
ours   = workspace/<current branch>
theirs = shared/draft
```

Publish reverses source and destination. The former copy-and-replace behavior is no longer used. After publish, the local workspace also receives the complete merged state so the next pull starts from the same base.

The normal workspace endpoints use this workflow automatically:

```text
POST /api/workspace/sync-from-shared
POST /api/workspace/publish
POST /api/workspace/resolve-diverged
```

These operations are available to `ARCHITECT` and `ADMIN`. Read-only users cannot publish architecture changes.

## Explicit portfolio Git operations

```text
GET  /api/projects/git/export
POST /api/projects/git/commit?branch=draft
POST /api/projects/git/materialize?branch=draft
POST /api/projects/git/merge
```

Merge request:

```json
{
  "sourceBranch": "alice-requirements",
  "targetBranch": "integration"
}
```

Both branches must be in the selected workspace repository. The endpoint also accepts
`?source=alice-requirements&target=integration` with an optional JSON message.

A successful semantic fallback creates a real two-parent merge commit when both branches are in the same repository.

## What remains outside Git

Operational and high-volume data remains relational:

- running analysis jobs,
- retry and rate-limit state,
- complete large analysis payloads,
- caches and diagnostics.

Snapshot IDs can be retained as origin metadata but are not assumed to be valid foreign keys when importing into another workspace. The DSL stores stable identities, reviewed architecture decisions, source references and hashes so the shared architecture, solution and product model remains reviewable and verifiable.
