# Workspace storage ownership implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan. Mark steps complete only when their evidence exists.

**Goal:** Assign the eleven JGit/Hibernate DSL storage implementations to workspace authority while preserving every runtime and persistence contract.

**Architecture:** Relocate the existing implementations and their consumers to workspace.storage inside taxonomy-app. Existing workspace ports and the separate application schema strategy stay intact. Retire only the now-empty DSL storage exception and measure the resulting graph before any Maven extraction.

**Tech Stack:** Java 21, Spring Boot 4, JGit/Hibernate storage Core, Flyway, JUnit/Mockito, ArchUnit, Maven/JaCoCo.

**Spec:** docs/superpowers/specs/2026-09-13-workspace-storage-design.md

**Base:** D5a source 09beae7525ea5968a162fd8113bd73bf97187912, tree9698b3d7489d3adaf06e0b0225130902d61e68d0. D4 is already independently reviewed and running main-targeted CI; root later integrates the approved stack without altering frozen source silently.

## Global Constraints

- Change production Java only by the eleven package relocations and exact imports/FQCN references needed by them. Preserve methods, visibility, annotations, constructors, bean names, data records, control flow, logging, counters and metric names after namespace normalization. No forwarding classes, compatibility shim, new port or implementation redesign.
- Preserve repository/workspace/branch identity, system-versus-selected repository routing, semantic operations separate from Git checkpoints, exact-head CAS/conflicts, retry/recovery, merge/diff/version behavior and every existing test assertion. Preserve old negative ownership assertions for types that are not moved in this slice.
- Preserve all SQL resources/checksums, tables, indices, history/adoption rules, database-family support, Flyway enablement and strategy order. The qualified application strategy and its ten integration tests stay in composition.persistence. No schema, DDL, mapping, transaction or bootstrap behavior change.
- Move the existing storage coverage protection to com/taxonomy/workspace/storage with LINE 0.87 and BRANCH 0.71 and the corresponding changed-source prefix. Retain every other package floor and the 0.75/0.60 changed-source minimums, including independent composition/persistence protection.
- Remove only the obsolete com.taxonomy.dsl.storage.. context/exemption scope. Workspace is classified by the existing com.taxonomy.workspace.. prefix. Keep remaining DSL export exceptions, all exception IDs/owners/expiry dates and all unrelated context mappings. Never extend exemptions to workspace.storage or introduce a new waiver.
- Preserve every existing architecture selector and add the storage ownership guard identically to pom.xml and .mvn/verification-suites.json. Preserve mandatory cycle and exact dependency-ratchet enforcement. Update the baseline only from freshly compiled production bytecode with every changed edge explained.
- Update real runtime and build consumers: both Hibernate schema-filter FQCNs, both OpenTelemetry method targets and the factory Micrometer key, five moved consumer report paths, and new storage source/test workflow filters. Retain existing commands, report requirements, old source filters, Docker/CI requirements and all unrelated instrumentation.
- Work only in /tmp/taxonomy-d5b-workspace-storage-20260913. The implementer must not run Maven, mutate Git, publish, edit another worktree or spawn subagents. Root owns one serial native Maven reactor, measured baseline/coverage evidence, independent review, commits and publication.

## Task 1: Move storage authority and all live consumers

**Interfaces:** Consume the existing WorkspaceDslReadPort, WorkspaceDslVersionPort and WorkspaceDslPublicationPort unchanged. Produce the same eleven public/internal types and methods under com.taxonomy.workspace.storage. The existing jgitStorageFlywayMigrationStrategy bean remains the qualified Core strategy consumed by composition.persistence.

**Production owners:** Move these files from taxonomy-app/src/main/java/com/taxonomy/dsl/storage/ to taxonomy-app/src/main/java/com/taxonomy/workspace/storage/:

- DslBranch.java
- DslCommit.java
- DslGitRepository.java
- DslGitRepositoryFactory.java
- DslStorageConfig.java
- DslWorkspacePublicationAdapter.java
- DslWorkspaceReadAdapter.java
- DslWorkspaceVersionAdapter.java
- ExpectedHeadDslCommitter.java
- JgitStorageHibernateSchemaFilterProvider.java
- JgitStorageSchemaMigrationConfig.java

**Test owners:** Move these files from taxonomy-app/src/test/java/com/taxonomy/dsl/storage/ to taxonomy-app/src/test/java/com/taxonomy/workspace/storage/:

