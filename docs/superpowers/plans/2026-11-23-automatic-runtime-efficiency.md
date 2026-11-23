# Automatic Runtime Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Reduce repeated durable bookkeeping and history/aggregate hydration without changing model prompts or application results.
**Architecture:** Reuse fresh scalar facts obtained under the existing row lock; fetch operation-scoped job rows; validate distributed results through the authoritative scheduler without constructing a report that the persistence path discards.
**Tech Stack:** Java 25, Spring transactions, Hibernate/HSQL, existing PostgreSQL/Oracle/SQL Server dialects.
**Spec:** `docs/superpowers/specs/2026-11-23-automatic-performance-program-design.md`

## Global Constraints

- Java 25; preserve compiler warnings and all existing test/quality gates.
- Preserve current project/tenant/workspace/provider/catalogue/claim scoping, lease timing and fresh cancellation/PAUSED behavior.
- ATTEMPT and physical budget debit commit before HTTP; accepted answer commits after HTTP; never keep a DB transaction across HTTP.
- Retain current legacy data, replay, storage limits, scarce-budget order and corruption failures.
- This stage introduces no schema migration, model prompt change, new fixed concurrency limit or external storage format.
- Do not run competing Maven processes. Use focused regression groups; all hosted checks gate publication/merge.
- Only the controller dispatches reviews or additional agents.

## Review Focus

- Storage quota rejection must pause before a successful answer can be surfaced (Task 1: real compression/quota regression).
- Claim expiry while waiting for a row lock and simultaneous last-credit consumption must remain fenced (Task 1: existing real ledger concurrency/expiry matrix plus initialized read-count assertion).
- Invalid operations still enforce project authorization; contradictory passes and foreign tenant keys stay isolated (Task 2: actual HSQL status rows, padding compatibility and consistency tests).
- A new cluster result with an altered grant or evidence must roll back without releasing credits, including a stored in-bounds allowance changed before execution (Task 3: actual worker persistence and negative grant/evidence tests).
- Out-of-order successful deliveries must produce identical final trace/order and budgets; untrusted imported plans still receive complete validation (Task 3: coordinator/distribution matrix).

## Task 1: Remove duplicate checkpoint and initialized-ledger reads

**Files:**
- Modify: `taxonomy-analysis/src/main/java/com/taxonomy/analysis/recovery/AnalysisContinuationStore.java`
- Modify: `taxonomy-analysis/src/main/java/com/taxonomy/analysis/recovery/AnalysisContinuationService.java`
- Test: `taxonomy-analysis/src/test/java/com/taxonomy/analysis/recovery/RelationCallLedgerTest.java`
- Test: existing `RecoveryCheckpointCompressionTest`, `AnalysisCheckpointDelegationTest` and real local checkpoint/service fixture as necessary.

**Interfaces:**
- Consume existing `Claim`, locked `CheckpointOwner`, `AnalysisCheckpointSession.Store` and accepted-answer behavior.
- Produce `public enum CheckpointCompletion { ACCEPTED, STORAGE_LIMIT }` in `AnalysisContinuationStore`; `finish(Claim, Question, String, LlmCallDetail)` returns this enum.
- Extend the private locked owner projection with nullable `Integer relationCallsSpent`; pass that freshly locked scalar into legacy-aware initialization.
- Keep public checkpoint Store SPI and fresh post-finish `checkActive()` unchanged.

