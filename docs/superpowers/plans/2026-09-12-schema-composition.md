# Application schema composition implementation plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` for this bounded task. Mark steps complete only after evidence exists.

**Goal:** Separate application-wide Flyway orchestration from the workspace DSL storage owner before relocating storage adapters, preserving migration order and every persisted contract.

**Architecture:** Move only `TaxonomySchemaMigrationConfig` from `dsl.storage` to `composition.persistence`. Inject the existing qualified `jgitStorageFlywayMigrationStrategy` into the application `@Primary` strategy instead of invoking the core package-private implementation. Core schema classification/adoption stays with its existing owner. The eleven storage classes remain in place for a subsequent bounded PR.

**Scope:** D5a of #1043/#628. Base is published D3 `b83cae72db2420966386218696fd8373b67e4a25`; root integrates D4's independent bootstrap/metadata changes before final measurement and publication. This plan was created by root before dispatch and is root-owned planning provenance to include with the task.

**Stack:** Java 21, Spring Boot 4, Flyway, JDBC, JUnit/Mockito, ArchUnit, Maven/JaCoCo, existing PostgreSQL Testcontainers contracts.

## Global constraints

- Preserve all SQL migration resources/checksums, table/index/history names, baseline/adoption classification, database-family support, exceptions, transaction/order semantics and application startup behavior. No DDL, schema redesign or newly supported database family.
- Preserve both configuration classes' `spring.flyway.enabled=true` condition and `proxyBeanMethods=false`, all existing bean names, and the application strategy's `@Primary`. Only core owns `taxonomy.jgit-storage.legacy-adoption` with its existing false default. The application strategy calls the exact qualified core strategy once, then application migration; core failure prevents application work.
- Do not expose package-private core migration helpers or introduce a new wrapper/port solely for this move. Use the existing public Spring `FlywayMigrationStrategy` bean. No direct replacement `flyway.migrate()` that bypasses core classification/adoption.
- Move all ten application-schema integration tests with their assertions intact; core storage tests stay in `dsl.storage`. Tests outside the core package use its already public strategy factory rather than widened internals.
- Preserve the existing `dsl.storage` 87% line / 71% branch package floor. Add the same independent 87% / 71% floor and changed-source prefix for `composition.persistence`. All existing package floors, 75% / 60% changed-source minimums and exceptions remain unchanged; repair real coverage gaps with meaningful contracts.
- No bootstrap, DSL document orchestration, storage adapter implementation, workspace identity, checkpoint/journal, runtime schema-filter FQCN, observation inventory, context map, cycle exception or review-gate changes. Existing workflow jobs, commands, selectors, Docker and evidence requirements remain; only relevant path filters and moved report paths change.
- Work only in `/workspace/scratch/5f847bdd1880/Taxonomy-schema-composition`. Root owns the single Maven reactor, fresh baseline/coverage measurements, independent review, commits and GitHub publication. Implementer must not run Maven, commit, push, change another worktree or start subagents.

## Task 1: Separate the application migration strategy and preserve its contracts

**Production:** Move `taxonomy-app/src/main/java/com/taxonomy/dsl/storage/TaxonomySchemaMigrationConfig.java` to `taxonomy-app/src/main/java/com/taxonomy/composition/persistence/TaxonomySchemaMigrationConfig.java`. Besides the package/import changes, the only allowed implementation change is:

```java
@Bean
@Primary
public FlywayMigrationStrategy taxonomyFlywayMigrationStrategy(
        @Qualifier("jgitStorageFlywayMigrationStrategy")
        FlywayMigrationStrategy coreMigrationStrategy) {
    return flyway -> {
        coreMigrationStrategy.migrate(flyway);
        migrateApplicationSchema(flyway.getConfiguration());
    };
}
```

Use `org.springframework.beans.factory.annotation.Qualifier` and remove the now-unused application `@Value` import/boolean parameter. Keep every other method, constant, nested record/enum and access level unchanged. The existing core strategy factory and `migrateCoreSchema` remain unchanged in `dsl.storage`; inspect their real signatures before adapting consumers.

**Move these ten existing tests** from `taxonomy-app/src/test/java/com/taxonomy/dsl/storage/` to `.../composition/persistence/`:

- `AnalysisArtifactTenantPostgresMigrationIT.java`
- `ArchitectureCommitIndexTenantMigrationPostgresIT.java`
- `RelationHypothesisTenantMigrationPostgresIT.java`
- `RelationProjectionCheckpointPostgresMigrationIT.java`
- `RelationProposalTenantMigrationPostgresIT.java`
- `RequirementTenantPostgresMigrationIT.java`
- `TaxonomyPostgresValidateStartupIT.java`
- `TaxonomyRelationProjectionIndexMigrationIT.java`
- `TaxonomyRelationTenantMigrationPostgresIT.java`
- `TaxonomySchemaPostgresMigrationIT.java`

