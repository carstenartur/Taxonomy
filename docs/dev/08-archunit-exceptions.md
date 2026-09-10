# ArchUnit Exception Inventory

This document tracks the current incremental exceptions enforced by the
architecture fitness functions in `taxonomy-app/src/test/java/com/taxonomy`.

The checked source of truth for temporary cycle and adapter exceptions is
`.github/architecture-exceptions.json`. The target bounded-context extraction map
is `.github/architecture-contexts.json`; its cross-context dependency baseline is
`.github/architecture-dependency-baseline.json`.

Each exception includes:

- why it exists today
- the condition that allows removing it
- an expiry date in the checked ledger

## Controller → repository rule

There are no remaining controller exceptions. Password changes and user/role
administration are delegated to `PasswordChangeService` and
`UserManagementService`; all controller packages are therefore required to stay
repository-free.

## Service implicit workspace-resolution allowlist

These are temporary exceptions to:

- `servicesShouldNotResolveCurrentUsernameImplicitly`
- `servicesShouldNotResolveCurrentWorkspaceContextImplicitly`

1. `com.taxonomy.catalog.service.CatalogFacade`
   - Why: legacy catalog endpoints still build workspace-aware response state internally.
   - Remove when: callers pass resolved username/context explicitly to catalog service methods.
2. `com.taxonomy.dsl.export.DslMaterializeService`
   - Why: DSL materialization still switches between shared/workspace routing internally.
   - Remove when: materialization receives explicit `WorkspaceContext` from request boundary.
3. `com.taxonomy.relations.service.GraphSearchService`
   - Why: graph search still resolves current workspace internally for relation queries.
   - Remove when: graph search APIs accept `WorkspaceContext` from boundary/facade.
4. `com.taxonomy.relations.service.RelationProposalService`
   - Why: proposal CRUD still derives active workspace internally.
   - Remove when: proposal operations receive `WorkspaceContext` from boundary/facade.
5. `com.taxonomy.versioning.service.DslOperationsFacade`
   - Why: remaining DSL/versioning operations still resolve workspace context inside the facade.
   - Remove when: all facade operations become context-explicit.
6. `com.taxonomy.versioning.service.SelectiveTransferService`
   - Why: selective transfer still resolves current workspace/user for navigation state.
   - Remove when: selective transfer APIs become context-explicit.

## Package-level exceptions

`ArchitectureCycleBoundaryTest` records the currently tolerated cycle edges and
adapter-boundary exclusions. `ArchitectureExceptionLedgerTest` requires those
hard-coded exception IDs to match the checked ledger exactly and rejects expired
entries.

Current adapter-boundary exceptions include:

- `taxonomy-dsl` application adapters (`com.taxonomy.dsl.storage`, `com.taxonomy.dsl.export`)
- `taxonomy-export` application adapters (`com.taxonomy.export.service`, `com.taxonomy.export.controller`)

Removal condition for package-level adapter exclusions:

- put each adapter behind a narrow port owned by the bounded context that needs it;
- move the implementation into that context's adapter package/module when the context is extracted;
- keep `taxonomy-dsl`, `taxonomy-export`, `taxonomy-domain`, and `taxonomy-extension-api` framework-free;
- do **not** create a generic catch-all `taxonomy-adapters` module, because that would recreate the broad coupling currently being removed from `taxonomy-app`.

## Cross-context dependency ratchet

`ArchitectureContextDependencyRatchetTest` complements the cycle rule. A dependency
can damage a module boundary before it closes a cycle, so the ratchet records the
current direct class-to-class dependencies between managed bounded contexts per
package pair.

The baseline is deliberately not an allowlist of ideal dependency directions. It
captures the reviewed current state while #628 removes coupling incrementally:

- a new package edge fails;
- growth of an existing edge fails;
- a reduction also fails until the lower value is committed to the baseline, so the improvement cannot silently regress later.

Changing the baseline therefore requires reviewing the architectural direction,
not merely regenerating a file.
