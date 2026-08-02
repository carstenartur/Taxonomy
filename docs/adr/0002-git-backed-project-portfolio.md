# ADR 0002: Git-backed project requirement portfolio and semantic merge

- **Status:** Accepted
- **Date:** 2026-08-02
- **Related:** #546, PR #547

## Context

The first portfolio increment stored projects, stable requirements, immutable requirement versions, analysis snapshots, solutions and products relationally. The architecture DSL was already Git-backed, but a pull or push between isolated workspace repositories copied the complete `architecture.taxdsl` file. Two people could therefore model independently, but publishing one complete file could overwrite the other person's requirements.

Line-oriented Git conflicts are also unnecessarily broad for the deterministic DSL. Two contributors adding different requirement blocks should merge automatically even when both additions occur near the same serialized location.

## Decision

### Durable business state is projected into the canonical DSL

The following information is written into `architecture.taxdsl` before pull or push:

- `project` blocks,
- `projectRequirement` blocks with stable project-qualified identities,
- every immutable `requirementVersion`,
- a canonical typed `requirement` block for the current version,
- current `mapping` blocks from requirements to taxonomy elements, including review and action metadata.

Operational job state, rate-limit information, caches and large immutable analysis payloads remain relational. The Git document stores their stable references and hashes rather than duplicating operational data.

### Portfolio-owned blocks are replaceable projections

A projection replaces only blocks carrying `x-portfolio-managed: true` and the dedicated portfolio block kinds. Elements, relations, views, evidence and manually maintained DSL content remain untouched.

### Semantic three-way merge

The normal JGit merge remains the fast path. If the canonical DSL file conflicts textually, the system performs a semantic three-way merge using:

- merge base,
- target branch (`ours`),
- incoming branch (`theirs`).

Blocks are identified by block kind plus all header tokens. This makes relation and mapping identities unambiguous and permits project-qualified portfolio blocks.

Rules:

1. A block added on only one side is retained.
2. Different blocks added independently are combined.
3. Different properties of the same block are combined.
4. The same property changed differently on both sides is a conflict.
5. Delete-versus-modify is a conflict.
6. Blocks with repeated property keys, such as views with several `include` entries, are merged atomically when both sides changed them.

Conflict identifiers name the exact block and property, for example:

```text
projectRequirement P-001 REQ-001:text
mapping P-001__REQ-001 -> CR-1047:x-action-status
```

### Same-repository branch merges preserve Git ancestry

When semantic fallback is needed inside one repository, the resulting commit has both branch heads as parents. It is therefore a real merge commit, not an overwrite commit.

### Cross-repository workspace synchronization uses a tracked merge base

Each isolated workspace repository maintains a `sync-base` branch. Pull and push use the DSL at that branch as the common semantic base:

```text
base   = workspace/sync-base
ours   = destination HEAD
other  = source HEAD
```

After a successful synchronization, `sync-base` is advanced to the merged content. This prevents a later contributor from overwriting changes that were already integrated.

### Materialization follows every successful merge

After branch merge, pull or push, the merged project and requirement blocks are materialized into the destination workspace projection. Materialization is additive/updating. A missing block does not silently delete a business record; deletion and archival remain explicit reviewed operations.

## Consequences

### Positive

- Different people can model requirements on separate branches or workspace repositories.
- Independent requirements merge automatically into one architecture model.
- Requirement text conflicts are precise and reviewable.
- The shared architecture preserves requirement IDs, versions, provenance and taxonomy mappings.
- Pull/push no longer replaces the complete destination model.
- The relational UI is rebuilt from the merged Git source after synchronization.

### Trade-offs

- Cross-repository synchronization creates a semantic content commit rather than transferring the complete foreign Git object graph.
- Large analysis payloads remain external to Git and are verified through stable references and fingerprints.
- Removing a requirement block does not automatically delete the relational requirement.
- Product and solution projections can be expanded using the same generic block contract, but requirement identity and architecture mapping are the mandatory first merge boundary.

## Rejected alternatives

### Keep database-only requirements

Rejected because database rows from isolated workspaces cannot be reviewed, pushed, pulled or semantically merged as architecture contributions.

### Copy the complete workspace DSL on publish

Rejected because the last publisher can erase previously integrated requirements.

### Use only line-oriented merge

Rejected because deterministic reordering and nearby independent block additions create avoidable conflicts and poor conflict explanations.

### Store every LLM payload in Git

Rejected because operational payload volume would dominate architecture history. Immutable payloads remain in persistence; stable results and decisions are projected into the DSL.
