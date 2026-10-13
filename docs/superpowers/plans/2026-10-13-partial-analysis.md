# Resilient Partial Analysis Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for remaining acceptance work. Preserve the completed implementation and its tests.

**Goal:** Keep useful architecture results while independent assessments survive individual model failures.

**Architecture:** Extend the existing durable question state machine rather than adding another executor. Keep answer validation separate from execution authority. Share bounded local projection between in-process and distributed result publication.

**Tech Stack:** Java 21, Spring/JPA, HSQLDB, existing Artemis contracts, native browser JavaScript.

**Spec:** `docs/superpowers/specs/2026-10-13-partial-analysis-design.md`

## Global Constraints

No implicit provider retries; no invented scores, negative findings or edges. Preserve immutable scope, question identity, revision and delivery fencing. No provider I/O inside database transactions. No new workflow, dependency, migration, polling loop or relaxed test threshold. Coordinators and workers must use the same question-state contract; mixed-version deployment is not verified.

## Review Focus

Mixed valid/unresolved relation decisions, uncertain physical calls, storage failure after a response, stale multi-question decisions, duplicate distributed completion, long containment cycles and retaining the visible architecture only within the original operation.

## Task 1: Independent assessments and selective recovery

Files: `AnalysisCheckpointSession`, `AnalysisContinuationStore`, `AnalysisContinuationBackupRestorer`, `AnalysisRequest`, `PartialAnalysisResilienceChecks`, `QuestionRecoveryProbe`.

- [x] Retain settled invalid answers as UNASSESSED, and valid question-bearing relation answers as CLARIFICATION.
- [x] Continue independent work without releasing failed scores or silently repeating provider requests.
- [x] Keep FAILED/PAUSED for uncertain execution or storage; preserve cancellation and stale-owner rejection.
- [x] Validate all selected retry/skip keys and the exact revision before mutating any checkpoint.
- [x] Verify replay, rollback, explicit rework, cancellation and inert restored archives using real HSQLDB transactions.

## Task 2: Partial architecture and distributed completion

Files: `AnalyzeRequirementUseCase`, `PartialArchitectureProjection`, `FrozenClusterAnalysisFinalizer`, `ClusterAnalysisStore`, relation distribution/protocol, corresponding executable checks and `RelationRecoveryTransitionTest`.

- [x] Project accepted nodes even when relation work or detailed rendering is incomplete.
- [x] Preserve verified evidence without inventing relationships or admitting extra provider work.
- [x] Retain actual execution-stop reasons and conservative grant charges; only ordinary assessment failures permit independent admission.
- [x] Publish a bounded partial view on cooperative stops even without an optional continuation journal.
- [x] Verify node limits, ambiguous identities, long cycles, empty evidence, duplicate completion and absent-view finalization.

## Task 3: Recovery interface

Files: `taxonomy-analysis-recovery.js`, recovery CSS, `.github/scripts/analysis-partial-results.test.mjs`, existing npm recovery task.

- [x] Show routine partial completion without an automatic modal; display names, IDs and actual clarification text.
- [x] Keep selected retry/skip explicit; paginate multi-selection and reject stale asynchronous UI updates.
- [x] Prevent global completeness/absence claims from partial evidence.
- [x] Retain the prior view's existing DOM and scroll position during the same operation's retry.
- [x] Test against the actual unchanged scoring module; exclude the erroneous intermediate scoring upload entirely.

## Current verification

The pinned base is `9e518bed083db86c4098825b6cd3cbe16e3c00bb`, tree `cfb5795e03b0270c7bc6139b3441b86ed0899cf6`.

Fresh compilation covers all 14 changed/new Java production sources against the matching, SHA-256-verified application/feature artifacts. The five standalone check owners pass 51 methods: 14 journal/storage/protocol/archive, 14 architecture/use-case, 8 distribution, 8 cluster-failure persistence and 7 cluster-completion checks. `QuestionRecoveryProbe.verify()` also passes. These are executed checks, not a full Maven/JUnit run. The normal JUnit adapter discovers all five owners.

The current Node 22 run passes 26 partial-result cases. Four load the actual scoring module: German/English view retention, workspace replacement and fresh-requirement isolation. Before the retention correction, the two pending-view assertions failed. Before the no-continuation projection correction, five of the seven cluster-completion checks failed. The final source passes both suites.

`taxonomy-scoring.js` is byte-identical to the base. Presentation retention belongs to the recovery controller, where operation identity is available before scoring mutates state. An incorrect unreferenced intermediate scoring blob is not part of this change.

## Remaining acceptance

- [ ] Run the normal complete Maven/JUnit reactor, including the adjusted existing question/relation contracts.
- [ ] Run the complete Node 24 UI contracts and actual authenticated browser shards.
- [ ] Complete database compatibility and real Artemis worker/broker interruption acceptance.
- [ ] Obtain independent code review and resolve findings before merge.

The current source checkout is reconstructed from pinned source/runtime artifacts, not a full clone. Maven/test dependencies and the full UI checkout were unavailable locally. Earlier browser/startup evidence is historical, not a fresh end-to-end verification of this revision. The author performed a separate self-review; no independent review or deployment is claimed.
