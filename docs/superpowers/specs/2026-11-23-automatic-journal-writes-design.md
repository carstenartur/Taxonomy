# Eliminate a redundant fresh-question write

**Outcome: not promoted.** The fixed twelve-run matrix left the bounded-workload regression risk unresolved. The candidate and its four source/test changes were reverted byte-for-byte to the original baseline. The validated workload harness and complete experiment evidence remain. See [the final QA record](../../qa/2026-11-23-automatic-catalogue-performance.md). The original acceptance criteria below are retained.

## Purpose and evidence

The normal automatic baseline performs 3,123 physical authored exchanges, 9,374 JDBC batches and 40,921 commits in about 65 seconds. Its JFR shows both Hibernate EntityInsertAction and EntityUpdateAction reaching HSQL's new-CLOB writer from `prepareQuestion`'s owner flush. The source persists a newly constructed READY question before assigning ATTEMPT, attempt metadata and encoded prompt. This suggests one inserted row is immediately rewritten before its first commit. Exact same-question SQL evidence is required before accepting that mechanism.

The baseline harness is in the preceding automatic-catalogue-workload stage. Its retained JFR and independently reviewed measurements inform this follow-up; final evidence belongs in `docs/qa/2026-11-23-automatic-catalogue-performance.md`. Counts and samples are not a performance guarantee. Extra historical catalogue reads, speculative indexes, broad dirty-column update changes and concurrent provider protocols are not part of this candidate.

## Intended behavior

When a question is genuinely absent, construct it as today, perform the same admission, limits and payload preparation, and register it for persistence after its final state for that transaction is known. Successful local prepare must insert that final ATTEMPT row once, without a question UPDATE in the same transaction. The run-owner projection, locking, explicit flush, fenced owner DML and commit remain exactly where they provide durability. Before any provider send, a separate transaction must observe the fully committed ATTEMPT. The later budget debit and accepted-reply transaction remain separate.

The new question must also be persisted on the existing early SKIPPED path. Fresh input-limit failure must durably insert its same FAILED metadata and atomically pause the run. Existing READY/SUPERSEDED retry rows remain managed and follow their current update path; accepted replay and compatibility rules remain unchanged. `prepareDistributed` still assigns and flushes delivery ownership after the helper returns; its legitimate ownership UPDATE is not suppressed. A transaction that loses its owner fence still rolls back the entire question write.

Only newly constructed rows change registration timing. Existing row/LOB reads, validation, upgrades, unknown outcomes, namespace bridges, scalar supersession checks, all codec bytes and representation choices, question/run budgets, cancellation/lease checks, and full historical-row updates are unchanged. No schema migration or new toggle is needed.

## Acceptance

1. Retain real baseline SQL proving INSERT plus UPDATE of the same fresh question within one prepare transaction. If that does not occur, stop this candidate and report the actual mechanism.
2. After the change, real fresh local prepare has one INSERT and zero question UPDATEs; full committed columns and the run's version/state/payload count match control after excluding only already justified volatile time/token fields. Independent transaction reads prove ATTEMPT and accepted detail durability. Failure/SKIPPED paths, distributed ownership, rollback, retry and ambiguity remain intact.
3. Use the final corrected automatic harness identically for baseline and candidate, with unchanged closed seed, effective settings, heap/CPU/driver and prompt/response order. A first paired screen per default/bounded configuration gates further expensive comparison. If positive, collect at least three alternating fresh pairs per configuration and preserve every result. Each same-mode pair must have identical ordered prompt AND response digests, complete normalized persisted semantics (including operation identity), authored coverage, budgets, claim/model bindings and source identities.
4. Record whole worker wall and allocations, SQL/batch/commit counts, fresh preparations and accepted replay preparation counts. Do not infer rows written from batching calls alone or add nested timers. No throughput or live inference claim follows from the intercepted provider fixture.
5. Promote only a demonstrated useful candidate with independent task and whole-branch review, current hosted gates and an authorized expected-head merge. A nonbeneficial candidate is discarded with its evidence retained.

## Remaining hypotheses

The existing compatibility-index draft remains available if actual query plans and end-to-end data justify it. The pending-supersession projection already selects scalar metadata but includes node-layout CLOBs; a later experiment needs actual plans and result-consumption evidence. Broad `@DynamicUpdate` can change how externally modified historical columns survive a stale flush and is explicitly a separate candidate. The current default profile does not justify expanding catalogue or progress code before this journal write experiment.
