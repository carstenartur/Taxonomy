# Hypothesis ownership implementation plan

> For agentic workers: use the executing-plans workflow; keep each ownership change independently reviewable.

**Goal:** Complete C2 of #1043 as part of #628: relations owns hypothesis lifecycle, review, state transitions and HTTP endpoints; workspace owns repository-context resolution.

**Architecture:** Keep the existing publication, expected-head command and exact-read ports. Resolve historic `WorkspaceContext` arguments through a workspace-owned API, retaining explicit repository selection and rejecting mismatched workspace provenance. Move hypothesis implementation into relations without retaining versioning implementation aliases.

**Tech Stack:** Java 21, Spring Boot, JPA, JGit and ArchUnit in the existing Maven reactor.

**Spec:** [Issue #628](https://github.com/carstenartur/Taxonomy/issues/628) and [C2 in #1043](https://github.com/carstenartur/Taxonomy/issues/1043).

## Global constraints

- One deployable modular monolith; no feature module may depend on `taxonomy-app`.
- No persistence migration, endpoint change, relaxed exception or coverage threshold.
- Preserve separate durable semantic operations and Git checkpoints.
- Preserve exact-head checks, retry identity, tenant visibility, transaction callbacks and original HTTP error contracts.
- Physical extraction requires an acyclic measured module graph.

## 1. Reproduce explicit repository-selection defects

- [x] Add productive compatibility tests in `GitAuthoritativeHypothesisServiceContractTest`: an explicitly selected central repository uses its own ID/default branch; an explicit repository differing from stored workspace provenance is rejected before any review/read/publication.
- [x] Execute the new tests against the existing implementation and retain the failure evidence.
- [x] Introduce `WorkspaceRepositoryContextPort.resolve(WorkspaceContext)` and its workspace-owned resolver, using the existing base-service resolution semantics without test-only fallback behavior.
- [x] Inject the resolver into both hypothesis services; move legacy test fixture wiring into test helpers.
- [x] Re-run routing, mutation, publication and transaction tests.

## 2. Transfer hypothesis lifecycle authority

- [x] Move `HypothesisService`, `GitAuthoritativeHypothesisService`, `GitAuthoritativeHypothesisReviewService` and `HypothesisReviewStateStore` from `versioning.service` to `relations.service`, updating every production/test consumer.
- [x] Add strict ArchUnit ownership tests for the moved classes: no workspace persistence, concrete DSL storage or JGit dependencies.
- [x] Preserve all existing review-state and transaction assertions and verify the affected suites.

## 3. Transfer hypothesis HTTP authority

- [x] Extract the hypothesis list/accept/reject/apply-session/evidence methods from `DslApiController` to a relation-owned controller with the same mappings, annotations and responses.
- [x] Move `HypothesisHeadApiController` and `GitHypothesisReviewCompatibilityFilter` to relations and update explicit wiring and tests.
- [x] Verify exact-head, repository visibility, pre-resolution, security, error and browser contracts, including direct MVC mappings.

## 4. Measure and publish the boundary

- [x] Run `ArchitectureContextDependencyRatchetTest`, review each changed class-pair count and commit the measured baseline; retain `ArchitectureCycleBoundaryTest` without adding exceptions.
- [x] Record remaining workspace/knowledge edges and ownership in the architecture documentation.
- [x] Run the mandatory `./mvnw verify -DexcludedGroups="real-llm"`; distinguish unavailable infrastructure from successful test execution.
- [x] Review the diff, commit the C2 change and publish a focused PR. Keep #628/#1043 open until their full completion criteria are met.

## Review and validation evidence

The two explicit-selection regressions failed on the prerequisite implementation;
all 26 productive compatibility invocations passed after the resolver change.
Ownership and HTTP boundary tests likewise failed before relocation and passed
after it. In a clean checkout the affected suites executed 159 tests: 158 passed,
and the sole failure printed the expected replacement dependency baseline. The
reviewed measurement is committed with this slice. No assertion was removed.

An independent review found that package relocation would remove hypothesis code
from critical coverage selection. The destination relations packages now inherit
the original versioning line/branch floors and changed-source selection; existing
versioning budgets remain in place. All four hypothesis services are covered by
the storage/persistence boundary rules.

With the pinned ONNX model available, a fresh-checkout default reactor verification
passed: 3,060 application test invocations, zero failures/errors/skips. That local
checkout additionally contained 24 independent draft module-gate invocations;
those test sources are not part of the C2 PR. The default command skips application
integration tests and the post-reactor quality gate, so authoritative CI remains
the integration requirement.

The destination controller package also receives 34 behavioral contract cases for
exact context propagation, central authorization, preconditions, review conflicts,
projection verification and recovery. They all pass in standalone JDK 21/JUnit.
Combining their JaCoCo execution data with the local reactor report measures
84.14% lines / 69.74% branches for `relations.controller`, above the unchanged
81% / 58% floors. The canonical aggregate CI report remains authoritative.

The initial CI CodeQL artifact identifies full DSL validation warnings being
written by `HypothesisService`. Logging now retains only the fixed validation
stage and warning count. Three real persistence/DSL round-trip tests cover one
warning, multiple warnings and a valid model: two disclosure assertions failed
before the fix, and all three pass afterwards. Only the obsolete hypothesis
CodeQL baseline entry is retired; the other six entries and tracking metadata
remain unchanged. Fresh CodeQL results must confirm clearance on the new head.

The final corrective Maven run passes 134 application cases plus 32 tooling/domain
cases, with no failures/errors/skips. It includes all hypothesis-named suites, the
34 destination HTTP contracts, the three logging regressions, the strict ratchet
and cycle rule, and the existing CodeQL baseline/threshold/routing checks.
