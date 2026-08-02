# Collaborative project requirements through Git

## Purpose

Several people can model requirements independently and integrate them into one architecture model without blending their analyses or overwriting the other contributors' work.

The workflow uses the existing workspace and branch model together with the Git-backed portfolio contract introduced by ADR 0002.

## Recommended workflow

```text
Shared architecture branch
        │
        ├── Alice workspace / branch
        │     ├── P-001 / REQ-A-001
        │     └── confirmed taxonomy mappings
        │
        ├── Bob workspace / branch
        │     ├── P-001 / REQ-B-001
        │     └── confirmed taxonomy mappings
        │
        └── pull / review / semantic merge / publish
              └── one shared architecture model
```

1. Each contributor synchronizes from the shared architecture.
2. Each contributor creates or edits stable project requirements in their own workspace.
3. Before pull or publish, the application automatically projects the relational portfolio into `architecture.taxdsl`.
4. Pull and publish use a three-way block-semantic merge.
5. The merged DSL is materialized into the destination portfolio projection.
6. Reviewers inspect any property-level conflicts and then publish the result.

## Git-backed blocks

The durable collaboration contract contains:

- `project`
- `projectRequirement`
- `requirementVersion`
- canonical `requirement`
- canonical requirement-to-taxonomy `mapping`

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
  x-portfolio-managed: true;
}

requirementVersion P-001 REQ-A-001 2 {
  text: "The solution shall provide encrypted voice communications.";
  contentHash: "...";
  createdBy: "alice";
  x-portfolio-managed: true;
}

requirement P-001__REQ-A-001 {
  title: "Secure voice";
  text: "The solution shall provide encrypted voice communications.";
  x-project-key: "P-001";
  x-requirement-key: "REQ-A-001";
  x-portfolio-managed: true;
}

mapping P-001__REQ-A-001 -> CR-1047 {
  score: 83;
  source: "analysis-snapshot-id";
  x-review-status: "CONFIRMED";
  x-action-status: "REUSE";
  x-portfolio-managed: true;
}
```

## Merge behavior

Independent requirements are combined automatically. Concurrent changes to different properties of the same project or requirement are also combined.

The following edits require review:

- two different texts for the same requirement version,
- deletion on one side and modification on the other,
- contradictory review or action decisions on the same mapping,
- different values for the same project or requirement property.

Conflicts identify the exact block and property:

```text
projectRequirement P-001 REQ-A-001:text
mapping P-001__REQ-A-001 -> CR-1047:x-action-status
```

## Workspace pull and publish

For isolated workspace repositories, the application maintains a private `sync-base` branch. It records the last integrated semantic state and is used as the merge base for the next pull or publish.

A pull therefore computes:

```text
base   = workspace/sync-base
ours   = workspace/main
theirs = shared/draft
```

A publish reverses source and destination but uses the same tracked base. The previous copy-and-replace behavior is no longer used.

## REST operations

```text
GET  /api/projects/git/export
POST /api/projects/git/commit?branch=draft
POST /api/projects/git/materialize?branch=draft
POST /api/projects/git/merge
```

Merge request:

```json
{
  "fromBranch": "alice-requirements",
  "intoBranch": "integration"
}
```

A successful semantic fallback creates a real two-parent merge commit when both branches are in the same repository.

## What remains outside Git

Operational and high-volume data remains relational:

- running analysis jobs,
- retry and rate-limit state,
- complete large analysis payloads,
- caches and diagnostics.

The DSL stores stable identities, current architecture decisions, snapshot references and hashes so that the architecture remains reviewable and verifiable.