- [x] **Step 1: Pin the current real execution path and add RED performance assertions.** In the existing HSQL ledger fixture isolate an initialized `beforeRelationCall`: assert the current implementation makes one redundant standalone SELECT of `relationCallsSpent`, then write the desired assertion of zero such standalone SELECTs, one locked owner read and one fenced debit. Add a service-level successful finish regression showing only the caller's fresh post-commit state query remains; retain failure on actual quota rejection and fresh PAUSED/CANCELLED changes.
- [x] **Step 2: Run the new regression against the unchanged production source and retain the expected count failure.** Run Java 25 through the existing Maven proxy with explicit Mockito agent if needed: `-B -ntp -pl taxonomy-analysis -am test -Dtest=RelationCallLedgerTest,RecoveryCheckpointCompressionTest,AnalysisCheckpointDelegationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false`. A missing fixture/class is not RED; correct the selector first.
- [x] **Step 3: Implement the narrow result and scalar reuse.** `finish` returns STORAGE_LIMIT exactly on its existing payload-quota rejection branch and ACCEPTED on its existing successful write path. The local adapter uses that value for the existing immediate AWAITING_DECISION failure of attempted SUCCESS, removing its own redundant `state` query. The caller's fresh post-finish check continues to observe concurrent state. Update actual non-void-compatible test stubs/call sites without changing assertions. Include nullable ledger value in the existing locked projection and use it in both `relationCallsSpent` and `beforeRelationCall`; only null uses the current legacy payload/bootstrap. Preserve the now-after-lock lease checks, conditional update and negative-value rejection. Do not remove other cooperative checkpoints.
- [x] **Step 4: Run focused GREEN and related existing authority/partial-result regression classes once.** Require zero failures/errors/skips in selected cases, unchanged quota/cancellation outcome, and actual statement counts. Do not weaken mocks or replace real persistence evidence with verification of method calls alone.
- [x] **Step 5: Self-review, commit only Task 1 source/tests and write the report.** Include baseline/candidate SQL counts, exact test command/result, RED evidence, behavior boundaries and commit IDs.

## Task 2: Bound Copilot operation status materialization

**Files:**
- Modify: `taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/CopilotOperationKey.java`
- Modify: `taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/CopilotAutomationService.java`
- Modify: `taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/ProjectRequirementAnalysisService.java`
- Modify: `taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/PortfolioAnalysisPersistenceService.java` (existing scoped transaction and shared grouping owner behind the facade)
- Modify: `taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/repository/RequirementAnalysisJobRepository.java`
- Test: existing `CopilotOperationKeyTest`, `PortfolioStatusPayloadReadTest`, `CopilotOperationConsistencyTest`, `CopilotAutomationCancellationTest`, and operation-directed mocks in `CopilotAutomationServiceCoverageTest`. Real HSQL fixtures cover branch/project isolation locally; the existing app branch-isolation suite remains a hosted gate.

**Interfaces:**
- Add `public static List<String> candidatesForOperation(String operationId)` using the existing encoder, both modes, every valid 1..3 pass/total pair, the three accepted profiles and both booleans. Valid operations produce exactly 144 unique canonical keys; invalid IDs return an empty list only after callers have enforced scope.
- Add an operation-specific analysis-service read method returning the same job view type as `listJobs`; it must enforce `requireScopedProject` before treating an invalid operation as absent.
- Repository canonical query binds keys and exact project/scope through the existing unique index, returning only scalar IDs/keys. Strict Java parsing and exact operation matching precede one scoped hydration of all items of selected jobs with existing admitted-version joins. This prevents permissive database collations from hydrating unrelated rows and adds one scalar query to the ordinary path.
- Selected IDs above 256 use bounded hydration batches, restoring newest-job ordering and retaining every matching row; normal operations keep one hydration query. This preserves behavior for large imported histories within database bind limits.
- Compatibility for directly stored padded but parseable keys: retrieve only scalar job ID/key candidates containing the literal operation ID, strict-parse and exact-match them, and hydrate only matching additional IDs. No unescaped LIKE, no full unrelated job/item DTO hydration. Normalization/write format is unchanged.

