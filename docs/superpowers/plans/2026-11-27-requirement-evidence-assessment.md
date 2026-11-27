# Requirement Evidence Assessment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make requirement-to-taxonomy contributions and their evidence explicit, show meaningful traffic statuses, and demonstrate source-bound refinement in a live application session.

**Architecture:** Extend existing scoring JSON with per-node assessment lists and validate against the exact input. Retain the canonical evidence across existing execution, snapshots and reports; use one shared browser presentation. Existing numeric ranking and architecture adoption remain distinct contracts.

**Tech Stack:** Java 25, Maven Wrapper, Spring Boot, Jackson 3, existing JavaScript UI and JUnit/Selenium tooling.

**Spec:** `docs/superpowers/specs/2026-11-27-requirement-evidence-assessment-design.md`

## Global Constraints

- Java 25, Maven Wrapper and existing JUnit/browser infrastructure; no new GitHub-only workflow or runtime service dependency.
- Use the exact DTO fields, enum values and bounds in the spec.
- Preserve source text, conditions, alternatives, legacy snapshots and human adoption boundaries.
- No generated evidence or numeric score is a calibrated probability or human approval.
- Changes stay in the isolated checkout `/workspace/scratch/d55e25eba3d3/Taxonomy`.
- Only one substantial Maven/application run at a time; memory and disk are bounded. Use the existing pinned frontend runtime via `-Dtaxonomy.frontend.directory=/home/agent/tax-scenario.4Wlzl23u/repo/target/test-runtime/frontend` locally to avoid a duplicate installation; this is an execution setting, not a source change.

## Review Focus

- Provider omits typed fields: legacy reply remains readable and no green status is invented (Task 1).
- Quote comes from a catalogue or translation: evidence rejected against exact source input (Task 1).
- Positive low-weight indirect claim and unknown direct claim: independent status survives every execution path (Tasks 1/2).
- New requirement version or historical snapshot: no stale classifications or auto-adoption (Tasks 2/3).
- Existing infrastructure or optional alternative: never silently promoted into required delivery (Tasks 1/3).

---

### Task 1: Source-bound semantic assessments through analysis

**Files:** Create `taxonomy-domain/src/main/java/com/taxonomy/dto/RequirementNodeAssessment.java` and validation/presentation helpers as needed. Modify `AnalysisResult.java`, `LlmCallDetail.java`, `LlmResponseParser.java`, `LlmService.java`, `LlmDetailAccumulator.java`, existing streaming/child result and distributed projection owners, `PromptTemplateService.java`, and scoring/relationship prompt resources. Tests belong to `taxonomy-domain` and `taxonomy-analysis`; add focused application boundary regression only where necessary.

**Interfaces:** Produces `getNodeAssessments()` / `setNodeAssessments(Map<String,List<RequirementNodeAssessment>>)` on both public DTOs. Provider node object adds `assessments`; use source-text-aware parsing without breaking legacy overloads. Publish `nodeAssessments` in incremental and final result envelopes. Preserve canonical stable iteration, original quotes and immutable collections.

- [x] Write and run failing tests for supported indirect, unresolved direct, neutral, conflict, multiple statements, optional/alternative conditions and invalid source quotes.
- [x] Implement the immutable DTO and contextual validation; absent legacy evidence stays absent.
- [x] Enrich existing prompts and strict parsing, carry evidence through local, interactive, streaming, partial/recovery and distributed paths. Version prompt policy; preserve existing score contracts.
- [x] Run `./mvnw -B -ntp -pl taxonomy-analysis -am test -Dtest=RequirementNodeAssessmentTest,RequirementAssessment*Test,LlmResponseParserTest,LlmResponseRootTest,LlmPreparedPromptTest,SharedChildAssessmentParserTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false` with the local runtime setting above. Log exact outcomes, including baseline/tooling limitations.
- [x] Commit the task, report touched integration paths and test evidence. Task review checks spec and quality before Task 2.

### Task 2: Shared UI, reports and refinement evidence

**Files:** Add a shared `taxonomy-app/src/main/resources/static/js/core/taxonomy-requirement-evidence.js` renderer and its stylesheet rules; modify main analysis result/session and saved requirement-detail rendering/templates and i18n labels. Modify the existing decision/snapshot report DTO/build/render owners and reformulation prompt builders to preserve assessments. Add existing Maven-owned JS/JUnit/Word rendering regressions.

**Interfaces:** Consumes the canonical `nodeAssessments` property from Task 1. Render status by enums, never numeric thresholds. Shared renderer exposes readable original quotes and questions and labels AI proposals separately from review. Relevant JSON/HTML/DOCX exports retain the same statuses and wording. New metadata remains optional for old inputs.

- [x] Write and run failing UI tests for indirect green, direct amber, red conflict, distinct grey states, hostile text, missing metadata and narrow layout-safe content.
- [x] Implement one reusable status/details renderer, show requirement aspects and per-node assessments in active/saved analysis, and preserve streaming/reset/history boundaries.
- [x] Add report and reformulation-context tests; implement evidence sections and source-bound interpretations without changing original text or adoption policy.
- [x] Run relevant Maven-owned browser/JavaScript and report tests, then commit and obtain a scoped task review.

### Task 3: Independent public reference, live walkthrough and observed fixes

**Files:** Add a bounded source-backed case under `taxonomy-app/src/test/resources/scenarios/`, independent evaluator tests, an opt-in live test using existing `real-llm` conventions, reusable loopback completion relay tooling if no maintained equivalent exists, and `docs/qa/2026-11-27-requirement-evidence-session.md`. Add snapshots/screenshots only for this public test case; keep large raw runs under target and deliver a compact evidence report.

**Interfaces:** Uses ordinary application authentication, analysis, project requirement versions, clarification/adoption, snapshot and export boundaries. Uses the authoritative catalogue without rewriting definitions or filtering away adversarial candidates. If a bounded catalogue is necessary, retain full original ancestor paths, relevant alternatives and state the scope precisely.

- [x] Freeze the Altscherbitz notice, three cumulative requirements and authored mapping oracle in `taxonomy-app/src/test/resources/scenarios/altscherbitz-backup-reference-v1.json` before model inference.
- [x] Test that invented timing, retention or recovery targets, incorrect site or component delivery, unconditional optimization, and erased uncertainty fail the evaluator.
- [x] Start a reusable vLLM-compatible live endpoint and the real application. If assistant-generated, answer actual queued requests afresh, in an isolated responder without the QA oracle; record exact provenance. No mock/replay mode.
- [x] Conduct a three-stage guided UI/API session in one project using new requirement versions. Use the original catalogue with `TAXONOMIES_ONLY` scope for Business Processes, Core Services and User Applications. Request analysis-guided clarification, answer available questions from the source, preview and explicitly adopt reviewed text, then reanalyse. Preserve remaining questions and record gaps in the actual interaction.
- [ ] Inspect screenshots and actual report output. Reproduce and fix observed weaknesses; run focused regressions and relevant broader Maven checks.
- [ ] Commit reference, test/tooling and walkthrough evidence. Run independent whole-branch review, fix important findings, publish a reviewable PR and report actual verification and remaining deployment/CI boundaries.