Adapt only package/import/setup references required by separation. Where setup currently calls `JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false)`, call `new JgitStorageSchemaMigrationConfig().jgitStorageFlywayMigrationStrategy(false).migrate(flyway)` through its existing public API. Retain every assertion and real database fixture. The validate-startup test must still import/register both actual configuration classes. Audit direct/FQCN/source-path consumers without moving unrelated storage tests.

**New tests:** Add `ArchitectureApplicationSchemaCompositionTest` and a cohesive `composition/persistence/TaxonomySchemaMigrationConfigTest` (split a distinct bean-selection contract file only if it materially improves cohesion). The non-vacuous architecture guard requires the new application owner, rejects its old FQCN, requires the unchanged core owner, and forbids the application configuration from importing core implementation classes. It must fail before relocation and pass afterward. Register it in both architecture selectors, preserving their complete existing lists; root integrates D4/gate additions later.

The migration tests must exercise actual Spring bean selection: application primary, exact qualified core delegation once, core-before-application order, failure before application work, and conditional enablement. Cover the application's existing database classification/schema-validation contract through observable outcomes, including non-PostgreSQL no-op, fresh versus complete-legacy baseline selection, existing history, unsafe partial legacy rejection, required post-migration tables, and JDBC metadata/inspection failures. Prefer real JDBC state where supported. A controlled Flyway/JDBC boundary may exercise decisions that require unavailable PostgreSQL locally; keep the existing real PostgreSQL ITs as the independent DDL evidence. Do not expose private helpers or mock the class under test merely to raise coverage. Characterize unchanged behavior before the move where feasible.

**Coverage:** In `.github/critical-coverage-policy.json`, add `taxonomy-app/src/main/java/com/taxonomy/composition/persistence/` and package `com/taxonomy/composition/persistence` with LINE 0.87 / BRANCH 0.71. Keep the current `com/taxonomy/dsl/storage` row and every unrelated value. Standalone coverage may expose gaps; root checks the actual aggregate and repairs gaps before publication.

**Build/evidence paths:** In `.github/jgit-storage-hibernate-contract.sh`, update only the three moved required report paths for `TaxonomyPostgresValidateStartupIT`, `TaxonomySchemaPostgresMigrationIT`, and `ArchitectureCommitIndexTenantMigrationPostgresIT` to `com.taxonomy.composition.persistence`. Keep the six other report paths, all commands, catalogue selectors, Docker requirement and required-report checks unchanged. In `.github/workflows/jgit-storage-hibernate-contract.yml`, retain both old storage main/test filters and add the new composition/persistence main/test filters for PR and push. No other workflow mutation. Audit any additional exact FQCN/report path actually affected.

**Docs:** Add `docs/dev/APPLICATION_SCHEMA_COMPOSITION.md`; update both `docs/en/JGIT_STORAGE_HIBERNATE.md` and `docs/de/JGIT_STORAGE_HIBERNATE.md` with current owner, qualified core-before-app composition and unchanged operations. Update actual direct owner/path references in `.github/copilot-ref-architecture.md`, `docs/dev/07-extension-points.md` and `docs/dev/relation-and-dsl-extension-boundaries.md` only where affected. Do not edit the historical D3 plan or canonical EN/DE MODULE_BOUNDARIES pages yet: root first integrates D4 and then records this slice's measured result. Do not invent dependency/test/coverage totals.

- [x] Read binding repository guardrails and inspect the exact core/application strategy seam, ten ITs, coverage policy and path consumers. Escalate a missing required interface or contradictory requirement before changing it.
- [x] Establish RED ownership evidence and unchanged behavior characterization in unique standalone output where feasible; add meaningful bean/migration tests and retain all old assertions.
- [x] Apply the single production move and qualified strategy change; move the ten tests and adapt their existing setup through the public core strategy factory. Audit normalized production diff and assertion preservation.
- [x] Add independent coverage protection and both synchronized guard selectors; update only the three required report paths and matching workflow path filters. Run `bash -n` and JSON/POM/path checks without replacing any CI evidence requirement.
- [x] Compile current production/test sources needed for standalone evidence with JDK21 and the explicit Mockito agent. Borrow dependencies from D3's current Surefire XML, but remove old app output entries and freshly compile current app sources to prevent stale-class evidence. No Maven or live API/LLM calls. Existing PostgreSQL execution remains root/CI-owned.
- [x] Write the implementation report and exact RED/GREEN commands/results under this plan's ignored SDD directory; explicitly name unrun database/coverage gates and freeze sources. No commit/publication by implementer.
- [ ] Root integrates D4/upstream metadata, measures the baseline from fresh reactor bytecode, runs focused and full `verify -DexcludedGroups=real-llm` with the pinned model, checks actual package/source coverage and affected consumer evidence, obtains independent task/final reviews, then publishes a bounded stacked PR. Main-head canonical `-Pci`, database/search/product/security/recovery/UI and exact-head review remain mandatory.
