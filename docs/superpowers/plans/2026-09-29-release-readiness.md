# Release 1.4.0 readiness implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement each assigned task. Steps use checkbox syntax for tracking.

**Goal:** Resolve the confirmed release audit defects and make the English and German documentation describe the shipped behavior.

**Architecture:** Keep independent root relevance distinct from budgeted child distribution. Gate relation generation on provider capabilities, retaining explicit incomplete evidence for embedding-only providers. Reuse the existing ONNX implementation and evaluation work from PR #1141.

**Tech Stack:** Java 21, Spring Boot, Maven, JUnit, Hibernate Search/DJL, JavaScript, Markdown.

**Spec:** The user accepted the 2026-09-29 release audit (K1–K3 and D1–D6). Confirmed scope is recorded below so the plan is self-contained.

## Global constraints

- Preserve child-budget normalization, including a single child.
- Root relevance 0, 20 and 100 must remain 0, 20 and 100 in normal, streaming and local-provider paths.
- LOCAL_ONNX supports embeddings, not generative relation JSON; do not turn missing evidence into confirmed absence or retry an unsupported operation.
- Preserve public API contracts; fix stale examples rather than changing controllers to match them.
- Do not run destructive migrations or publish a release without candidate verification.
- GUI/LLM/document-language work is an investigation and concrete design recommendation in this scope.

## Review focus

- A legitimate one-child group must still receive its parent's budget (task 1 regression).
- Paused/replayed analysis must retain the scoring question's semantics (task 1 checkpoint review/test).
- An embedding-only provider must leave relation evidence explicitly incomplete (task 1 regression).
- Node-ready PARTIAL indexing must not be mistaken for unusable search (task 2 regression).
- Backup instructions must select the running Compose service's real volume (task 3 command review).

### Task 1: Scoring and provider contract

**Files:** LlmService, LlmResponseParser, scoring/prompt types and templates, RequirementRelationSearchService, corresponding analysis/app tests; scoring and decision-pipeline documentation.

**Interfaces:** Introduce a typed distinction for independent root relevance and child budget allocation; keep existing public child-scoring behavior. Expose a typed generation capability at the provider boundary.

- [x] Add failing root 0/20/100 regressions and a single-child budget regression.
- [x] Run focused tests; record environmental blockers distinctly from test failures.
- [x] Implement root prompt/parser semantics across provider, streaming and checkpoint paths.
- [x] Add and implement an embedding-only relation-search regression asserting incomplete evidence and no unsupported model call.
- [x] Run focused tests and review consumers/exports of the relation status.

### Task 2: Integrate ONNX corrections

**Files:** PR #1141 implementation/evaluation files, LocalEmbeddingService, ONNX Maven/workflow setup, OnnxReferenceCases/Evaluation and tests.

**Interfaces:** Explicit normalized CLS embeddings; bounded ANN candidate exploration separate from returned top-K. Canonical catalogue fingerprints and node-search readiness reuse production contracts.

- [x] Integrate PR #1141 without reverting changes already merged on main.
- [x] Add regressions for node-ready PARTIAL and canonical catalogue identity; implement the remaining review fixes.
- [x] Run available focused tests and keep German reference misses visible.

### Task 3: Documentation and release notes

**Files:** docs/en and docs/de operating, database, Git API, preferences, workspace and configuration guides; docs/dev verification/release guides; release_notes.md and 1.4.0 notes.

- [x] Correct backup/restore, index recovery and database qualification instructions against source.
- [x] Correct API parameter names, paths and JSON request records against controllers.
- [x] Align workspace/preferences, recovery, provider and verification documentation.
- [x] Summarize the complete 1.4.0 scope consistently and run link/release-contract checks.

### Task 4: Language decision and final verification

**Files:** PromptTemplateService, locale configuration, preferences registry/UI, report and reformulation consumers (read-only investigation).

- [x] Trace UI locale through prompts, async execution, persisted rationale and exports.
- [x] Compare UI-coupled, input-derived and optional fixed language behavior; recommend concrete defaults and historical-result semantics.
- [x] Review the complete diff, execute available verification and report remaining release gates without overstating evidence.

## Verification outcome

The implemented scope and available checks are complete; see
[executed evidence and remaining release gates](../../testing/release-readiness-2026-09-29.md).
Full candidate CI, upgrade/restore and browser acceptance remain prerequisites for
publication. Language controls remain a reviewed proposal, not shipped settings.

## Follow-up acceptance

- [x] Reproduce and correct the rendered-root-prompt/scenario-playback mismatch;
  retain strict source/scope validation and independent scoring.
- [x] Add the 50→150 Preferences component regression and strengthen the existing
  ADMIN browser scenario's visible UI and authoritative draft assertions.
- [x] Download and verify the original 1.3.0 release JAR, create persistent data,
  stop the old application and restore its backup under the old version.
- [x] Correct the reproduced HSQLDB upgrade failure before Hibernate/catalogue
  initialization, including newly required fields in populated portfolio tables;
  preserve repository provenance and final database constraints.
- [x] Re-run the local upgrade, restart and restore probes with the corrected
  diagnostic overlay, including historical reads and new analysis/snapshot writes.
- [x] Repeat upgrade acceptance with the unchanged CI application artifact;
  artifacts `11019914459` and `11020754924` pass the authentic 1.3 upgrade/restart
  probe. Subsequent fixture-only changes do not alter application code.
- [x] Correct the final-suite repository-provenance, Mockito setup and lineage
  response fixtures, retaining production constraints and result assertions.
- [ ] Complete required CI/browser/scenario/ONNX/database gates on the final head.

The authentic 1.3.0 → first-candidate probe found a release blocker: adding new
NOT NULL columns to populated HSQLDB tables fails before the existing application
runner can backfill them. The correction passes the local probe and targeted
review; immutable-candidate acceptance and final CI remain release gates.