- DatabaseIdentifierTestSupport.java
- DslGitRepositoryFactoryRepositoryAwareCompatibilityTest.java
- DslGitRepositoryFactoryTest.java
- DslGitRepositoryTest.java
- DslWorkspaceHistoryReadTest.java
- DslWorkspacePublicationAdapterTest.java
- DslWorkspaceReadAdapterTest.java
- DslWorkspaceVersionAdapterTest.java
- ExpectedHeadDslCommitterTest.java
- JgitStorageDocumentationContractTest.java
- JgitStorageHibernateIntegrationTest.java
- JgitStorageHibernateSchemaFilterProviderTest.java
- JgitStorageOptimizedIndexContractTest.java
- JgitStoragePostgresMigrationIT.java
- JgitStorageSchema091CompatibilityTest.java
- JgitStorageSchemaIndexValidationTest.java
- JgitStorageSchemaMigrationConfigTest.java

**Consumers:** Audit dotted, slash and escaped old-package references in tracked Java, resources, .github, observability and current docs. The preflight inventory is supplied in the task workspace; it found38 production Java and86 test Java files with dotted references before planning, but it is not a substitute for auditing slash-based coverage/workflow consumers. Only required namespace imports/references change in these consumers. In ArchitectureApplicationSchemaCompositionTest, the former application owner com.taxonomy.dsl.storage.TaxonomySchemaMigrationConfig remains a valid negative assertion: that class is not among the eleven moved owners. Do not blindly rewrite it.

- [ ] Add taxonomy-app/src/test/java/com/taxonomy/ArchitectureWorkspaceStorageOwnershipTest.java and establish honest RED against pre-move bytecode. Use this complete test:

```java
package com.taxonomy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureWorkspaceStorageOwnershipTest {
    @Test
    void jgitStorageBelongsToWorkspaceAuthority() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy");
        for (String simpleName : List.of(
                "DslBranch", "DslCommit", "DslGitRepository", "DslGitRepositoryFactory",
                "DslStorageConfig", "DslWorkspacePublicationAdapter", "DslWorkspaceReadAdapter",
                "DslWorkspaceVersionAdapter", "ExpectedHeadDslCommitter",
                "JgitStorageHibernateSchemaFilterProvider", "JgitStorageSchemaMigrationConfig")) {
            assertThat(classes.contain("com.taxonomy.workspace.storage." + simpleName))
                    .as("workspace storage owner %s", simpleName).isTrue();
        }
        assertThat(classes.stream()
                .filter(type -> type.getPackageName().equals("com.taxonomy.dsl.storage")
                        || type.getPackageName().startsWith("com.taxonomy.dsl.storage."))
                .map(type -> type.getName()).toList()).isEmpty();
    }
}
```

Use actual Jupiter execution and imported production classes. A failure must identify absent destination owners against old output; compilation trouble is not RED. Retain the test's exact ownership list. No new production test seam is needed.

- [ ] Relocate only the eleven production and seventeen test/support files and replace their package declarations. Update imports and exact moved-class FQCNs in consumers. A safe mechanical reference mapping is:

```python
def relocate_known_references(text, moved_type_names):
    old_prefix = "com.taxonomy.dsl.storage."
    new_prefix = "com.taxonomy.workspace.storage."
    for name in moved_type_names:
        text = text.replace(old_prefix + name, new_prefix + name)
    return text
# Moved owner files also change their package declaration and physical path.
# Package-wide policy scopes and historical negative owners require the separate
# reviewed steps below; do not globally replace every old package occurrence.
```

Compare old/new Java after reversing only these namespace changes. All eleven production implementations and all retained assertions must otherwise match. Keep the ten application-schema ITs in composition.persistence; adapt only imports of moved Core types/support.

- [ ] Update both runtime profile values to com.taxonomy.workspace.storage.JgitStorageHibernateSchemaFilterProvider in application-hsqldb.properties and application-postgres.properties. Update the two class targets in observability/javaagent.properties (DslGitRepositoryFactory and DslGitRepository) without changing any listed method or other target. Update only the factory class-name key in TaxonomyObservationConfiguration; retain its metric/operation names. Reuse ObservabilityConfigurationTest, which loads each configured class and verifies its actual methods.

