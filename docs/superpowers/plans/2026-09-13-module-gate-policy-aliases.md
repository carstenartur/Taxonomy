# Module gate policy boundary and directory aliases

> For agentic workers: use `superpowers:subagent-driven-development` for this bounded external-review follow-up. Root owns planning, serial native Maven verification, review and publication.

**Goal:** Close confirmed current-head review gaps in PR #1054 without weakening the extraction gate or its documented support for safe checkout-local aliases.

**Base:** `78e391ab2aa5a96978501a70795422c5d6c020ac`, tree `d1422f538583e74b6d872e5a6f10140a2bde3f60`. Main remains `746e1ce6d9c33eabef1cff06618e8802876949a2`.

## Global constraints

- Work only in `/tmp/taxonomy-module-gate-fix-20260913`. Do not mutate Git state, commit, publish, run Maven, edit another worktree, or spawn subagents as implementer. Root owns native verification and publication.
- Preserve all 91 existing fixture methods and all their assertions, separate managed temporary roots, ordinary mandatory downstream Surefire enforcement, synchronized architecture selectors, and the build report owner/path.
- Preserve the pure graph evaluator, in-memory compiler declaration inventory, source-to-module identity, fail-closed effective-POM behavior, support-module presence checks, and independent safe-extraction semantics.
- No production source, POM dependency, workflow, baseline, context-map content, cycle exception, coverage floor, tag, skip, inventory exclusion, waiver, or human/exact-head review-rule change.
- Every consumed policy, source and output must stay in the checkout before reading, compiling or importing. Safe aliases inside the checkout remain supported. Reject external links and directory cycles before traversing them; do not use a blind follow-links traversal that visits outside content first.
- Test real adapter behavior with actual filesystem/compiler fixtures. New external targets must be owned by the existing managed external fixture root. No sleeps, broad cleanup, test-only adapter bypass, or assertions weakened to accept failures.

## Task 1: Validate the policy input and preserve logical inventory for safe directory aliases

Review 5188015344 on the exact base raises these findings:

1. `evaluateRepository` reads `.github/architecture-contexts.json` before applying the checkout-boundary helper. An external policy symlink can provide the policy controlling the gate. Validate the policy path, including linked ancestors, before reading policy content. Retain safe local policy aliases.
2. `repositoryFiles` validates its root but walks with `Files.walk(root)` without following directory links. A safe `src/main/java` or `target/classes` root alias is treated as the directory link alone and its inventory is omitted. Preserve the documented support for safe in-checkout directory aliases, including meaningful nested aliases, while retaining logical paths for package and physical owner mapping and checking boundaries before descending. Directory cycles must fail explicitly rather than hang, overflow, or truncate silently. Confirm the real ArchUnit import as well as the inventory accepts the same safe roots.

First establish fresh RED evidence against the exact published adapter. Cover an external policy file and linked policy ancestor before parsing, safe policy controls, source-root and output-root aliases and a meaningful nested safe alias. Include negative external/cyclic directory controls where the traversal change makes them relevant. Existing controls should continue to pass; report actual RED failure reasons without calling an already-passing case a new reproduction.

Then make the smallest cohesive adapter fix and rerun every fixture, preserving all prior methods/assertions and the unchanged evaluator. Update only directly affected statements in `docs/dev/MODULE_EXTRACTION_GATE.md` if needed to make policy/traversal behavior precise; do not narrow the safe-alias contract to avoid implementation.

Write the full report with exact changed paths, commands, RED/GREEN counts and reasons, warnings, limitations and remaining native gates in the ignored SDD directory. Freeze for root review. Root separately investigates the unrelated CI temporary Git-directory cleanup failure; do not edit tooling tests under this task.

- [x] Reproduce policy/alias findings against the exact published adapter with new regression tests.
- [x] Implement the bounded fix and pass all existing and added fixtures.
- [x] Freeze the source with a complete implementation report.
- [ ] Root runs native covering tests/architecture profile, obtains scoped and final review, publishes the exact reviewed tree and requests fresh external review/CI.

## Existing evidence and remaining integration

The previous tree passed a full clean reactor locally and canonical core CI. New PR1054 review changes and one SQL Server job remain red. That SQL job failed in unchanged `ReleaseRequestAnchorContractTest` during JUnit temporary-directory cleanup (`DirectoryNotEmptyException` for `.git`), before the DB test phase; this is investigated separately, not evidence for changing database code.

