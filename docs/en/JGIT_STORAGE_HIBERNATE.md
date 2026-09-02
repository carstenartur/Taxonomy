# Hibernate-backed JGit storage

Taxonomy stores Architecture DSL and preference history in relational database tables through the released [`jgit-storage-hibernate-core`](https://github.com/carstenartur/jgit-storage-hibernate) library. Taxonomy does not contain a copied implementation of JGit's DFS object, pack, reftable or reflog storage.

## Responsibility boundary

| Taxonomy owns | `jgit-storage-hibernate-core` owns |
|---|---|
| DSL file name, parser, semantic diff and architecture workflows | JGit DFS repository implementation |
| Branch, merge, cherry-pick, revert and workspace orchestration | Pack, object, ref and reflog persistence |
| Logical repository names and exact workspace routing | Transactional repository-scoped storage operations |
| Authorization, audit, REST, UI and application-level recovery | Core entities and the versioned Core/adoption SQL resources |
| Selection of an explicitly supported database migration path | Public migration locations and physical Core schema changes |
| Application projections and Hibernate Search indexes | Library storage internals and storage schema compatibility |

Taxonomy consumes public types from `io.github.carstenartur.jgit.storage.hibernate` and public JGit APIs. Application code must not import the library's `repository`, `objects` or `refs` implementation packages. Conversely, the library does not own Taxonomy's tenant, architecture, UI or application-projection semantics.

## Released dependency and anonymous package access

The root POM is the source of truth for both the released dependency and its repository:

```xml
<jgit-storage-hibernate.version>0.11.3</jgit-storage-hibernate.version>
```

```xml
<repository>
  <id>jgit-storage-hibernate-releases</id>
  <name>jgit-storage-hibernate anonymous releases</name>
  <url>https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository/</url>
  <releases><enabled>true</enabled></releases>
  <snapshots><enabled>false</enabled></snapshots>
</repository>
```

The application module uses the property rather than repeating the release number:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>${jgit-storage-hibernate.version}</version>
</dependency>
```

The configured release repository is publicly and anonymously readable. A clean consumer build therefore needs no Maven server credentials or package-read token. The repository is not Maven Central; its exact ID, URL and release-only policy remain part of Taxonomy's reproducible dependency contract until distribution changes in a reviewed update.

`JgitStorageDocumentationContractTest` reads the version and repository block from the root POM and compares both language guides with that source. It also rejects the obsolete authenticated-package access model.

## Spring-managed persistence context

The library's Core entities live outside `com.taxonomy`, so the application explicitly includes `io.github.carstenartur.jgit.storage.hibernate.entity` in `@EntityScan`. Integration tests compare the resulting JPA metamodel with `CoreEntities.annotatedClasses()` so a future public entity addition cannot be missed silently.

Spring remains the owner of the `EntityManagerFactory` and native Hibernate `SessionFactory`:

```java
@Bean
HibernateRepositoryFactory hibernateRepositoryFactory(
        EntityManagerFactory entityManagerFactory) {
    SessionFactory sessionFactory =
            entityManagerFactory.unwrap(SessionFactory.class);
    return new DefaultHibernateRepositoryFactory(sessionFactory);
}
```

A `HibernateGitStorage` handle owns only the opened JGit repository. Closing a handle must never close the application-managed `SessionFactory`.

## Logical repositories

The physical storage tables are shared, but every operation is scoped by an exact logical repository name:

| Purpose | Logical name |
|---|---|
| Shared system DSL | `taxonomy-dsl` |
| Workspace DSL | `ws-<workspace-id>` |
| Preferences | `taxonomy-preferences` |

`DslGitRepositoryFactory` caches open handles. Cache eviction closes a handle but preserves database rows. Hard workspace deletion closes the handle first and then calls `HibernateRepositoryFactory.deleteRepository(...)`, which removes only the requested logical repository. A reopened persistent repository is not seeded again when it already contains refs.

## Ref updates and reflogs

All productive Taxonomy ref mutations set the expected old object ID, the new object ID, the actor with `setRefLogIdent(...)`, and an operation-specific message with `setRefLogMessage(...)`.

Every `RefUpdate.Result` is checked. Rejected, locked or missing-object results fail the operation instead of being logged as success. The library commits reftable and queryable reflog state inside its repository-scoped transaction. Taxonomy reads reflogs through the public JGit API:

```java
repository.getReflogReader("refs/heads/draft").getLastEntry();
```

## Schema and migration authority

The released library owns the immutable SQL resources and exposes their stable locations through `CoreSchemaMigrations`. Taxonomy owns the decision whether one of those streams is enabled for a product database profile. Availability in the library is therefore not the same as an activated and certified Taxonomy path.

The pinned artifact exposes these public locations:

| Stream | Public constant | Packaged classpath location |
|---|---|---|
| HSQLDB Core | `CoreSchemaMigrations.HSQLDB_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/hsqldb` |
| HSQLDB pre-library adoption | `CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/adoption/hsqldb` |
| PostgreSQL Core | `CoreSchemaMigrations.POSTGRESQL_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/postgresql` |
| PostgreSQL pre-library adoption | `CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/adoption/postgresql` |
| Microsoft SQL Server Core | `CoreSchemaMigrations.SQL_SERVER_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/sqlserver` |
| Microsoft SQL Server pre-library adoption | `CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/adoption/sqlserver` |

The normal HSQLDB, PostgreSQL and SQL Server streams packaged in the pinned artifact contain migrations `0.1.4`, `0.1.5`, `0.1.14`, `0.1.14.1`, `0.1.14.2`, `0.1.17`, `0.1.18`, `0.9.1` and `0.9.2`. The HSQLDB and PostgreSQL adoption streams contain V1 and V2 for the pre-library Taxonomy schema. The SQL Server adoption stream contains its own V1 and V2 for the pre-library/Sandbox schema. `CoreSchemaMigrations.LEGACY_ADOPTION_VERSION` is `2`.

These numbers describe the resources in the exact resolved dependency; Taxonomy does not carry copied migration SQL. The documentation contract checks that every named final resource is present on the test classpath, so an upgraded artifact cannot leave this guide silently describing a different package.

Spring Boot Flyway uses dedicated history tables:

- `CoreSchemaMigrations.SCHEMA_HISTORY_TABLE` → `jgit_storage_hibernate_core_schema_history`;
- `CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE` → `jgit_storage_hibernate_core_adoption_history`.

`JgitStorageHibernateSchemaFilterProvider` keeps the mapped library-owned tables `git_packs`, `git_reflog`, `git_repository_lock` and `git_pack_chunks` outside Hibernate create, update, truncate and drop operations while retaining schema validation. The Core migration stream also owns any additional storage structures it creates, including repository lifecycle state. Taxonomy must not introduce application-authored DDL for those structures.

## Database paths actually supported by Taxonomy

`JgitStorageSchemaMigrationConfig.DatabaseFamily` currently selects only HSQLDB and PostgreSQL. SQL Server's upstream migration assets are present in the pinned release, but the Taxonomy SQL Server profile does not yet activate them. Oracle has no public Core/adoption migration location in the pinned library.

| Database | Upstream pinned-release migration assets | Taxonomy-managed Core migration/adoption | Current evidence and limitation |
|---|---|---:|---|
| HSQLDB | Core plus Taxonomy adoption V1/V2 | yes | Default/local path and direct Maven/JUnit migration tests |
| PostgreSQL | Core plus Taxonomy adoption V1/V2 | yes | Testcontainers migration, persistence and database-matrix coverage |
| Microsoft SQL Server | Core plus SQL Server adoption V1/V2 | no | `application-mssql.properties` keeps Flyway disabled; application/dialect checks do not certify the complete Core migration, adoption, restart and persistence path |
| Oracle | no Core or adoption location | no | `application-oracle.properties` keeps Flyway disabled; application/dialect checks do not establish persistent Core migration support |

The library also exposes an H2 Core location, but Taxonomy has no H2 product profile. It is not an application-supported storage path.

Do not describe SQL Server or Oracle as Taxonomy-managed persistent JGit Core migration paths merely because the application has a database profile or a compatibility job. Enabling SQL Server requires a separate bounded change that adds the database family, exercises fresh and legacy migration, verifies physical schema and indexes, reopens repositories, tests restart and failure recovery, and passes the real SQL Server matrix.

## Startup classification on enabled paths

For HSQLDB and PostgreSQL, Flyway completes before the Spring-managed persistence unit is initialized. Taxonomy classifies the physical state before choosing an action:

| Existing state | Startup action |
|---|---|
| Empty database | Run the released fresh Core stream |
| Shared schema with unrelated tables but no Core tables | Establish the pre-migration baseline `0`, then run the released stream |
| Exact unversioned shape from a recognized released Core version | Establish the corresponding verified history point, run pending migrations and validate the result |
| Managed Core history with a supported physical shape | Run pending migrations and revalidate history, columns, lengths and indexes |
| Exact pre-library Taxonomy shape | Fail unless the one-time legacy-adoption flag is enabled; then run released adoption V1/V2 and the normal Core stream |
| Adoption V1 already recorded but V2 still required | Fail unless the one-time flag is enabled; then run the remaining released adoption step |
| Partial tables, unknown columns, unsupported lengths, duplicate identities, inconsistent history or missing required indexes | Fail before automatic repair |

Recognized unversioned release shapes span the migration history through `0.9.2`; Taxonomy accepts only exact physical contracts. It never guesses a nearest version from a timestamp or a subset of columns.

## Adopting an existing Taxonomy database

The pre-library tables differ from the released Core contract in committed-state columns, indexes and physical lengths:

- `git_packs.pack_extension` was implicitly `VARCHAR(255)`; Core requires `VARCHAR(32)`;
- `git_reflog.ref_name` was implicitly `VARCHAR(255)`; Core requires capacity for 1024 characters.

Use this runbook only on the enabled HSQLDB or PostgreSQL paths:

1. Stop every writer and take a restorable backup.
2. Record repository counts, ordered checksums of all `git_packs.data` BLOBs and the existing reflog rows.
3. Start once with `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true` only after the backup and evidence exist.
4. The released read-only preflight rejects partial schemas, incomplete rows, duplicate `(repository_name, pack_name, pack_extension)` identities and any `pack_extension` value longer than 32 characters.
5. Released adoption V1 adds committed state, backfills `committed_at`, and establishes the required unique and committed-state indexes.
6. Released adoption V2 narrows `pack_extension` from 255 to 32 and widens `ref_name` from 255 to 1024.
7. Taxonomy establishes or validates the normal Core history and verifies the final columns, lengths and required indexes.
8. Remove the legacy-adoption flag immediately after successful startup.
9. Reopen at least two logical repositories, traverse refs and commits, compare BLOB checksums and reflog rows, and inspect normal queryable reflogs before enabling writers.

Do not replace this procedure with Hibernate `ddl-auto=update`, manual ad-hoc DDL, Flyway `repair`, or deletion of migration history. Taxonomy never chooses a duplicate row or truncates an oversized value automatically.

## Verification

The integration is covered through normal Maven/JUnit/Failsafe authority:

- `JgitStorageHibernateIntegrationTest` checks public Core entity registration, close/reopen persistence, refs, commits, reflogs, logical repository isolation and scoped deletion.
- `JgitStorageSchemaMigrationConfigTest` covers fresh/shared schemas, exact released-shape history establishment, V1/V2 adoption, preservation, invalid/partial states and idempotence.
- `JgitStoragePostgresMigrationIT` repeats the pre-library adoption against PostgreSQL and verifies the dedicated histories.
- `JgitStorageDocumentationContractTest` derives dependency/distribution facts from the root POM, keeps the German and English guides aligned, rejects obsolete authenticated access instructions, and verifies the named Core/adoption resources in the resolved artifact.

A clean verification resolves the pinned `jgit-storage-hibernate-core` release anonymously through the configured release repository. Use the repository's authoritative CI command without adding a second variant:

```bash
./mvnw -q verify -DexcludedGroups="real-llm"
```

GitHub Actions may select or parallelize Maven invocations, but it must not own a different migration or documentation pass/fail rule.
