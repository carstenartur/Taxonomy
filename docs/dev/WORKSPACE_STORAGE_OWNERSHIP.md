# Workspace storage ownership

## Owner and API

The JGit-backed DSL repository implementation belongs to the workspace bounded
context under `com.taxonomy.workspace.storage`. The eleven repository, factory,
configuration, branch/commit value, expected-head and workspace adapter types
consume the existing `WorkspaceDslReadPort`, `WorkspaceDslVersionPort` and
`WorkspaceDslPublicationPort` contracts. The move changes their internal Java
namespace; it does not add a compatibility facade or a new port.

Workspace remains responsible for repository and branch identity, system versus
selected-repository routing, exact-version reads, exact-head compare-and-set
conflicts, Git checkpoint publication, merge/diff/version behavior and recovery.
Accepted semantic operations remain distinct from explicit Git checkpoints.

## Persisted behavior

The relocation does not change SQL resources, Flyway checksums, tables, indices,
history-table handling, legacy adoption, database-family support, mappings,
transactions or bootstrap order. `JgitStorageSchemaMigrationConfig` remains the
Core schema owner. The qualified `jgitStorageFlywayMigrationStrategy` bean is
still consumed first by the application strategy in
`com.taxonomy.composition.persistence`; its ten application-schema integration
tests remain with that application owner.

## Runtime, observation and build consumers

Both Hibernate profiles name
`com.taxonomy.workspace.storage.JgitStorageHibernateSchemaFilterProvider`.
OpenTelemetry method instrumentation targets the workspace-owned repository
factory and repository with the same method lists, and Micrometer observation
uses the moved factory class name with unchanged operation and metric names.

The consumer contract expects the five moved storage test reports under
`com.taxonomy.workspace.storage`. Its workflow watches both the former paths,
which remain useful for deletion changes, and the new workspace storage source
and test paths. Coverage keeps the independent storage floor of 87% line and
71% branch coverage at the new package and retains the repository-wide 75% line
and 60% branch changed-source minimums. The architecture profile includes an
ownership guard that requires all eleven types at their workspace names and
rejects production classes in the former DSL storage package.

## Measured dependency graph

The root-owned native architecture run measured fresh production bytecode. The
relocation reduces the checked graph from **537 to 472 cross-context class
pairs** and from **146 to 140 package edges**. The entire measured graph equals
the namespace-only projection of the previous baseline; no edge change is
unexplained.

The **42 workspace-to-storage pairs** and **23 storage-to-workspace pairs** are
now internal to the workspace context, removing six cross-context package edges
and 65 cross-context class pairs. Workspace has **zero outgoing class pairs to
other managed application contexts**. Three incoming package edges retarget to
the workspace storage owner: composition DSL controller (1 pair), composition
DSL service (2 pairs) and portfolio service (5 pairs).

The **117 knowledge-to-workspace pairs** remain. A supplemental inventory
enumerated all 117 and found zero knowledge-to-former-storage pairs, so this
slice does not reclassify or redesign that coupling.

## Remaining extraction limits

This ownership move does not create a Maven module or close #628/#1043. Physical
extraction remains subject to the knowledge-to-workspace coupling review and to
dependencies on foundation modules that are outside the managed application
context graph. Canonical database, recovery, UI, security and current-head CI
lanes remain required before merge.