D2 PR #1056 retains its stable technically-green head and requires a real human confirmation under the repository review gate. Do not post that attestation, change the rule or merge another PR first merely to invalidate it. #628 and physical feature extraction remain incomplete.

## Controller validation

Fresh standalone RED against the exact published adapter ran 101 tests: all 91
prior fixtures and four new controls passed, while six policy-read, alias-inventory
and cycle cases failed for the expected reasons. The final adapter passed all 101
with real JUnit. The pure evaluator and all prior fixture methods/assertions remain
unchanged. Expected JUnit notices explain deletion of deliberate external links
without following their targets into the separate managed temporary root.

The complete native reactor architecture profile passed in 46.135 seconds on
Java 21: 18 application tests and 102 build tests, including all 101 fixtures and
the real repository gate, with zero failures, errors or skips. This fresh reactor
compile and inventory/import run covers the changed adapter; it is not a new full
`verify -Pci` claim. Canonical CI and current-head review follow publication.

## Task 2: Close report-write and Maven-coordinate review gaps

The complete external review `5189621863` on published head
`af8f447bc5f1d0a373f5cf10792b68b510260518` reports three additional gaps:

1. The real test writes `taxonomy-build/target/architecture-module-graph.txt`
   without validating the report path. A linked output directory or report file
   can redirect the write outside the checkout. Validate the report and its
   existing ancestors before directory creation or writing; retain safe local
   aliases and the existing report location. Add real filesystem regressions
   proving outside files remain unchanged and outside directories receive no
   report, plus passing ordinary/local-alias controls.
2. The owner/dependency fixture indexes direct build dependencies only by
   artifactId. An unrelated group can satisfy the app/coverage/tooling/ArchUnit
   assertions. Require the complete groupId:artifactId coordinate, retain
   type/scope assertions, and prove same-artifact dependencies from a different
   group cannot satisfy the real contract. Expected coordinates are
   `com.taxonomy:taxonomy-app`, `com.taxonomy:taxonomy-coverage`,
   `com.taxonomy:taxonomy-tooling`, and `com.tngtech.archunit:archunit-junit5`.
3. A non-reactor local parent found through relativePath is filtered out if its
   raw artifact expression differs from the child's literal artifact. The
   concrete case is candidate `build-${parent.module}` resolving to
   `build-local-parent`, referenced literally by the child. Preserve effective
   coordinate matching, including inherited runtime and managed dependency
   edges, without inheriting unrelated external parents. Add a non-reactor
   parent regression covering an inherited application edge and retain existing
   raw/effective identity, version, profile-uncertainty and external-parent
   controls. If actual Maven semantics contradict the finding, establish that
   with the real cached ModelBuilder rather than guessing.

First reproduce each claimed defect against the exact published behavior with
real JUnit/filesystem/POM tests, then implement the smallest cohesive fix in the
adapter/fixture class. A package-visible report-writing seam is permitted if the
real test delegates to it. Preserve all 101 current fixtures; the ownership
fixture's artifact-only assertions must be strengthened to the coordinates
above, which is the sole intentional exception to the original assertion-text
freeze. The pure graph evaluator, report semantics, production code, reactor
dependencies, selectors, policy and enforcement baselines stay unchanged. Direct
gate documentation may be updated only for these corrected contracts.

No Maven, Git state changes, external publication or subagents by the implementer.
Use Java 21 and the cached real JUnit launcher; root runs the one native Maven
reactor. Write commands, RED/GREEN counts and exact failure reasons, preserved
controls and remaining limits in task-2-report.md in this plan's SDD directory.
Freeze source for one scoped review of this complete final-review fix wave.

- [ ] Reproduce all three findings with meaningful controls.
- [ ] Correct the bounded adapter/fixture contracts and pass the complete fixture suite.
- [ ] Root performs native verification, scoped review and publication.

D2 confirmation was accepted by the repository and PR #1056 was squash-merged
as `5d1d0ee88b1fdd4f7d0d9b37a6925bf4d464ccfa`. The earlier hold on main merges
has therefore ended. Root handles subsequent stack integration in a separate
worktree; it must not mutate this adapter during implementation.

## Task 3: Recognize POM recursion through safe local aliases

