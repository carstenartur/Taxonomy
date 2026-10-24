# Test execution ownership implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Changes are already authorized by the user.

**Goal:** Remove verified repeated test execution while preserving the required verification union.

**Architecture:** Existing Maven profiles and the verification catalogue own selections. Original JUnit evidence from each authoritative lane is collected once. An opt-in test-only JUnit Platform filter suppresses inherited methods during discovery.

**Tech Stack:** Java 21, Maven Wrapper, JUnit Platform/Jupiter, existing Python/shell evidence tooling and GitHub Actions.

**Spec:** docs/superpowers/specs/2026-10-24-test-execution-ownership-design.md

The checkboxes record local work before publication. Final remote acceptance,
review state and measured report counts are recorded in the pull request and
its source-bound CI evidence.

## Global Constraints

- Base the branch on main; do not stack on the previous PR.
- No new workflows, global test disabling, weakened assertions or coverage thresholds.
- Keep full standalone verification complete; scope ownership changes to existing CI companion profiles.
- Preserve database integration variants, packaged integrations, build guards, browser cases, original XML and source binding.
- Use the project's managed JUnit version and a test-scoped launcher dependency only where needed.
- No remote model calls. Put raw evidence under target/test-optimization.
- File ownership is exclusive. Only the parent stages, commits and publishes.
- Serialize Maven runs using /tmp/taxonomy-test-optimization-maven.lock; do not race shared target outputs.

## Review Focus

- A missing or failed dedicated report must still fail the final gate.
- Core and dedicated JavaScript modes must not both execute the same contract selection.
- Unit-test suppression must not propagate to Failsafe or independent standalone profiles.
- Annotated subclass suppression must retain its own parameterized tests and leave unannotated contracts intact.
- Browser preparation must still install the pinned runtime, and the following verify must execute its tests.

### Task 1: Opt-in inherited-test discovery filter

**Files:** taxonomy-app/src/test/java/com/taxonomy/testsupport; taxonomy-app/src/test/resources/META-INF/services; taxonomy-app/pom.xml; the two Reformulation*ContractTest subclasses.

- [x] Verify the current nine/eleven/eleven occurrence inventory from the retained audit.
- [x] Add a compact real-launcher regression proving inherited methods/templates are removed only for annotated classes, base/own cases remain, and removed cases are not skipped.
- [x] Implement IgnoreInheritedTests and its PostDiscoveryFilter using public launcher/descriptor APIs.
- [x] Register it in the test ServiceLoader and annotate only the two identified subclasses.
- [x] Execute the filter regression and real three-class selection; verify nine/two/two cases and zero skips.
- [x] Obtain independent task review.

### Task 2: Single ownership of CI suites

**Files:** root/tooling/build POMs, the parentless Mermaid plugin POM, .mvn/verification-suites.json, .github/workflows/ci-cd.yml, existing evidence scripts/tooling and relevant existing contract tests. Do not edit taxonomy-app/pom.xml or the document workflow.

- [x] Adapt existing selection/evidence regressions to require one JavaScript owner and original plugin-lane evidence; reproduce failures before changing selections.
- [x] Exclude JavaScript contracts only from split core; keep the existing fast contract job and standalone verification.
- [x] Assign the two packaged plugin suites solely to the existing plugin lane in split CI, and stage their original reports exactly once with fail-closed completeness checks.
- [x] Suppress already-owned upstream Surefire suites only in database/plugin companion profiles, explicitly retain build guards and Failsafe selections.
- [x] Verify effective configurations, targeted existing contracts and report replay including missing-report negatives.
- [x] Obtain independent task review.

### Task 3: Document workflow preparation

**Files:** .github/workflows/document-template-report-e2e.yml and the existing document lifecycle contract owner. Coordinate with Task 2 before modifying any shared test file.

- [x] Verify that pre-integration-test followed by verify currently traverses unit tests twice.
- [x] Change runtime preparation to avoid the first test execution, retaining pinned browser installation and the complete following verify.
- [x] Adapt the existing focused contract and execute the exact native preparation command, including a Chromium launch.
- [ ] Complete native document verification in CI.
- [x] Obtain independent task review.

### Task 4: Integration and publication

**Files:** docs/dev/MAVEN_VERIFICATION.md and the implementation documents.

- [x] Document exact suite ownership and opt-in inherited-test semantics.
- [x] Run combined focused Maven verification and inspect actual XML selections.
- [x] Review the complete branch independently; resolve material findings.
- [ ] Publish one reviewable PR from main and verify all required CI lanes, actual final test inventory and review feedback.
- [ ] Report measured changes, passed checks and any limits without promising unmeasured wall-time savings.
