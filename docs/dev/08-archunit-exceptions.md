# ArchUnit Exception Inventory

This document tracks the current incremental exceptions used by
`taxonomy-app/src/test/java/com/taxonomy/ArchitectureTest.java`.

Each exception includes:

- why it exists today
- the condition that allows removing it

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

These are documented in the Javadoc of `ArchitectureTest` rules:

- cycle check exclusions for known cross-cutting shared packages
- `taxonomy-dsl` adapter exclusions (`com.taxonomy.dsl.storage`, `com.taxonomy.dsl.export`)
- `taxonomy-export` adapter exclusions (`com.taxonomy.export.service`, `com.taxonomy.export.controller`)

Removal condition for package-level exclusions:

- remove each exclusion once the adapter package is moved out of framework-free module scope
  into a dedicated adapter module/package boundary.