The new complete external review `5189764165` of published head
`42ce0ff0ac865e2d3b86bfcf687d48bfc9b8b503` raises two previously unreported
POM-recursion identity gaps. Task 2 is completed; its three findings are fixed
and its scoped review is clean. This task addresses the new traversal cases.

1. `localPom` tracks normalized logical paths in `resolving`. A two-POM parent
   chain can reach the same file through a local directory alias while building
   an unfinished model, so every logical path differs and the cycle guard is
   missed. After checkout-boundary validation, track `toRealPath()` identity in
   the active recursion set and remove that same identity on exit. Preserve
   logical paths for diagnostics, model cache keys and relative-path resolution.
2. `collectModules` likewise stores only normalized logical POM paths in
   `visited`. A declared property-artifact module can revisit its own physical
   POM through aliases before deferred artifact registration catches it. Validate
   containment before canonical resolution and track canonical POM identity for
   duplicate/cyclic declaration detection, retaining logical module ownership and
   error paths.

Add real filesystem/POM regressions for the two-POM alias parent cycle and a
declared property-artifact module alias cycle. Both must fail promptly with the
intended explicit cycle error, not an eventual stack overflow, missing-file
fallback or OS symlink-depth exception. Establish honest RED against the exact
published adapter and record the actual old failure mode; the review's claim of
an overflow is a hypothesis, not a result to assume. Retain positive support for
ordinary local aliases and shared acyclic parent models; add a focused passing
control if existing tests do not cover reuse across distinct aliases.

Preserve all 108 current fixture methods/assertions, the nine synchronized
architecture selectors, logical cache/ownership paths, effective Maven-coordinate
checks, all earlier containment/report fixes and the unchanged pure evaluator.
No production code, dependencies, enforcement data, workflow, skip or waiver
changes. Do not canonicalize every path indiscriminately or treat a shared parent
as cyclic after its active resolution has ended. Direct documentation may clarify
only these POM cycle semantics.

The sole implementer owns the adapter, fixture class and directly affected gate
documentation in the existing gate worktree. Root owns Git, serial native Maven,
review and publication. No subagents or Maven by the implementer. Use fresh
task-3-specific ignored JUnit compilation/log paths and write task-3-report.md;
leave all Task 1/2 evidence intact. Freeze for scoped independent review.

- [ ] Reproduce both new alias-recursion findings and preserve acyclic controls.
- [ ] Implement canonical recursion identity and pass the complete fixture suite.
- [ ] Root performs native verification, scoped review and publication.

## Task 4: Integrate the reviewed workspace storage boundary without dropping guards

Tasks 1–3 are complete and published at `71db619b1bde94414c04d4b99ac5dc6492f7088c`; do not reimplement or re-review their unchanged adapter/fixtures. The approved D5b source is now published as draft PR #1060 at `2122b52eabae0edce69d2674c6b2a98cfa050985`, tree `e21d98e7a847de777ec6985b28b24ce003143551`. Its production ownership, policy, schema preservation, 779 native tests and source coverage have already received complete and scoped reviews. #1054 cannot merge on its previous base because the architecture selector lists conflict with the newer guards.

Root creates the integration merge in the existing isolated gate worktree. The sole implementer resolves only `pom.xml` and `.mvn/verification-suites.json`: replace the conflicting architecture test values with this exact ordered union in both files:

```text
ArchitectureTest,ArchitectureCycleBoundaryTest,ArchitectureExceptionLedgerTest,ArchitectureCycleRuleRegressionTest,ArchitectureContextDependencyRatchetTest,ArchitectureDecisionReportBoundaryTest,ArchitectureCommitHistoryOwnershipTest,ArchitectureDslCompositionBoundaryTest,ArchitectureWorkspaceAuthorityBoundaryTest,ArchitectureApplicationSchemaCompositionTest,ArchitectureWorkspaceStorageOwnershipTest,ArchitectureModuleGraphTest,ArchitectureModuleExtractionTest,ArchitectureSelectorSynchronizationTest
```

Retain all 11 selectors from D5b and all three module-gate selectors, with no duplicates. Preserve the POM/profile structure, every other property, all JSON keys and values, canonical CI command, every existing fixture method and assertion (111 current fixtures), all graph/inventory/path/coordinate behavior, runtime and source files, current D5b baseline, contexts, SQL, workflows and coverage floors. This task changes no production behavior and creates no physical feature module. Do not resolve either file by dropping the other side.