- [ ] In .github/critical-coverage-policy.json, replace the old storage changed-source prefix with taxonomy-app/src/main/java/com/taxonomy/workspace/storage/ and change only the storage package row to com/taxonomy/workspace/storage, retaining LINE0.87/BRANCH0.71. Keep composition/persistence and every other value unchanged.

- [ ] In .github/jgit-storage-hibernate-contract.sh, move only these five required report namespaces to com.taxonomy.workspace.storage: JgitStorageHibernateIntegrationTest, JgitStorageOptimizedIndexContractTest, JgitStorageSchemaIndexValidationTest, JgitStorageSchemaMigrationConfigTest and JgitStoragePostgresMigrationIT. Keep the other four reports, all commands and missing-report failure behavior. In .github/workflows/jgit-storage-hibernate-contract.yml, add workspace/storage main/test filters under both pull_request and push; retain every current filter, including old storage and composition/persistence, and all job logic.

- [ ] Retire only obsolete DSL storage policy scope. Remove com.taxonomy.dsl.storage.. from the dsl-adapters package list in .github/architecture-contexts.json; workspace classification already covers the destination. In ArchitectureCycleBoundaryTest remove only that storage entry from DSL_ADAPTER_PACKAGES. Narrow the two matching adapter-boundary scopes in .github/architecture-exceptions.json to com.taxonomy.dsl.export.., preserving IDs/owners/expiry and remaining exception semantics. Adjust only now-stale explanatory text in ArchitectureTest and exception docs; retain the existing rule predicates and other exceptions. ArchitectureWorkspaceAuthorityBoundaryTest already covers the new package and must remain unchanged.

- [ ] Register ArchitectureWorkspaceStorageOwnershipTest in pom.xml and .mvn/verification-suites.json with all ten existing D5a selectors preserved identically. Root will later integrate any approved upstream gate selectors. Run bash syntax, JSON and XML checks on changed consumers; never weaken a required gate to accommodate the move.

- [ ] Update current owner/path references in docs/en/MODULE_BOUNDARIES.md, docs/de/MODULE_BOUNDARIES.md, docs/dev/08-archunit-exceptions.md, docs/dev/APPLICATION_SCHEMA_COMPOSITION.md, docs/internal/MAINTAINABILITY_MATRIX.md and any other directly affected current usage docs found by the audit. Add docs/dev/WORKSPACE_STORAGE_OWNERSHIP.md explaining owner/API, unchanged persisted behavior, runtime/observation/build consumers and remaining extraction limits. Preserve historical plan/spec stage references. Do not invent post-move graph or test/coverage counts; root will supply actual measurements.

- [ ] Compile fresh post-move production/test output and run the new guard, existing Core/in-memory contracts that can run standalone, and observation reflection contracts. Use JDK21 and an explicit Mockito agent. Build dependencies from the fresh D5a Surefire classpath, excluding its app main/test outputs for GREEN; freshly compile all current app sources and required test/support sources into unique task output. All borrowed foundation modules are unchanged. Full Spring/HSQLDB and native reactor execution remain root-owned. Report any limitation honestly.

- [ ] Leave .github/architecture-dependency-baseline.json unchanged initially. Root runs the mandatory architecture reactor, captures the exact generated baseline from its expected ratchet failure, reviews every edge change, and then provides the generated file to this same implementer for exact application and measured documentation. Never invent baseline numbers or add allowances.

- [ ] Write task-1-report.md under this plan's ignored SDD directory: complete changed-file/normalization audit, retained test/assertion evidence, RED/GREEN commands/output, runtime and metadata consumer checks, unchanged SQL hashes, concerns and explicit unrun gates. Freeze source for root native verification and the one task-scoped review; no Git mutations/publication/subagents. After root measurements, append the exact follow-up and covering verification evidence to the same report.

**Root-owned completion:** One serial native architecture reactor first; retain its expected old-baseline failure honestly. Apply only reviewed measured data through the implementer. Then run architecture plus affected existing Surefire contracts and actual aggregate coverage, inspect preservation of every moved floor and changed-source threshold, perform task and whole-branch review under SDD, and publish a bounded draft stacked on D5a. Fresh main-targeted canonical CI/database/UI/security/recovery and current-head review remain required for merge.

## Task 2: Prove invalid branches cannot open a Git transport

