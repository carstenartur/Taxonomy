# ADR 0002: Git-backed project, requirement, solution and product portfolio

- **Status:** Accepted
- **Date:** 2026-08-02
- **Related:** #546, PR #547

## Context

Taxonomy already versions the canonical architecture DSL with JGit, while the project portfolio originally stored its durable business objects only in relational tables. That split prevented two architects from modelling requirements independently and then combining the complete project portfolio through normal pull, push and merge operations.

Copying a complete `architecture.taxdsl` file between isolated workspace repositories is not sufficient: concurrent additions can overwrite one another, and a line-oriented conflict describes serialization layout rather than business identity.

The collaboration contract must also remain portable across different workspaces and databases. Relational primary keys and target-workspace snapshot foreign keys therefore cannot be Git identities.

## Decision

### Git is the authoritative collaboration history

Durable, human-reviewable portfolio state is projected into `architecture.taxdsl` before commit, pull or publish and materialized into the destination relational projection after a successful integration.

The contract contains stable-key blocks for:

- projects,
- requirements and every immutable text version,
- requirement-to-taxonomy mappings and review/action decisions,
- reusable solution definitions and taxonomy coverage,
- project-specific solution decisions,
- requirement-to-solution decisions,
- sourced product definitions and taxonomy coverage,
- reviewed solution-to-product decisions.

Database primary keys are projection details only. References use stable business keys such as project key, requirement key, solution key and product key.

### Operational data remains relational

The following data is intentionally not copied into Git:

- running analysis jobs and retry state,
- rate-limit and queue state,
- full large analysis payloads,
- caches and diagnostics.

Snapshot identifiers can be retained as provenance strings. When materializing another workspace they are not assumed to be valid foreign keys.

### Portfolio-owned blocks are replaceable projections

A projection replaces only blocks carrying `x-portfolio-managed: true` and dedicated portfolio block kinds. Elements, relations, views, evidence and manually maintained DSL content remain untouched.

The durable block kinds include:

```text
project
projectRequirement
requirementVersion
requirement
mapping
solutionDefinition
solutionTaxonomyCoverage
projectSolutionDecision
requirementSolutionDecision
productDefinition
productTaxonomyCoverage
solutionProductDecision
```

### Deterministic, lossless serialization

TaxDSL output is deterministically sorted. Newlines, carriage returns and tabs in requirement, evidence or source text are represented as `\n`, `\r` and `\t`, then restored exactly on parsing. Quotation marks and backslashes are escaped as well. This keeps each property on one physical line without losing content.

### Semantic three-way merge

The normal JGit merge remains the fast path. If the canonical DSL conflicts textually, the system performs a semantic three-way merge using:

- merge base,
- target state (`ours`),
- incoming state (`theirs`).

A block is identified by its kind and complete header, for example:

```text
projectRequirement P-001 REQ-A-001
projectSolutionDecision P-001 SOL-001
solutionProductDecision P-001 SOL-001 PRD-001
```

Rules:

1. A block added on only one side is retained.
2. Different blocks added independently are combined.
3. Different properties of the same block are combined.
4. The same property changed differently on both sides is a conflict.
5. Delete-versus-modify is a conflict.
6. Blocks with repeated property keys are merged atomically when both sides changed them.

Conflict identifiers name the exact block and property:

```text
projectRequirement P-001 REQ-A-001:title
mapping P-001__REQ-A-001 -> CR-1047:x-action-status
projectSolutionDecision P-001 SOL-001:actionStatus
solutionProductDecision P-001 SOL-001 PRD-001:selectionStatus
```

### Same-repository merges preserve Git ancestry

When semantic fallback is needed inside one repository, the resulting commit has both branch heads as parents. It is therefore a real merge commit, not an overwrite commit.

### Isolated workspaces use a tracked merge base

Each isolated workspace repository maintains a private `sync-base` branch containing the last integrated semantic state.

Pull uses:

```text
base   = workspace/sync-base
ours   = workspace/<active branch>
theirs = shared/draft
```

Publish reverses source and target but uses the same tracked base. The merged result is committed to the shared repository and back to the local active branch. The old copy-and-replace implementation is no longer used.

The persistent active workspace branch is authoritative. A stale volatile default such as `draft` must not redirect an operation away from the provisioned `main` or a selected variant branch.

### Materialization follows every successful integration

After branch merge, pull or publish, the merged portfolio is materialized into the destination workspace projection. Materialization is additive/updating. A missing block does not silently delete a business record; retirement and archival remain explicit reviewed decisions.

A product can remain `SELECTED` after materialization only when its review is `CONFIRMED` and it has no hard exclusion. Invalid imported selections are reduced to a candidate state and reported as warnings.

### Authorization

Pull, publish and semantic divergence resolution mutate architecture history and require `ARCHITECT` or `ADMIN`. Read-only users cannot publish changes. Global workspace administration remains restricted to `ADMIN`.

## Consequences

### Positive

- Different people can model requirements, solutions and product evaluations on separate branches or workspaces.
- Independent contributions merge automatically into one architecture model.
- Requirement text and decision conflicts are precise and reviewable.
- Git history records who introduced or changed every durable decision.
- The same DSL can rebuild a portfolio in another workspace or database.
- Pull and publish no longer replace the complete destination model.
- Product selection retains source and verification provenance.

### Trade-offs

- Cross-repository synchronization creates semantic content commits rather than transferring the complete foreign Git object graph.
- Large analysis payloads remain external to Git and are verified through stable references and fingerprints.
- Removing a block does not automatically delete its relational projection.
- Changing the same requirement text or decision on two branches still requires human resolution.
- New durable portfolio concepts require stable DSL block contracts and backward-compatible materialization.

## Rejected alternatives

### Keep database-only portfolio objects

Rejected because rows from isolated workspaces cannot be reviewed, pushed, pulled or semantically merged as architecture contributions.

### Copy the complete workspace DSL on publish

Rejected because the last publisher can erase previously integrated requirements or decisions.

### Use only line-oriented merge

Rejected because deterministic reordering and nearby independent block additions create avoidable conflicts and poor conflict explanations.

### Store every LLM payload in Git

Rejected because operational payload volume would dominate architecture history. Immutable payloads remain in persistence; stable results and reviewed decisions are projected into the DSL.

## Verification

The implementation is covered by tests for:

- independent requirement additions,
- changes to different properties of one block,
- same-property conflicts and delete-versus-modify,
- real two-parent semantic merge commits,
- isolated cross-repository pull and publish,
- persistent active-branch resolution,
- complete requirement-version roundtrip,
- multiline text roundtrip,
- solution, project-solution, product and selected-product roundtrip across workspaces,
- contradictory product selections reported as semantic conflicts.
