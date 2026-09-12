# Git bootstrap composition implementation plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` for this bounded task. Mark completed steps only when their evidence exists.

**Goal:** Remove the remaining workspace dependencies on application readiness and knowledge export by assigning Git startup orchestration to composition, preserving every bootstrap behavior.

**Architecture:** Move `GitRepositoryBootstrap` from `versioning.service` to the existing `composition.dsl.service` package inside `taxonomy-app`. Workspace still owns repository/version authority; application startup combines readiness, knowledge export and the selected system Git repository. No Maven extraction or storage/schema relocation occurs here.

**Scope:** D4 of #1043, implementing #628. Start from local D3 source checkpoint `a6b4926`; root integrates the published D3 metadata/head before final verification. The actual D3 bytecode baseline has 539 class pairs / 147 package edges. Workspace's only non-storage outgoing pairs are bootstrap export (one) and application readiness (two). No post-D4 count may be invented.

**Stack:** Java 21, Spring Boot 4, JGit, JUnit, Mockito, ArchUnit and Maven/JaCoCo.

## Constraints

- Production change is exactly one package relocation. Preserve annotations, constructor, static guard, methods, logging and behavior byte-for-byte after package normalization. Do not add a compatibility shim or reset API.
- Preserve `taxonomy.git.bootstrap` default true; primary/system repository selection in the constructor; `ApplicationReadyEvent` and `InitializationReadyEvent`; readiness-before-guard ordering; JVM-wide one-shot behavior; branch `draft`, namespace `default`, author `system`, exact commit message; existing-head no-op; IO/runtime failure guard reset and later retry.
- Keep every existing test assertion. Preserve all budgets and changed-source thresholds: the destination already has 77% line / 59% branch coverage protection and a changed-source prefix. The versioning-service budget remains.
- Do not change DSL storage, schema migration, DSL document orchestration, ports, journal/checkpoints, Spring selection policy, observation inventories, workflow/review gates, context classifications or cycle exceptions. Storage and the broader graph remain separate work; do not close the parent issues.
- Only edit `/workspace/scratch/5f847bdd1880/Taxonomy-bootstrap-composition`. Root owns the one serial Maven reactor, measured baseline, independent review, Git commits and publication. Implementer must not run Maven, commit, push, edit another worktree or start agents.

## Task 1: Relocate startup orchestration and enforce workspace purity

**Files:**

- Move `taxonomy-app/src/main/java/com/taxonomy/versioning/service/GitRepositoryBootstrap.java` to `taxonomy-app/src/main/java/com/taxonomy/composition/dsl/service/GitRepositoryBootstrap.java`.
- Create `taxonomy-app/src/test/java/com/taxonomy/ArchitectureWorkspaceAuthorityBoundaryTest.java`.
- Create `taxonomy-app/src/test/java/com/taxonomy/composition/dsl/service/GitRepositoryBootstrapTest.java`.
- Add `ArchitectureWorkspaceAuthorityBoundaryTest` to both `pom.xml` and `.mvn/verification-suites.json`, retaining every selector currently present. Root later unions the D1/D2/D3/gate metadata from upstream.
- Create `docs/dev/GIT_BOOTSTRAP_COMPOSITION.md`. Update the remaining-bootstrap statements in `docs/dev/DSL_DOCUMENT_COMPOSITION.md`; clearly distinguish historical D3 counts from the current source ownership. Do not edit canonical EN/DE boundary pages yet: root integrates their corrected D3 versions first, then adds the measured D4 subsection. Do not modify the historical D3 plan.
- Do not refresh `.github/architecture-dependency-baseline.json`: root records the exact failing ratchet JSON from fresh production bytecode after integration.

**Architecture contract:** The new guard imports production classes only. Require the new bootstrap FQCN, reject its old FQCN, and explicitly require representative classes in all three workspace packages so absence cannot pass silently. Forbid production `workspace..`, `versioning..` and `editor..` from directly depending on application composition packages (`composition..`, `shared..`, `security..`, `observability..`), knowledge packages (`catalog..`, `relations..`, `search..`), `architecture..`, `portfolio..` and `dsl.export..`. Do not prohibit framework-free DSL/domain/export APIs or workspace-owned ports. Keep existing guards unchanged. Observe the new rule fail on the three pre-move bootstrap edges and missing destination owner, then pass after the move.

**Behavior evidence:** Add focused tests of the real startup event wiring and observable repository state. Prefer an actual in-memory `DslGitRepository` with a mocked factory/export boundary and a real `AppInitializationStateService`; reuse the existing DSL tests' JGit setup. Use a small `AnnotationConfigApplicationContext` or `ApplicationContextRunner` with only the bootstrap and its dependencies. Publish the real readiness events and verify that readiness after an early application-ready event creates exactly the expected draft commit, while repeated events do not create additional commits. Cover ready-at-application-start, existing-head preservation, disabled property/no bean, default-enabled property, and IO/runtime failure followed by a successful retry. Failure injection may use a spy over the real repository; keep the actual successful write/read path real. Assert system repository selection, exact content/author/message and no workspace/central fallback. Inspect the local Spring/JGit API if needed; do not invent constructors.

The JVM-wide `AtomicBoolean` has process-wide effects in tests. Save its original value, reset it only in isolated test setup through test reflection, and restore it in cleanup. Do not alter production state APIs or leave a reset guard for other tests. A targeted mock-based test may cover a hard-to-reach failure, but avoid a suite that only repeats implementation calls.

- [x] Add the non-vacuous ownership guard and observe RED against pre-move production output in unique standalone directories.
- [x] Add startup contracts against the old class first where possible to establish existing behavior; then relocate only the package and change the test import/owner. Record all assertions retained.
- [x] Run focused standalone compilation/tests with the explicit Mockito agent and JDK21; use D2's fresh Surefire classpath from its XML as dependency evidence, compiling the changed bootstrap into the unique output before tests. Do not run Maven concurrently with root.
- [x] Audit all source/config/dynamic references to `GitRepositoryBootstrap`. Keep bean name and event/conditional annotations intact; no direct production caller exists at the inspected base. Update only references actually affected.
- [x] Update selectors and concise developer ownership documentation without inventing test counts or baseline changes. Verify normalized old/new production source equality and `git diff --check`.
- [x] Write a full implementation report and RED/GREEN evidence under this plan's ignored SDD directory; freeze source for root review. No commit or publication by the implementer.
- [ ] Root integrates final D3 metadata/ancestry, measures and records the exact baseline, runs the focused full reactor and final `verify -DexcludedGroups=real-llm` with the pinned ONNX model, checks package/source coverage without lowering floors, obtains independent review and publishes a reviewable stacked PR. Local verification does not substitute for canonical `-Pci`, database/security/product/UI/recovery checks on the final head.
