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

`WorkspaceResolver` resolves and caches one `RepositoryContext` per HTTP request. Its legacy `WorkspaceContext` view is constructed from that same object, so portfolio services that still expose compatibility signatures cannot observe another repository or a branch different from the request's canonical selection.

Direct legacy three-argument contexts remain a bounded compatibility path outside productive request resolution. Repository-sensitive code should continue migrating to `RepositoryContext` explicitly.

## Persistence and migration

PostgreSQL migration `V12__scope_portfolio_by_repository_branch.sql`:

1. permits a fresh empty application schema before the repository-catalog initializer creates the first catalogue row;
2. rejects more than one primary repository;
3. requires exactly one primary repository when existing central portfolio rows need deterministic repository and branch provenance;
4. allows workspace-only historic rows without a primary repository only when every referenced workspace already identifies a valid source repository and current branch;
5. rejects portfolio rows that reference an unknown workspace or incomplete workspace provenance;
6. backfills repository, workspace scope and branch from `user_workspace` or the unambiguous primary repository;
7. rejects ambiguous historic central project, solution or product business keys;
8. rewrites legacy scope keys to the reversible v2 identity;
9. makes all tenant columns mandatory and adds repository foreign keys, consistency checks and tenant indexes.

The migration neither deletes portfolio rows nor drops tables. Other supported databases derive the same columns from the JPA mappings and are exercised by the database compatibility matrix.

## Central migration safety

Historic central portfolio data was user-scoped. Central state is now owned by `(repositoryId, CENTRAL, branch)`, independent of the requesting user. Before merging those legacy scopes, the migration rejects case-insensitive duplicate project, solution or product business keys rather than silently selecting one user's row. Operators must resolve such ambiguous records deliberately before retrying the migration.

## Remaining work

This boundary isolates the portfolio roots by repository, workspace or central scope, and branch. #743 remains open for tenant-bound composite foreign keys and exact-context authority checks across every subordinate analysis, decision, provenance and audit row; exact job staleness identity; lifecycle purge/copy evidence; audit and status telemetry; and reviewed migration rollback evidence.
