# Workspace and Git Context Boundaries

## Intended rule

Resolve workspace and repository context once at the request boundary, then pass it through explicitly:

1. **Request boundary resolves context**
   - controllers/facades resolve the authenticated username
   - request flow provisions workspace state if needed
   - request flow resolves `WorkspaceContext` once
2. **Use cases receive `WorkspaceContext` explicitly**
   - command objects should carry the username plus `WorkspaceContext` when repository/view state is needed
3. **Lower-level services do not resolve current user implicitly**
   - services should accept `WorkspaceContext` (and username where needed) as parameters
   - services should not call `WorkspaceResolver.resolveCurrentContext()`
   - services should not call `WorkspaceResolver.resolveCurrentUsername()`
   - services should not call `WorkspaceContextResolver.resolveCurrentContext()`

## Incremental pattern

Use this sequence when a request needs workspace-aware repository routing:

1. `String username = workspaceResolver.resolveCurrentUsername();`
2. `repositoryStateService.ensureWorkspaceState(username);`
3. `WorkspaceContext ctx = workspaceResolver.resolveCurrentContext();`
4. pass `username` and `ctx` into the use case/facade/service call chain

This avoids request flows where an early call uses `SHARED` and a later call uses the user's provisioned workspace repository.

## Refactored examples in this repository

- `AnalysisApiController` now resolves `username` + `WorkspaceContext` once and passes both through `AnalyzeRequirementCommand`.
- `AnalyzeRequirementUseCase` now uses the explicit command context when attaching `viewContext`.
- `DslApiController` now resolves `username` + `WorkspaceContext` once before workspace-aware history/current response state is loaded through `DslOperationsFacade`.
- `HypothesisService` now receives explicit `WorkspaceContext` for persistence, listing, evidence access, acceptance, rejection, and session application; cross-workspace IDs are treated as not found.

## Current internal implicit-resolution inventory

The following service classes still resolve current request context internally and are temporarily allowlisted by `taxonomy-app/src/test/java/com/taxonomy/ArchitectureTest.java`:

- `com.taxonomy.catalog.service.CatalogFacade` — legacy catalog endpoints still build workspace-aware response state internally.
- `com.taxonomy.dsl.export.DslMaterializeService` — materialization still switches between shared/workspace routing internally.
- `com.taxonomy.relations.service.GraphSearchService` — graph search still resolves the current workspace for relation queries.
- `com.taxonomy.relations.service.RelationProposalService` — proposal CRUD still derives the active workspace internally.
- `com.taxonomy.versioning.service.DslOperationsFacade` — several remaining DSL/versioning operations still resolve workspace context inside the facade.
- `com.taxonomy.versioning.service.SelectiveTransferService` — selective transfer still resolves the current workspace/user for navigation state.

## Architectural enforcement

`ArchitectureTest` now fails when a new Spring `@Service` class outside the documented allowlist calls:

- `WorkspaceResolver.resolveCurrentUsername()`
- `WorkspaceResolver.resolveCurrentContext()`
- `WorkspaceContextResolver.resolveCurrentContext()`

When a temporary exception is unavoidable, document it here with the owning class and the reason before adding it to the allowlist.
