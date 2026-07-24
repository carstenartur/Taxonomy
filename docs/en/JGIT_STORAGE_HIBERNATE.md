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
| Application projections and Hibernate Search indexes | Core entities and schema migrations |

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

The library's Core entities live outside `com.taxonomy`, so the application explicitly includes the package `io.github.carstenartur.jgit.storage.hibernate.entity` in `@EntityScan`. The integration test compares the resulting JPA metamodel with `CoreEntities.annotatedClasses()` so a future library entity addition cannot be missed silently.

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

All Taxonomy ref mutations set:

- the expected old object ID;
- the new object ID;
- an actor with `setRefLogIdent(...)`;
- an operation-specific message with `setRefLogMessage(...)`.

Every `RefUpdate.Result` is checked. Rejected, locked or missing-object results fail the operation instead of being logged as success. The library commits the reftable update and queryable `git_reflog` row in the same repository-scoped transaction. Reflogs are read with the normal public API:

```java
repository.getReflogReader("refs/heads/draft").getLastEntry();
```

## Supported schema paths

The library release supplies and tests these Core migration paths:

| Database | Fresh schema | Pre-library Taxonomy adoption |
|---|---:|---:|
| HSQLDB | yes | yes |
| PostgreSQL | yes | yes |
| H2 | yes | 0.1.4 baseline path |
| Microsoft SQL Server | not yet supplied | not yet supplied |
| Oracle | not yet supplied | not yet supplied |

Taxonomy still supports SQL Server and Oracle for its application entities, but persistent Core storage on those databases must not be described as migration-supported until the library publishes dialect-specific migrations and real integration coverage.

## Fresh installation

Use the public migration constants from `CoreSchemaMigrations`; do not copy classpath locations into application code. For an empty HSQLDB schema:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.HSQLDB_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

Use `POSTGRESQL_LOCATION` for PostgreSQL. In a shared schema that already contains unrelated application tables, follow the library's pre-migration baseline-0 procedure.

Flyway must finish before Hibernate validates the persistence unit. For persistent deployments, schema migration plus `hibernate.hbm2ddl.auto=validate` is the target operating model. Taxonomy's broader application schema must also be provisioned before switching the global Hibernate setting to `validate`.

## Adopting an existing Taxonomy database

The copied pre-library `git_packs` table lacks committed-state columns and the unique logical pack identity. Do not allow Hibernate `ddl-auto=update` to improvise this data migration.

Follow the upstream [Taxonomy adoption runbook](https://github.com/carstenartur/jgit-storage-hibernate/blob/v0.1.8/docs/taxonomy-adoption.md) exactly:

1. Stop every writer and take a restorable backup.
2. Record repository counts and ordered checksums of all `git_packs.data` BLOBs.
3. Run `LegacyCoreSchemaAdoption.requireSafeToAdopt(connection)` before any DDL.
4. Run the dedicated HSQLDB or PostgreSQL legacy-adoption Flyway location with its separate history table and baseline version `0`.
5. Establish the normal Core history at `CoreSchemaMigrations.CURRENT_SCHEMA_VERSION`.
6. Start Hibernate with schema validation.
7. Reopen at least two logical repositories and traverse refs and commits.
8. Verify BLOB checksums and normal queryable reflogs before enabling writes.

The preflight rejects partial schemas, incomplete rows and duplicate `(repository_name, pack_name, pack_extension)` identities. Operators must resolve duplicates from application knowledge or restore a known-good backup; neither Taxonomy nor the library chooses a row automatically.

## Verification

`JgitStorageHibernateIntegrationTest` verifies in the Spring-managed HSQLDB persistence unit that:

- all public Core entity types are registered;
- a DSL commit and branch ref survive handle close/reopen;
- normal Taxonomy commits produce queryable reflog entries;
- two logical repository names remain isolated;
- deleting one logical repository removes its pack and reflog rows without affecting the other.

The full project gate remains:

```bash
mvn verify -DexcludedGroups="real-llm"
```

PostgreSQL, SQL Server and Oracle container workflows remain responsible for the broader Taxonomy database matrix. The storage library's migration support matrix is deliberately narrower until matching upstream migrations exist.
