# Hibernate-backed JGit storage

Taxonomy stores Architecture DSL and preferences histories in relational database tables through the released [`jgit-storage-hibernate-core`](https://github.com/carstenartur/jgit-storage-hibernate) library. Taxonomy no longer contains a copied implementation of JGit's DFS object and reftable storage.

## Responsibility boundary

| Taxonomy owns | `jgit-storage-hibernate-core` owns |
|---|---|
| DSL file name, parser and semantic diff | JGit DFS repository implementation |
| Branch, merge, cherry-pick, revert and workspace workflows | Pack/object persistence |
| Logical repository names and workspace routing | Reftable ref persistence |
| Preferences JSON and branch convention | Queryable reflog persistence |
| Authorization, audit, REST and UI contracts | Transactional logical-repository deletion |
| Application projections and Hibernate Search indexes | Core entities and versioned Core migrations |

Taxonomy consumes only public types from `io.github.carstenartur.jgit.storage.hibernate` and public JGit APIs. Application code must not import the library's `repository`, `objects` or `refs` implementation packages.

## Dependency and package access

The pinned version is declared in the root POM:

```xml
<jgit-storage-hibernate.version>0.1.8</jgit-storage-hibernate.version>
```

The application module depends on:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>${jgit-storage-hibernate.version}</version>
</dependency>
```

Version 0.1.8 is currently published through GitHub Packages. Maven credentials must use the same server ID as the repository entry in `pom.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

The token needs `read:packages`. Never commit a token to the repository. GitHub Actions jobs use their scoped `GITHUB_TOKEN` and require `packages: read` permission.

## Spring-managed persistence context

The library's Core entities live outside `com.taxonomy`, so the application explicitly includes `io.github.carstenartur.jgit.storage.hibernate.entity` in `@EntityScan`. The integration test compares the resulting JPA metamodel with `CoreEntities.annotatedClasses()` so a future library entity addition cannot be missed silently.

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

The physical tables are shared, but every query is scoped by an exact logical repository name:

| Purpose | Logical name |
|---|---|
| Shared system DSL | `taxonomy-dsl` |
| Workspace DSL | `ws-<workspace-id>` |
| Preferences | `taxonomy-preferences` |

`DslGitRepositoryFactory` caches open handles. Cache eviction closes a handle but deliberately preserves database rows. Hard workspace deletion closes the handle first and then calls `HibernateRepositoryFactory.deleteRepository(...)`, which removes only rows for the requested logical name.

A reopened database repository is not seeded again when it already contains refs.

## Ref updates and reflogs

All Taxonomy ref mutations set the expected old object ID, the new object ID, an actor with `setRefLogIdent(...)`, and an operation-specific message with `setRefLogMessage(...)`.

Every `RefUpdate.Result` is checked. Rejected, locked or missing-object results fail the operation instead of being logged as success. The library commits the reftable update and queryable `git_reflog` row in the same repository-scoped transaction. Reflogs are read through the normal public API:

```java
repository.getReflogReader("refs/heads/draft").getLastEntry();
```

## Schema ownership and startup order

Spring Boot's Flyway integration owns the two Core tables on supported Taxonomy profiles:

- `git_packs`
- `git_reflog`

`application-hsqldb.properties` and `application-postgres.properties` enable Flyway and select the released database-specific migration stream. The default, SQL Server and Oracle configurations leave this Core migration integration disabled.

`JgitStorageHibernateSchemaFilterProvider` excludes the two Core tables from Hibernate create, update, truncate and drop operations. The entities remain included in Hibernate schema validation. This prevents `ddl-auto=create` or `ddl-auto=update` from racing with or improvising the Core migration while the rest of Taxonomy's application schema can continue to use its existing Hibernate lifecycle.

Flyway completes before the Spring-managed persistence unit is initialized. Startup classifies the database before selecting a path:

| Existing state | Startup action |
|---|---|
| Empty database | Run the released fresh Core migrations |
| Shared schema with unrelated tables but no Core tables | Establish baseline `0`, then run the released migrations |
| Exact unversioned current Core tables | Run the read-only safety check, establish the normal history at `0.1.4`, then run pending migrations |
| Managed Core history and exact current shape | Run pending migrations and revalidate the physical contract |
| Exact pre-library Taxonomy shape | Fail unless the one-time legacy-adoption flag is enabled |
| One missing Core table, unknown columns or unsupported lengths | Fail before automatic repair |
| Adoption history without normal Core history | Fail and require restore or documented recovery |

## Supported database paths

| Database | Fresh Core schema in Taxonomy | Existing Taxonomy Core adoption |
|---|---:|---:|
| HSQLDB | yes | yes, directly tested |
| PostgreSQL | yes | yes, directly tested with Testcontainers |
| H2 | library migration exists; no Taxonomy profile | not an application path |
| Microsoft SQL Server | no released Core migration | no |
| Oracle | no released Core migration | no |

Taxonomy still supports SQL Server and Oracle for its application entities. Persistent Core storage on those databases must not be described as migration-supported until the library publishes dialect-specific migrations and matching integration coverage.

## Fresh installation

No application code copies classpath migration locations. `JgitStorageSchemaMigrationConfig` uses the public constants from `CoreSchemaMigrations` and configures the Boot-managed Flyway instance for HSQLDB or PostgreSQL.

A fresh HSQLDB or PostgreSQL startup creates the dedicated Core history and applies the released `0.1.4` and `0.1.5` migrations before Hibernate initializes. When the same schema already contains unrelated Taxonomy tables, the orchestrator first records the documented pre-migration baseline `0`; it never baselines an unknown partial Core schema.

The long-term operating model for a fully provisioned persistent deployment remains Flyway plus global Hibernate validation. Until the broader Taxonomy application schema is migrated to Flyway, the schema filter provides the narrower safe boundary: Flyway owns Core DDL and Hibernate manages the remaining application tables.

## Adopting an existing Taxonomy database

The copied pre-library tables differ from the released Core contract in more than the committed-state columns and unique pack identity:

- `git_packs.pack_extension` was implicitly `VARCHAR(255)`; Core requires `VARCHAR(32)`;
- `git_reflog.ref_name` was implicitly `VARCHAR(255)`; Core requires capacity for 1024 characters.

The application therefore refuses legacy adoption by default. Use this runbook:

1. Stop every writer and take a restorable backup.
2. Record repository counts and ordered checksums of all `git_packs.data` BLOBs.
3. Start once with `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true` only after the backup and verification evidence exist.
4. The read-only preflight rejects partial schemas, incomplete rows, duplicate `(repository_name, pack_name, pack_extension)` identities and any `pack_extension` value longer than 32 characters.
5. Only after every preflight succeeds, the exact legacy length 255 is narrowed to 32 for `pack_extension`, and `ref_name` is widened from 255 to 1024. Unknown lengths are rejected.
6. The released legacy-adoption migration adds committed state, backfills `committed_at`, creates the unique identity and records its separate history. The normal Core history is then established at the current version.
7. After successful startup, remove the legacy-adoption flag immediately.
8. Reopen at least two logical repositories, traverse refs and commits, verify BLOB checksums and inspect normal queryable reflogs before enabling writers.

Do not use Hibernate `ddl-auto=update` as a substitute for this data migration. Taxonomy never chooses a duplicate row automatically.

The missing column-length normalization in library version 0.1.8 is tracked upstream as [`jgit-storage-hibernate` issue #78](https://github.com/carstenartur/jgit-storage-hibernate/issues/78). Taxonomy's consumer bridge is deliberately fail-closed and can be removed after a released upstream migration covers already-adopted and not-yet-adopted databases.

## Verification

`JgitStorageHibernateIntegrationTest` verifies in the Spring-managed HSQLDB persistence unit that all public Core entities are registered, commits and refs survive close/reopen, normal commits produce queryable reflogs, logical repository names remain isolated, and scoped deletion cannot affect another repository.

`JgitStorageSchemaMigrationConfigTest` covers fresh and shared HSQLDB schemas, exact unversioned history establishment, explicit legacy opt-in, physical column normalization, BLOB and reflog preservation, duplicate rejection, oversized extension rejection and partial-schema refusal.

`JgitStoragePostgresMigrationIT` repeats the real pre-library adoption against PostgreSQL and is an explicit job in the database-compatibility matrix.

The full project gate remains:

```bash
mvn verify -DexcludedGroups="real-llm"
```