- [x] **Step 1: Add RED in real HSQL.** Seed substantial unrelated job/item history, both modes/profiles/flags/passes for a selected operation, the same key in a foreign scope and leading/trailing Java-strip whitespace on a legacy key. Verify identical returned operation/contradiction behavior while asserting unrelated job/item rows are not materialized. Invalid ID must not bypass missing/forbidden-project checks.
- [x] **Step 2: Run focused RED with `-pl taxonomy-portfolio -am -Dtest=CopilotOperationKeyTest,PortfolioStatusPayloadReadTest,CopilotOperationConsistencyTest,CopilotAutomationCancellationTest` and standard Surefire/proxy options.
- [x] **Step 3: Implement the typed key-candidate helper and scoped operation lookup.** Reuse existing row grouping and conversion, strict key parsing and consistency checks. Retain all matching job items; do not turn duplicate/conflicting passes into silent omission. The compatibility query returns scalar candidates only; unrelated history never becomes JobView/ItemView objects.
- [x] **Step 4: Verify focused GREEN and existing branch-isolation/status tests.** Report returned row/materialization counts for a short and long unrelated history; distinguish any compatibility key scan from bounded DTO rows.
- [x] **Step 5: Self-review, commit and report.** No other status endpoint or schema optimization in this task.

## Task 3: Validate distributed results without constructing a discarded report

**Files:**
- Modify: `taxonomy-analysis/src/main/java/com/taxonomy/analysis/relations/RelationSearchDistribution.java`
- Modify: `taxonomy-analysis/src/main/java/com/taxonomy/analysis/cluster/ClusterAnalysisStore.java`
- Test: existing `DistributedRelationSearchTest`, `ClusterRelationWorkerPersistenceTest`, `ClusterRelationCoordinatorTest`.

**Interfaces:**
- Add `public static void validateResults(Plan plan, List<WorkResult> persisted)` using the existing authoritative `schedule` validation.
- Share the inexpensive report-admission checks that previously prevented completion writes: source progress fields, the existing short-circuit exhausted-frontier reason inspection and representable cumulative actual charges. Preserve inline and historical acceptance behavior; leave `ready` and DTO contracts intact.
- Recompute exact grants and validate all persisted results using the original scheduler and shared evidence validators. A persisted Work row alone is not independent proof of the originally issued allowance: changing that row and returning a matching result must still fail.
- Storage paths for success and failure use this validator instead of constructing and discarding a complete report. This first stage deliberately still reads prior result JSONs; do not claim zero history reads or elimination of all quadratic scheduling work.
- Keep `ready`, full `combine`, backup/import and final reconstruction semantics unchanged; reuse the same scheduler validation rather than duplicating its logic.

- [x] **Step 1: Pin semantic regressions and add the new validation API tests.** Require identical acceptance/rejection to `combine` for valid reverse-order results, foreign grants, changed ordinal/root, over-budget counters and incompatible evidence. Extend actual worker persistence with an in-bounds stored grant alteration (for example issued allowance 3 changed to 2) and matching success/failure result; both must roll back. The new API initially does not exist, while the baseline scheduler fixtures must pass. Avoid tests that merely spy on internal constructor/method calls.
- [x] **Step 2: Run focused RED with `-pl taxonomy-analysis -am -Dtest=DistributedRelationSearchTest,ClusterRelationWorkerPersistenceTest,ClusterRelationCoordinatorTest`.
- [x] **Step 3: Expose shared scheduler/evidence validation and update the two persistence paths.** Preserve full independent schedule validation before every write, including when a matching stored grant was altered. Do not loosen import/global wave/budget checks. Only report/trace/progress assembly performed after validation in `combine` is omitted on the write path.
- [x] **Step 4: Run focused GREEN for reversed completions, rollback, duplicate delivery, scarce budgets and full final trace equality.** Measure allocation/time of validation versus the prior complete-report call on identical representative plan/results, separately from correctness tests. Report remaining prior-result reads and scheduler work honestly; do not add flaky elapsed-time assertions.
- [x] **Step 5: Self-review, commit and report.**

## Task 4: Review, document, publish and merge stage 1

- [x] Consolidate targeted test/SQL evidence and changed behavior into `docs/qa/2026-11-23-automatic-runtime-efficiency.md`.
- [ ] Run the independent whole-branch review, resolve real findings, verify the final source tree and publish a focused PR.
- [ ] Let all exact-head hosted gates finish, including full scenario and external databases where selected.
- [ ] Expected-head squash merge under existing authorization, independently verify main and preserve the work ledger.
- [ ] Continue the remaining programme stages recorded in the programme ledger; stage 1 completion is not the whole user request.