Validate both XML and JSON parsers, exact ordered selector equality, existence of each selected test class, and absence of conflict markers. Report exact commands/output and changed paths to `task-4-report.md` in this plan's ignored SDD directory. No new tests are required for this selector merge; root runs one fresh clean native architecture profile to verify the actual combined source/binary inventory and all guards. No Maven, Git/index/HEAD/branch mutation, external API, subagents or edits outside the two files by the implementer.

Root verifies all non-conflicted files against the pre-resolution automatic merge tree, commits, obtains a task-scoped integration review, then updates #1054 as a bounded draft stacked on #1060. The previous gate implementation and approved D5b source are inherited unchanged; no second broad review sweep is needed for those bytes. Current-head external review and canonical CI remain required, and no merge into a feature branch or human attestation by automation is authorized. Retarget to main only after predecessor integration.

## Task 5: Preserve classifier and source-file identity in ownership contracts

Tasks 1–4 and their reviewed upstream inheritance are complete at published `49d98135043c2d223d44c75bcca8e7c6fa7b3e07`. External review `5190491718` supplies two concrete findings: comment `3999459321` identifies the ignored dependency classifier in the build-owner assertion; its suppressed comment at `ArchitectureModuleGraph.java:205` identifies the incorrect assumption that every dollar sign denotes nesting. The review's remaining summary does not provide an additional actionable file/line/behavior; all 12 earlier inline threads are resolved. Do not guess a third source change.

1. A required dependency with the correct group/artifact/type/scope but a nonempty classifier must not substitute for the required unclassified artifact. Preserve all four current ownership requirements, including group, artifact, type and scope. Distinguish the classifier in the dependency identity or apply a comparably precise check; retain legitimate unrelated dependencies and the existing unclassified requirements. Add actual POM regression evidence for each of the four required artifacts and passing controls. Do not change real POM dependencies or general effective-Maven resolution to repair this assertion gap.
2. `com.taxonomy.AppConfig$Plugin` may be a top-level declaration in `AppConfig$Plugin.java`. It must not inherit the policy's `AppConfig.java` root-composition allowance. Carry verified compiler/source-file identity into graph ownership instead of splitting binary names at `$`. The adapter already obtains the real originating source from javac and validates imported source files; preserve that authority and every freshness/containment check. Add a real compiler/adapter regression that rejects the unlisted dollar-named source and passing controls for genuine nested classes and explicitly allowed dollar-named root source files. Preserve legitimate multiple/nested/local/anonymous declarations, physical ownership and deterministic reports.

Use the existing gate worktree. Writable source paths are `taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraph.java`, `ArchitectureModuleExtractionTest.java`, `ArchitectureModuleGraphTest.java` in that same directory, and directly affected prose in `docs/dev/MODULE_EXTRACTION_GATE.md` if needed. Preserve all 111 current fixture methods and assertions; no production, POM, workflow, selector, baseline, context-map, schema, coverage-floor, exception, skip, waiver or review-rule changes. Task 5 is the explicit bounded exception to the earlier evaluator freeze: change only its verified source-identity input and root-composition decision, preserving unrelated graph semantics.

Establish honest RED for both findings against the exact published implementation using new tests that exercise real POM/compiler adapter behavior. Do not treat a compilation failure, infrastructure error or assertion on an unrelated earlier failure as a reproduction. Use task5-specific ignored compilation/log directories, existing `JunitFixtureLauncher.java`, JDK21 and the supplied native gate classpath. Then make the smallest cohesive fixes and pass all preserved and new fixture tests. Do not rerun historical task sequences. No Maven, Git/index/HEAD/branch mutation, network/API or subagents by the worker. Write exact paths, commands, RED/GREEN outcomes, warnings, source-preservation checks and concerns to `task-5-report.md` in this plan's SDD directory, then freeze.

Root runs one fresh complete native architecture profile because the inventory/evaluator path changes, compares actual graph and unchanged production/policy hashes, commits and obtains a scoped independent review of this external-finding fix. Publish the exact reviewed tree and request fresh current-head external review. Main-targeted canonical CI and genuine human confirmation remain separate gates; no physical extraction or #628 completion follows.
