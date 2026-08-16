# Multi-repository portfolio tenancy

Portfolio data is owned by an exact logical architecture context. The isolation key is not a user name and is not inferred from a nullable workspace.

## Identity

Every project, reusable solution and product catalogue entry stores:

- `repository_id` — the selected logical `SystemRepository`;
- `workspace_scope` — `WORKSPACE:<workspaceId>` or the explicit central scope `CENTRAL`;
- `branch_name` — the exact selected Git branch;
- `scope_key` — a reversible, length-prefixed representation of those three values.

The encoded format is:

```text
v2|r<length>:<repositoryId>|s<length>:<workspaceScope>|b<length>:<branch>
```

Lengths are Unicode code-point counts. This avoids delimiter collisions and allows the PostgreSQL migration and Java parser to produce identical values.

## Request boundary

`WorkspaceResolver` resolves and caches one `RepositoryContext` per HTTP request. Its legacy `WorkspaceContext` view is enriched from that same object, so portfolio services that still expose compatibility signatures cannot observe another repository or a stale branch.

A mismatch between the legacy workspace and the canonical repository context fails closed.

## Synchronization boundary

Portfolio-to-Git synchronization carries the same identity through pull, publish and materialisation:

- active-user synchronization resolves `RepositoryContext`, not the legacy user/workspace-only context;
- explicitly addressed workspace endpoints derive the repository ID from persisted `source_repository_id` provenance;
- a request context that disagrees with that persisted source repository fails before Git or portfolio mutation;
- central synchronization opens the selected logical repository rather than the primary-repository compatibility handle;
- publishing a workspace materialises both the workspace and the exact central `(repositoryId, CENTRAL, sourceBranch)` scope, including non-primary repositories;
- `WorkspaceContext.SHARED` is not used as portfolio authority in these productive synchronization paths.

Direct three-argument `WorkspaceContext` construction remains only as a bounded compatibility API for callers that have not yet migrated. It must not be introduced into repository-sensitive production paths.

## Persistence and migration

PostgreSQL migration `V12__scope_portfolio_by_repository_branch.sql`:

1. permits a fresh empty installation before the repository-catalog initializer creates its first row;
2. permits workspace-only historic data when every workspace already has exact repository and branch provenance;
3. rejects more than one primary repository and requires exactly one when existing central portfolio rows need deterministic backfill;
4. rejects portfolio rows that reference an unknown workspace or incomplete workspace repository/branch provenance;
5. backfills repository, workspace scope and branch from `user_workspace` or the unambiguous primary repository;
6. rewrites legacy scope keys to the reversible v2 identity;
7. makes all tenant columns mandatory and adds repository foreign keys and tenant indexes.

Other supported databases derive the same columns from the JPA mappings and are exercised by the database compatibility matrix.

## Central migration safety

Historic central portfolio data was user-scoped. Central state is now owned by `(repositoryId, CENTRAL, branch)`, independent of the requesting user. Before merging those legacy scopes, the migration rejects case-insensitive duplicate project, solution or product business keys rather than silently selecting one user's row. Operators must resolve such ambiguous records deliberately before retrying the migration.

## Remaining work

This boundary prevents cross-repository, cross-workspace and cross-branch addressing for the three portfolio roots and their Git materialisation. #743 remains open for tenant-bound composite foreign keys across every subordinate requirement, analysis, decision, provenance and audit row; exact job staleness identity; export/deletion/copy lifecycle evidence; audit/status telemetry; and reviewed migration rollback evidence.
