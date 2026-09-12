# Application schema composition

## Ownership

`com.taxonomy.composition.persistence.TaxonomySchemaMigrationConfig` owns the
Taxonomy application schema. It keeps the application migration resources,
history table, legacy-state classification and final-table validation separate
from JGit Core storage.

`com.taxonomy.dsl.storage.JgitStorageSchemaMigrationConfig` remains the sole
owner of Core schema classification, history establishment, migration and
legacy adoption. Its package-private helpers stay package-private. Consumers
compose with its public Spring `FlywayMigrationStrategy` bean.

## Startup composition

When `spring.flyway.enabled=true`, Spring selects the application strategy as
the primary `FlywayMigrationStrategy`. The bean injects the exact qualified
Core strategy:

```java
@Qualifier("jgitStorageFlywayMigrationStrategy")
FlywayMigrationStrategy coreMigrationStrategy
```

For the Boot-managed `Flyway` instance, the application strategy performs two
ordered steps:

1. invoke the qualified Core strategy exactly once;
2. after Core succeeds, inspect and migrate the Taxonomy application schema.

A Core exception stops the sequence before application-schema inspection or
migration. Both configuration classes use the same
`spring.flyway.enabled=true` condition and `proxyBeanMethods=false`. Only the
Core configuration owns `taxonomy.jgit-storage.legacy-adoption`, including its
`false` default.

## Application-schema behavior

The application migration remains PostgreSQL-specific. Other database products
leave this stream untouched. PostgreSQL startup keeps the existing decisions:

| Existing application state | Action |
|---|---|
| No legacy markers and no application history | Baseline at `0`, then migrate |
| All legacy markers and no application history | Baseline at `1`, then migrate |
| Existing application history | Run pending migrations without a new baseline |
| Partial legacy markers | Fail before migration |

After Flyway completes, startup still requires every legacy marker, every table
used by the current portfolio model and `taxonomy_schema_history`. Database
identification and schema-inspection failures remain startup failures. No SQL
resource, checksum, table name, index name or history name changes as part of
this ownership separation.

## Verification boundary

`ArchitectureApplicationSchemaCompositionTest` requires both owners in their
respective packages, rejects the old application owner and prevents the
application configuration from depending on Core implementation classes.
`TaxonomySchemaMigrationConfigTest` exercises Spring bean selection, ordering,
failure propagation and the application-schema decision boundary. The moved
PostgreSQL integration tests remain the real DDL, upgrade and validate-startup
evidence.

Operational backup, legacy-adoption, restart and recovery procedures remain in
the English and German JGit storage guides.

## Integrated measurement

After integrating published bootstrap composition D4, a fresh full-reactor
focused run passed **29 tests** with zero failures/errors/skips in **54.804
seconds**. It covers the application strategy, both migration-owner contracts,
Core schema behavior, storage documentation and the dependency ratchet. The
actual production-bytecode inventory is unchanged at **537 class pairs / 146
package edges**; all **42 outgoing workspace pairs** still target DSL storage,
and **117 knowledge-to-workspace pairs** remain. No baseline adjustment is
required. Both architecture selectors contain the same ten test classes.

The ten moved PostgreSQL ITs retain their real database fixtures and assertions.
Local compilation and controlled JDBC tests do not establish PostgreSQL DDL or
validate-startup execution; the unchanged consumer contract and canonical CI
remain required.

## Full local verification

The integrated full reactor `clean verify -DexcludedGroups=real-llm`, with the
pinned local embedding model, passed in **10:26** with **3,129 application test
invocations**, zero failures/errors/skips. The complete architecture profile
passed **24 tests** across all ten selectors in **42.377 seconds**. Every one of
1,050 application production class files and 672 test class files has a current
source owner; all 585 production and 548 test sources have compiled output.
The former application-schema and bootstrap owners are absent.

The actual aggregate covers the new application-schema package and source at
**65/65 lines and 16/16 branches (100%)**. Storage remains at **977/1,086 lines
(89.96%) and 278/365 branches (76.16%)**. Both independent **87% / 71%** package
floors and the unchanged **75% / 60%** changed-source floors pass. Every existing coverage budget remains unchanged; the new independent
composition budget matches the storage floor. Migration resources, the Core
implementation, baseline and cycle exceptions are unchanged.

Default local verification skips application integration tests and post-reactor
quality execution. Docker is unavailable locally, so the ten moved PostgreSQL
ITs were compiled but not executed here. The unchanged consumer workflow must
provide real migration, validate-startup and indexed-history evidence on the
applicable published/main head; this local result does not replace canonical
`-Pci`, database, security, recovery, product or UI gates.
