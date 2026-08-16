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

## Persistence and migration

PostgreSQL migration `V12__scope_portfolio_by_repository_branch.sql`:

1. verifies that exactly one primary repository exists;
2. rejects portfolio rows that reference an unknown workspace;
3. backfills repository, workspace scope and branch from `user_workspace` or the primary repository;
4. rewrites legacy scope keys to the reversible v2 identity;
5. makes all tenant columns mandatory and adds repository foreign keys and tenant indexes.

Other supported databases derive the same columns from the JPA mappings and are exercised by the database compatibility matrix.

## Central migration safety

Historic central portfolio data was user-scoped. Central state is now owned by `(repositoryId, CENTRAL, branch)`, independent of the requesting user. Before merging those legacy scopes, the migration rejects case-insensitive duplicate project, solution or product business keys rather than silently selecting one user's row. Operators must resolve such ambiguous records deliberately before retrying the migration.

## Remaining work

This boundary prevents cross-repository, cross-workspace and cross-branch portfolio reads. #743 remains open for tenant-bound composite foreign keys across every subordinate analysis/decision row, exact job staleness identity, audit/status telemetry and reviewed migration rollback evidence.
