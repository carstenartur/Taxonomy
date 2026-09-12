# Git commit-history projection ownership

This is the bounded D2 history-projection slice of [#1043](https://github.com/carstenartur/Taxonomy/issues/1043) / [#628](https://github.com/carstenartur/Taxonomy/issues/628). Git history indexing belongs to workspace versioning. It does not own the imported DSL materialization archive or architecture reporting.

| Type | Owner package |
|---|---|
| `ArchitectureCommitIndex` | `com.taxonomy.versioning.model` |
| `ArchitectureCommitIndexRepository` | `com.taxonomy.versioning.repository` |
| `CommitIndexService` | `com.taxonomy.versioning.service` |
| `CommitIndexSearchLifecycle` | `com.taxonomy.versioning.service` |
| `CommitIndexSearchRebuilder` | `com.taxonomy.versioning.service` |

The production relocation changes only package/import references. Constructors, queries, tenant/branch keys, transaction boundaries, failure handling, lifecycle order, rebuild properties and mass-indexer settings retain their existing definitions. Two package-local service tests move with their owner and keep every assertion. Consumers and the source-path ownership test follow the new packages.

`ArchitectureDslDocument` and its repository remain in their existing packages. Facade export/materialization/archive operations and startup composition need separate review. This slice neither extracts a module nor completes the module-extraction gate.

## Persistence and search compatibility

`ArchitectureCommitIndex` retains its exact simple class name. Its `@Entity` and `@Indexed` annotations still have no explicit name. An isolated bootstrap using the resolved Hibernate ORM **7.4.5.Final** and Hibernate Search **8.4.0.Final** measured the old mapping before relocation and the new mapping afterward:

| Runtime identity | Before | After |
|---|---|---|
| JPA metamodel entity name | `ArchitectureCommitIndex` | `ArchitectureCommitIndex` |
| Hibernate Search index descriptor name | `ArchitectureCommitIndex` | `ArchitectureCommitIndex` |
| Explicit SQL table | `architecture_commit_index` | `architecture_commit_index` |
| JPQL entity lookup | `from ArchitectureCommitIndex` | `from ArchitectureCommitIndex` |

Both isolated HSQLDB/Lucene probes persisted one row and retrieved it through SQL, JPQL and the existing analyzer-backed search mapping. No index name was guessed or overridden. The existing `CommitIndexHibernateSearchTest` now asserts the effective JPA entity name and Hibernate Search index name in the full Spring persistence context.

The entity annotations are otherwise byte-for-byte unchanged: column definitions, named relational indexes, the `uq_commit_index_repository_workspace_branch_commit` constraint, and the `english`, `dsl`, and `csv-keyword` analyzers retain their values. The new Java FQNs are source ownership changes, not schema identities. There are no schema migrations, database/index renames or extra rebuild actions. `@EntityScan` covers `com.taxonomy`, and component/repository discovery continues beneath the application package; the simple bean names remain unchanged. Search rebuilding continues to target only this entity's scope.

## Measured dependency change

A fresh reactor compilation of C2 + D1 + this relocation measures **537 class
dependencies across 141 package edges**, down from **543 / 144** on the stacked
base. The baseline records the actual ArchUnit result without exceptions.

- `architecture.service -> dsl.storage`: 3 -> 0.
- `architecture.service -> workspace.service`: 2 -> 0.
- `versioning.controller -> architecture.model`: 2 -> 1.
- `versioning.service -> architecture.model`: 2 -> 1.
- `versioning.service -> architecture.service`: 2 -> 0.
- `versioning.service -> dsl.storage`: 21 -> 24. These are the same three Git
  adapter dependencies of the relocated index service, now attributed to its
  workspace owner; no new runtime dependency was introduced.

The four remaining workspace-to-architecture class pairs concern the imported
DSL document archive in the controller and facades. DSL adapter ownership and
startup composition also remain unresolved; the proposed graph is still cyclic
and does not permit physical extraction.

## Focused architecture verification

The D2 branch includes D1's report-composition metadata and ownership guard.
Both the root `pom.xml` and `.mvn/verification-suites.json` select
`ArchitectureCommitHistoryOwnershipTest` in addition to the six predecessor
selectors, which include `ArchitectureDecisionReportBoundaryTest`. The profile
therefore selects seven test classes. Run them from the repository root across
the full reactor, so the production output of every module is current:

```bash
./mvnw test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false
```

This focused command does not replace canonical `./mvnw -B verify -Pci` or remove
the CI constraints of a stacked branch. The current D2 dependency measurement is
**543 to 537**; D1's **540 to 543** remains its historical slice result.

## Validation and integration limits

- The new strict `ArchitectureCommitHistoryOwnershipTest` failed for all five types before relocation, then passed after relocation; `allowEmptyShould(false)` prevents a missing type from passing silently.
- All 582 application production sources compiled into a fresh standalone output directory. All five old source/class paths were absent. An exact old/new comparison verified package/import substitutions only in relocated production sources, with every moved unit-test assertion retained.
- Thirteen standalone ownership, service-failure, lifecycle, facade rationale/isolation, overlay-scope and history-maintenance tests passed.
- Old/new runtime mapping probes and all seven `CommitIndexHibernateSearchTest` cases passed, including the new mapping-name assertion in the full Spring context. This standalone integration execution disabled unrelated ONNX initialization; 20 selected tests passed in total, with no failures or skips.
- A fresh focused Maven reactor ran 66 cases: 65 passed and only the expected initial dependency-baseline mismatch failed. This includes 43 independent module-gate fixtures, the real gate, the strict cycle rule and all selected history/search contracts. Root recorded the measured 537-pair baseline after reviewing every changed edge. The independent module-gate sources are validation inputs only and are not part of this slice.
- Full default `./mvnw verify -DexcludedGroups=real-llm`, with the pinned ONNX model, passed in 11:08: Maven reports 3,120 application invocations, zero failures/errors/skips. This combined run also contains the independent module gate at `c1fe9bec` (43 fixtures plus the actual gate), which is not part of this PR. The recorded dependency baseline, strict cycle rule and history/search contracts all pass.
- Aggregate JaCoCo measures `versioning.service` at 79.77% lines / 67.36% branches, above the unchanged 77% / 59% floors. Each relocated executable source meets the existing 75% line / 60% branch changed-source floor; the repository interface has no executable counters. The context map, exception ledger and coverage policy remain unchanged, and the existing versioning changed-source prefix protects the relocated sources.
- The default local reactor skips application integration tests and the post-reactor quality gate. Canonical CI and PostgreSQL integration profiles remain required on the final main-targeting head.

## Current upstream and metadata checkpoint

C2 is merged in `main` at `66db4e8526691f18b4e83d9ebcef7ff12c65e3ed`.
This D2 branch also incorporates D1's reviewed head
`46615e245a60055a3a11bb67bc8a25f7fdb47dca`, including its canonical English/German
documentation and focused ownership selector. D1 is now merged as
`746e1ce6d9c33eabef1cff06618e8802876949a2`; D2 incorporates that actual `main`
ancestry and targets `main` for authoritative CI. The metadata integration changes no production source,
dependency baseline, coverage rule or workflow.

Fresh `clean verify -DexcludedGroups=real-llm` with the pinned ONNX model passed
in **10:22**. Maven reported **3,120 application test invocations**, zero
failures/errors/skips; 44 of these were retained compiled validation tests from
the independent module-gate checkpoint (43 fixtures and its actual gate).
Their sources are absent from this PR, and these retained test binaries were
removed after the run. All production class files were freshly compiled by
this build. The unchanged versioning service package again measured
**79.77% lines / 67.36% branches**.

The corrected full-reactor architecture profile separately selected all seven
current test classes and passed **19 tests**, zero failures/errors/skips, in
**35.629 seconds**. Both ownership guards ran. The actual `HelpController`
rendered the canonical English and German pages with historical D1 ownership,
current D2 ownership and the four remaining archive pairs.

Stacked PRs cannot receive full repository CI until their base reaches `main`.
Default local verification does not replace canonical CI, profile-specific
database gates or the final exact-head review. The proposed graph remains
cyclic, so no physical module extraction is claimed.
