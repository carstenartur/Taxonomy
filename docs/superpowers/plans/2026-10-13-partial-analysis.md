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

Files: `taxonomy-analysis-recovery.js`, `taxonomy-scoring.js`, recovery CSS, `.github/scripts/analysis-partial-results.test.mjs`, `.github/scripts/analysis-readiness.test.mjs`, existing npm recovery task.

- [x] Show routine partial completion without an automatic modal; display names, IDs and actual clarification text.
- [x] Keep selected retry/skip explicit; paginate multi-selection and reject stale asynchronous UI updates.
- [x] Prevent global completeness/absence claims from partial evidence.
- [x] Retain the prior view's existing DOM and scroll position during the same operation's retry.
- [x] Test against the actual integrated scoring module: retain accepted evidence for the same operation and reset it for a newly allocated Copilot operation. The original partial-analysis implementation left scoring unchanged; the subsequent navigation integration intentionally adds the `newOperation` reset distinction. The erroneous intermediate scoring upload remains excluded.

## Verified integration checkpoint (2026-10-14)

Head `a159508c935a4c1ad710e7daef1018336bec62cf`, tree `905a3af044cd81769ac82243a42eab3bcf140100`, combines the partial-analysis work with the navigation and analysis-state repairs from #1223. Its base remains `9e518bed083db86c4098825b6cd3cbe16e3c00bb`.

In this integrated source, `taxonomy-scoring.js` intentionally performs the complete evidence reset when `options.newOperation === true`. The recovery controller supplies that flag only when allocating a new Copilot operation. Repeated START requests and retries for the same operation retain accepted results. Scoring owns relation-report publication; recovery hydration preserves a missing report within the same evidence scope and passes explicit clears or legacy nested reports through the same central report helper. These paths are covered by the actual scoring/recovery modules in the readiness and partial-result tests.

[Canonical CI 38478344828, attempt 2](https://github.com/carstenartur/Taxonomy/actions/runs/38478344828/attempts/2) passed the complete Core reactor, all six authenticated browser shards, UI contracts and final Maven verification. The independently verified Core artifact `11871089193` has SHA-256 `7052e36a7fd6caf8b45dc4c7aed3d56ae525367fed2f2cf1c81d2d84b4078fce`, build ID `38478344828.2.core`, and CI merge `b5d390703d3c82caaf9f022c582af824034daaf1` with the same source tree. Its JUnit reports contain 9,177 cases, zero failures/errors/flakes and 80 existing profile skips. All eight execution-failure checkpoint cases, 51 partial-resilience cases and four continuation UI cases passed without skips. [Database compatibility 38478344829](https://github.com/carstenartur/Taxonomy/actions/runs/38478344829) also passed. Local supporting checks passed 77 focused Java tests and 1,182 combined UI contract tests.

These results apply to the named checkpoint. Any subsequent review correction requires fresh canonical verification and resolved review findings on its resulting head before the protected Main merge. The PR records the final head and CI evidence; this checkpoint is not a deployment or live-incident reproduction claim.

## Historical verification of the initial partial-analysis implementation

The pinned base is `9e518bed083db86c4098825b6cd3cbe16e3c00bb`, tree `cfb5795e03b0270c7bc6139b3441b86ed0899cf6`.

At that initial checkpoint, compilation covered all 14 changed/new Java production sources against the matching, SHA-256-verified application/feature artifacts. The five standalone check owners passed 51 methods: 14 journal/storage/protocol/archive, 14 architecture/use-case, 8 distribution, 8 cluster-failure persistence and 7 cluster-completion checks. `QuestionRecoveryProbe.verify()` also passed. These were executed checks, not a full Maven/JUnit run. The normal JUnit adapter discovered all five owners.

The initial Node 22 run passed 26 partial-result cases. Four loaded the actual scoring module: German/English view retention, workspace replacement and fresh-requirement isolation. Before the retention correction, the two pending-view assertions failed. Before the no-continuation projection correction, five of the seven cluster-completion checks failed. The corrected initial source passed both suites.

At that initial checkpoint, `taxonomy-scoring.js` was byte-identical to the base. Presentation retention belonged to the recovery controller, where operation identity was available before scoring mutated state. An incorrect unreferenced intermediate scoring blob was excluded. This byte-identity statement does not apply to the later integrated source described above.

### Acceptance that was outstanding at the initial checkpoint

- Run the normal complete Maven/JUnit reactor, including the adjusted existing question/relation contracts.
- Run the complete Node 24 UI contracts and actual authenticated browser shards.
- Complete database compatibility and real Artemis worker/broker interruption acceptance.
- Obtain independent code review and resolve findings before merge.

That initial checkout was reconstructed from pinned source/runtime artifacts, not a full clone. Maven/test dependencies and the full UI checkout were unavailable in that environment. Earlier browser/startup evidence was historical, not a fresh end-to-end verification of that revision. The author had performed a separate self-review; that initial report did not claim independent review or deployment. Later integration evidence is recorded separately above.
