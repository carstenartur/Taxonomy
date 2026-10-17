# Comprehensive QA Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Correct the sixteen approved QA findings and the identified verification/upgrade gaps without reducing required test scope.
**Architecture:** Repair existing authority, analysis and export boundaries; preserve compatibility constructors and exact-context checks. Independent packages are implemented in isolated branches and integrated only after focused verification.
**Tech Stack:** Java 21, Maven/JUnit, Spring, Hibernate/JGit, existing browser adapters.
**Spec:** `docs/qa/2026-10-17-remediation-contract.md` and the approved QA report.

## Global Constraints
- No new test framework or workflow; new regression tests are JUnit owned by Maven.
- No lower coverage floors, broad test exclusions, fake completion states or live paid LLM calls.
- Preserve exact repository/workspace/branch authority, checkpoint immutability and truthful human-review semantics.
- Do not commit generated QA logs, browser binaries or report artifacts.

## Review Focus
- Revoked read vs downgraded contribute rights must be checked independently (Task 1).
- An accepted first batch must survive a later thrown failure and remain visible in coverage/export (Task 2).
- Null origins and equal-score provisional edges must not look confirmed after export (Task 3).
- HTML links, code spans, table delimiters and literal markup must remain safe and readable (Task 4).
- Nested/parameterized suites, skipped cases and unowned source tests must fail the evidence gate accurately (Task 5).

### Task 1: Workspace authority and central reads
Files: `taxonomy-workspace` sync/context/editor services/controllers and focused JUnit tests. Interfaces: existing source membership and context ports; no export-model changes.
- [x] Add failing current-policy, central-read and two-repository compare regressions.
- [x] Recheck source policy per sync operation; route central reads through Git snapshots; pass selected context to comparisons.
- [x] Run focused JUnit, review and commit.

### Task 2: Analysis truth and provider continuity
Files: `taxonomy-analysis`, analysis DTOs, `core/taxonomy-scoring.js`, `core/taxonomy-browse.js`; relevant JUnit wrappers. Interfaces: preserve completed score/reason accumulators; carry provider/operation pins through existing APIs. Own export-control visibility in taxonomy-browse.js.
- [x] Add failing timeout/late-failure and completed-analysis follow-up tests.
- [x] Keep failed products unassessed, preserve earlier batches and bind follow-up provider explicitly/authoritatively.
- [x] Retain zero-only export controls; run focused JUnit, review and commit.

### Task 3: Diagram fidelity and provenance
Files: diagram DTOs, `taxonomy-export`, provisional relationship pipeline; export JUnit tests. Interfaces: extend neutral edge metadata compatibly; inform Task 4/root of exact additions.
- [x] Add failing wide-label, canonical-color and origin-preservation tests.
- [x] Measure/wrap labels, retain full text, unify palette and retain/display relation provenance/counts.
- [x] Verify SVG/PDF/Visio output and commit.

### Task 4: Safe complete textual reports
Files: `taxonomy-reporting` HTML/text/DOCX renderers and JUnit tests. Interfaces: use Task 3 metadata when present; do not change taxonomy-export implementation.
- [x] Add failing literal-markup/link and mixed recommendation/suggested-relation fixtures.
- [x] Escape before formatting and preserve Word section/content parity.
- [x] Render/check representative reports, run JUnit and commit.

### Task 5: Test authority, evidence and critical path
Files: `taxonomy-tooling`, `taxonomy-build`, root/CI POM selection, `.github` verification scripts/catalogue and developer verification docs. Interfaces: preserve existing test inventory/outcomes, source/digest contracts and all statuses.
- [x] Reproduce orphaned validator selection and report count mismatches through JUnit.
- [x] Give tests a JUnit/Maven owner, fail closed on inconsistent outcomes, preserve inventories and reduce measured repeated root/coverage work.
- [x] Re-run policy/selection tests and document any remaining verified operational prerequisite without claiming full parity.
- [x] Review and commit.

### Task 6: Browser exports, login and Oracle migration fixtures
Files: `shared/taxonomy-export.js`, local security/login controller/template/i18n, existing Oracle migration JUnit fixtures. Interfaces: Task 2 owns taxonomy-browse visibility; Task 3 owns server diagram types.
- [x] Add failing PDF pan/zoom, zero-row CSV, German login and interrupted-migration tests.
- [x] Export full model bounds, preserve explicit zeros, provide localized secure login and test every migration phase.
- [x] Run focused JUnit and commit.

### Task 7: Integration and acceptance
- [x] Review each package against the contract and integrate isolated commits.
- [ ] Run applicable full Maven verification, browser/artifact checks and fresh independent branch review.
- [ ] Publish a concrete PR/change set; preserve honest CI/build/deployment status and finish all actionable review findings.
