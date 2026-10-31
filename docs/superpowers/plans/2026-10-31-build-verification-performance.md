# Build verification performance implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Track implementation, regression evidence and review in the task ledger.

**Goal:** Implement all five approved optimization areas with preserved Maven ownership and verification integrity.
**Architecture:** Independent local branches for test distribution, coverage replay and cache work; integrate into one branch/PR. The controller implements change scope and coordinates shared workflow/POM edits.
**Tech Stack:** Java 25, Maven, JUnit, GitHub Actions, Bash/Node tooling, Docker BuildKit.
**Spec:** docs/superpowers/specs/2026-10-31-build-verification-performance-design.md

## Global Constraints

- Full current test inventory, ONNX and required database variants remain covered.
- Maven owns all selection and verification; no new workflow files.
- Preserve source commit/tree, JAR/class/coverage digests, positive JUnit results and complete coverage policies.
- Full verification on main, tags, unknown/mixed changes and release requests; documentation evidence is explicitly scoped.
- Keep Spring context cache size 1 and browser JVM isolation until independently proven safe.
- One consolidated PR; no direct main writes, production deployment, release or unrelated issue mutations.

## Review Focus

1. New runtime selection must preserve ONNX enablement and PostgreSQL tests.
2. Removed replay work must not remove non-app class/execution data or the fresh coverage policy.
3. Documentation classifier must reject source/config changes, renames across the allowlist boundary, unsafe paths and malformed diffs.
4. Final gate must fail on a failed planner, unexpected skip, mismatched source/digest, and every draft PR.
5. Restored cache/archive/build outputs must never satisfy differently scoped fresh test evidence.

### Task 1: Measured application groups and ongoing timing evidence
**Files:** .mvn/verification-suites.json; root/app POM profiles; selected application test classes; AppTestShardVerifier and its tests; workflow shard arguments.
**Interfaces:** Produce runtime and domain-integration group/profile IDs; preserve all existing group IDs and report schema compatibility.
- [x] Add negative/positive inventory and selector regression tests.
- [x] Add the ten runtime and 45 focused domain classes from the audited JSON and profile/tag selection.
- [x] Preserve explicit ONNX/model/Postgres requirements and special core execution identities.
- [x] Expose group/class timing summaries and drift visibility from existing evidence without rerunning tests.
- [x] Run focused Maven tests, self-review and commit.

### Task 2: Coverage replay and packaging ownership
**Files:** POM coverage profile/modules; coverage staging workflow/scripts; coverage and packaging contract tests; package-ui-application.sh.
**Interfaces:** Consume original verified core classes/coverage plus all shard coverage. Preserve ReactorCoveragePolicyIT and original report/digest contract.
- [x] Reproduce duplicate Mermaid/ExternalGitSync replay and repeated compilation with meaningful profile/effective execution tests.
- [x] Implement a lean Maven-owned reporting/coverage path that preserves all module data and fresh policy checks.
- [x] Avoid redundant early UI SBOM/test compilation where authoritative reports/runtime resources remain complete.
- [x] Run focused checks and representative replay/package verification; self-review and commit.

### Task 3: Dependency, Docker and application cache efficiency
**Files:** Dockerfile; ProductionImageIT/helper; existing CI/delivery/release workflows; cache helpers/contracts; .mvn build-cache config if justified.
**Interfaces:** Default local Docker builds remain valid; CI provides optional persistent backend parameters through Maven-owned verification. Unknown/missing cache falls back to a real build.
- [x] Add behavioral regressions for Docker cache arguments and cache-key coverage.
- [x] Implement cross-run Docker layer reuse and explicitly handle Maven dependencies/cache mounts.
- [x] Include standalone Maven POMs in dependency keys; remove browser restore from browser-free contracts.
- [x] Reduce unnecessary large application cache writes/history and publish useful cache evidence.
- [x] Run focused tests and a feasible Docker validation; self-review and commit.

### Task 4: Conservative change scope, documentation verification and draft handling
**Files:** taxonomy-tooling scope/verification code and tests; Maven documentation profile; existing ci-cd.yml and related contract tests.
**Interfaces:** scope in full/documentation/draft; exact commit/tree and changed paths recorded. Final Maven verification evaluates mode-specific required evidence.
- [x] Test whitelist classification, mixed changes, renames, unknown/empty diff and non-PR full fallback.
- [x] Add real documentation/package validation with positive JUnit and distinct scoped evidence.
- [x] Wire planner, draft conditions, documentation lane and always-running final gate into the current workflow.
- [x] Refine demonstrably overbroad plugin README performance triggers.
- [x] Verify full-mode invariants and fail-closed documentation/draft behavior.

### Task 5: Targeted Spring fixture work and integrated verification
**Files:** Selected slow application tests/fixtures, docs/dev/CI_PERFORMANCE.md, Maven verification/cache docs, release_notes.md.
- [x] Inspect measured slow Spring tests and remove demonstrably unnecessary start/fixture work without narrowing intended integration checks.
- [x] Run affected Spring tests without changing their original assertions; validate explicit ONNX opt-out separately.
- [ ] Integrate all tasks, update current/historical metrics documentation and run focused complete contract tests.
- [ ] Obtain independent task and whole-branch review; fix findings.
- [ ] Publish one PR and resolve exact-head CI failures.
- [ ] Record actual full-run times and limitations; finish with concrete PR/status evidence.