The completed workspace-storage change is published as draft PR #1060 at `2122b52eabae0edce69d2674c6b2a98cfa050985`. Complete external review `5190415687` inspected all150 changed files and identified one new moderate assertion gap in `ExternalGitSyncServiceTest.pushRejectsBlankAndInvalidBranchNamesBeforeOpeningTransport`: the three exception assertions do not prove the named absence of transport opening. Earlier implementation, coverage, whole-branch review and the selective-transfer assertion fix are complete; do not repeat them.

Change only `taxonomy-app/src/test/java/com/taxonomy/workspace/service/ExternalGitSyncServiceTest.java`. Preserve every existing test method, fixture, exception assertion and production byte. Add imports for `org.eclipse.jgit.transport.Transport`, `org.mockito.MockedStatic` and static `org.mockito.Mockito.mockStatic`. Wrap the existing three assertions in a try-with-resources `MockedStatic<Transport>` from `mockStatic(Transport.class)` and invoke `verifyNoInteractions()` after each existing `assertThrows`. Keep the exact inputs `null`, `"  "` and `"bad branch"`, the valid credential-free remote URL, the current method name and all current setup/teardown. Do not weaken the promise by renaming the test, add a production seam or dependency, suppress output, or change any other test, policy, source, schema, selector or coverage floor.

Use the current native classpath in this plan's ignored SDD `classpath-current-native.txt`, its existing `JupiterLauncher.java`, JDK21 and explicit Mockito agent. Compile the amended test to a fresh task-local output and run only `com.taxonomy.workspace.service.ExternalGitSyncServiceTest`. Prove the assertion's sensitivity using a temporary shadow copy of the real production service that moves its branch validation from before opening the transport to the first line inside the transport try block. Keep repository production untouched. Compare the original published test and amended test against that same shadow mutant: record actual outcomes; the amended test must reject observed transport opening rather than fail for compilation/classpath/network reasons. Then run the amended test against unchanged production. No network connection is required or authorized for this focused validation.

The implementer may write the one test and its own ignored evidence/report only; no Maven, Git/index/HEAD/branch mutation, external API or subagents. Root owns one native focused reactor run, preservation and coverage-evidence checks, commit, scoped external-finding review and publication. Write commands, exact outcomes, changed paths, warnings and concerns to `task-2-report.md` in this plan's SDD directory and freeze. Current-head external review and required main-targeted CI remain gates; no automated human attestation is allowed.

## Task 3: Trigger the consumer contract for all relocated runtime consumers

Task2 is complete and published at `647c9b2e38ae12c0eeb652c18a1fe299f271a511`. Complete external review `5190452812` inspected150/150 files and raised comment `3999416648`: changes solely to the runtime consumers or verification catalogue currently bypass the separate JGit consumer workflow because its path filters omit them. This is a new trigger-coverage finding, not a remaining transport-test defect.

Change only `.github/workflows/jgit-storage-hibernate-contract.yml`. In both `on.pull_request.paths` and `on.push.paths`, add each of these five exact paths once, immediately after the existing `pom.xml` entry, preserving this order:

```yaml
      - 'taxonomy-app/src/main/resources/application-hsqldb.properties'
      - 'taxonomy-app/src/main/resources/application-postgres.properties'
      - 'observability/javaagent.properties'
      - 'taxonomy-app/src/main/java/com/taxonomy/observability/TaxonomyObservationConfiguration.java'
      - '.mvn/verification-suites.json'
```

Retain every existing trigger, branch filter, path (including old storage paths), permission, concurrency value, job, step, command, timeout, report, upload and environment value byte-for-byte. Add no skip, condition, waiver, production/test change or dependency. This is the sole follow-up extension to Task1's path-filter scope; its job-logic freeze remains absolute.

Validate YAML with the available parser using YAML1.2-compatible `on` handling (PyYAML BaseLoader is adequate for this structural check), exact presence/uniqueness of the five paths in both lists, existence of each referenced file, and that removing only those ten new entries reproduces the original published workflow exactly. Run whitespace/diff checks. Do not add a test mirroring this low-impact list edit or rerun Maven: no runtime or test implementation changes. Root owns commit, scoped review, publication and the fresh external-review request. Write exact commands/results, changed paths and any concerns to `task-3-report.md` in this plan's SDD directory; freeze. No Git/index/HEAD/branch mutation, Maven, external API/network or subagents by the implementer.
