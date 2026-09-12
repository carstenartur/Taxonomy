# Commit-index ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Place the five Git commit-history projection types under workspace/versioning ownership without changing persistence, search or history behavior.

**Architecture:** Relocate the entity to `versioning.model`, its Spring Data repository to `versioning.repository`, and the three projection services to `versioning.service`. Update consumers and package-local tests mechanically. The imported `ArchitectureDslDocument` archive and all unrelated facade operations stay in their current owners for a separately reviewed slice.

**Tech Stack:** Java 21, Spring Boot/Data JPA, Hibernate ORM/Search, JUnit 5, ArchUnit.

**Spec:** [Issue #1043](https://github.com/carstenartur/Taxonomy/issues/1043), bounded D2 history-projection slice of [#628](https://github.com/carstenartur/Taxonomy/issues/628).

## Global constraints

- Preserve class simple names, constructors, methods, entity/table/column/index/unique-constraint names, analyzers, transaction annotations, tenancy, startup recovery and exact error behavior.
- No schema migration, facade redesign, module extraction, context-map/ledger change or coverage-budget change.
- This delegated implementation does not edit the dependency baseline. Root subsequently records the actual baseline measured from clean bytecode for the integrated change.
- Use the isolated `refactor/628-commit-index-ownership` worktree based on C2 + D1 (`f7c2427`). No commits, pushes or additional agents in this delegated slice.
- No Maven while the root verification reactor is active. Standalone Java compilation/tests use fresh unique scratch output for each red/green phase and never reuse old moved-class bytecode.
- Stacked PRs cannot receive full repository CI until their base reaches `main`. Standalone verification does not replace a clean full reactor or PostgreSQL/search gates; report these limitations explicitly.

## Task 1: Guard and relocate the history projection

**Files:** Create `taxonomy-app/src/test/java/com/taxonomy/ArchitectureCommitHistoryOwnershipTest.java`. Move `architecture/model/ArchitectureCommitIndex.java`, `architecture/repository/ArchitectureCommitIndexRepository.java`, and `architecture/service/{CommitIndexService,CommitIndexSearchLifecycle,CommitIndexSearchRebuilder}.java` under corresponding `versioning` packages. Move the two package-local service tests to `versioning/service`. Update exact imports/FQNs/source-path references in facade/controller consumers, `CsvKeywordAnalyzer`, `CommitIndexHibernateSearchTest` and `WorkspaceOverlayScopeOwnershipTest`.

**Interfaces:** All public signatures and data remain identical except their Java package references. The workspace history facade continues to consume `CommitIndexService`; Hibernate Search continues to index the same simple entity/index name.

- [x] Add one strict ArchUnit ownership test, matching each of the five exact simple names and checking its expected versioning package with `allowEmptyShould(false)`:

```java
classes().that().haveSimpleName(typeName)
        .should().resideInAPackage(expectedPackage)
        .allowEmptyShould(false).check(imported);
```

- [x] Compile/run that guard against pre-move production classes in a fresh scratch directory; record the expected ownership failures and verify the old sources are still present in the same invocation.
- [x] Move only the five production types and two package-local tests. Change package/import/FQN/source-path references only; verify normalized old/new source equality and retained assertions with `git diff` and exact replacement comparison.
- [x] Add a runtime mapping assertion to the existing `CommitIndexHibernateSearchTest`: JPA entity name and Hibernate Search index name both remain `ArchitectureCommitIndex`. This resolves the concrete risk from implicit names without changing production annotations.
- [x] Compile production and selected test sources in a new unique scratch directory, verify all five old source/class paths are absent, then run the guard, failure/lifecycle, facade rationale/isolation, scope-ownership and history-maintenance tests. Run the mapping/search integration suite standalone if application resources/dependencies permit; report any unavailable gate without claiming it passed.
- [x] Document the ownership boundary and persistence/search compatibility in `docs/dev/COMMIT_HISTORY_PROJECTION_OWNERSHIP.md`, including validation evidence and remaining integration gates. Leave all changes uncommitted for root review.

## Execution evidence

The ownership guard failed for all five pre-move owners. Fresh standalone compilation of 582 production sources passed, with no old moved-class paths in the output. Thirteen selected ownership/unit tests passed. Actual old/new ORM and Search mappings both resolve to `ArchitectureCommitIndex`; SQL, JPQL and analyzer-backed search probes returned one row/hit each. All relocated production statements and existing moved-test assertions are unchanged after normalizing only package/import references. All seven existing Hibernate Search integration cases also passed in the full Spring context with unrelated ONNX initialization disabled, including the new runtime-name assertion (20 selected tests total, no failures/skips). Root performs clean Maven verification, records the actually measured dependency baseline, and owns full-profile verification after hand-off.
