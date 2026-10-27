# Root relevance implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep root irrelevance, supported relevance and unresolved requirements distinct.

**Architecture:** Add typed root evidence to the existing scoring boundary and
reuse CLARIFICATION journal states and UNKNOWN coverage. Preserve legacy numeric
replies and root-independent relationship target discovery.

**Tech Stack:** Java 25, Maven Wrapper, existing Jackson, JUnit and browser tooling.

**Spec:** `docs/superpowers/specs/2026-10-27-root-relevance-design.md`

## Global Constraints

- No new dependencies, workflow, database schema, queue or blanket retries.
- No fabricated catalogue identities or forced CO minimum.
- Existing scores and accepted evidence remain valid partial results.
- Authored replies are protocol fixtures, not live-model quality evidence.

## Review Focus

- A valid UNRESOLVED response must not become FAILED, SUCCESS or numeric zero on replay.
- A field/status contradiction must not enter numeric scores or persisted accepted evidence.
- Missing root values must remain visible at taxonomy containers, not only leaf nodes.
- An already available service must remain eligible when the requested function needs it.
- Old prompt answers must not silently bypass the changed semantic policy.

## Task 1: Prompt and typed scoring boundary

**Files:** domain `RootAssessment`/`LlmCallDetail`; analysis `LlmResponseParser`,
`LlmService`, `PromptTemplateService`, `prompts/root-relevance.txt`; their tests.

**Interfaces:** `RootAssessment(Status status, Double score, String reason,
String question)` with RELEVANT/IRRELEVANT/UNRESOLVED and `unresolved()`;
`LlmCallDetail.getRootAssessments()/setRootAssessments(Map<String,RootAssessment>)`.
`ScoreParseResult` adds the same map while retaining old constructors.
Valid unresolved details carry `ROOT_UNRESOLVED:` and no numeric entry.

- [x] Add failing parser and root-flow cases for unresolved without zero, retained reason/question, strict contradictions, positive decimals and legacy replies.
- [x] Run selected Maven tests and inspect the expected failures.
- [x] Implement the typed boundary and clarify prompts, including reused dependencies and open implementation choices.
- [x] Verify parser/root-flow tests and policy fingerprint separation.

## Task 2: Continuation, coverage and observations

**Files:** analysis recovery/cluster/progress components; domain `AnalysisCoverage`; their existing tests.

**Interfaces:** consume Task 1 root evidence; persist `CLARIFICATION`, derive
`UNKNOWN`/null plus `CLARIFICATION:` reason; retain root metadata in every copy.

- [x] Add failing replay, explicit retry, coverage and observation regressions using valid unresolved root evidence.
- [x] Verify failures, implement preservation across local/distributed paths and semantic clarification observations.
- [x] Run the selected recovery, coverage and cluster tests.

## Task 3: Visible roots and realistic authored demo

**Files:** existing taxonomy browse/recovery/progress scripts and i18n; demo scenario/reply model and its tests.

**Interfaces:** consume UNKNOWN coverage with CLARIFICATION reason and the
ROOT_UNRESOLVED diagnostic. No fake score is introduced for display.

- [x] Add failing UI cases for a root clarification with no 0% and unchanged valid sibling results.
- [x] Correct authored R0/R1 CO assumptions using actual catalogue nodes and preserve complete demo replies.
- [x] Verify UI and demo tests, then capture the actual application state where feasible.

## Completion

- [x] Run affected-module tests and broader Maven gates; record prerequisite limits precisely.
- [x] Obtain independent whole-change review and fix material findings.
- [ ] Publish one focused PR directly against main, with evidence and remaining limits.

Verification and capture details: [final evidence report](../../testing/ROOT_RELEVANCE_2026-10-27.md).
Publication is the remaining integration action when this plan is committed.
