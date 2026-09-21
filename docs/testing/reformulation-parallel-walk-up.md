# Bounded parallel walk-up

Package 5 now supports `reformulation.synthesis.parallelism=1..8` (environment
`REFORMULATION_SYNTHESIS_PARALLELISM`). The default remains **1**: operators can
select **2** or more without changing provider rate limits or request budgets.
Existing runs do not silently gain a larger request burst after an upgrade.

Only independent Phase-A tasks run concurrently. Every parent waits for all of
its actual child tasks. Inputs are captured on the run thread, including its
provider override, immutable original and child results. Child threads set and
clear that captured provider; they never select a provider from another request.
The caller's transaction guard runs before dispatch, so an open transaction cannot
be hidden by moving the provider request to a new thread.

Each invocation owns a separate fixed-size child executor; it never submits
children to the same portfolio pool whose parent is waiting. At most the selected
number of tasks are submitted at a time. The bound is **per run**, not a global
provider limit. Existing provider throttling and final-prompt budgets still apply.
Whole children and the existing bounded groups retain their checkpoint semantics.
Groups within a node, affected human-decision rewording and Phase-B reconciliation
remain serial in this slice.

Only the caller assembles the final document, in the existing deterministic
walk-up order. Completion order does not reorder statements, questions or sections.
On failure, no dependent parent starts. Already running siblings are joined before
the failure returns, allowing their valid checkpoints to finish; no complete
proposal is published from an incomplete plan. This does not cancel or refund an
already in-flight provider request. Existing cancellation and lease fencing remain
the authority for checkpoint writes and final publication.

No original text, active requirement version, user answer or architecture is
changed by scheduling. Parallelism changes scheduling, not the prompt's semantic
input; it is not a source of human approval or a reason to regenerate stable IDs.

## Executable checks

`ParallelReformulationTest` executes six checks. The main check uses the real
engine, existing HTTP gateway, budget boundary and strict parser. Only loopback
HTTP responses are authored. The two sibling replies are synchronized by latches,
with the second child deliberately completed first. The parallel document must be
identical to the serial document, including full original, statements, questions,
provenance and IDs. Exactly four model calls occur in either run; parallel maximum
is two. The default provider is LOCAL_ONNX, so a lost provider override cannot
accidentally call a cloud endpoint.

The additional task-level checks cover the ready-queue bound, joined parents,
failure with an in-flight sibling, invalid graphs/limits, provider-context cleanup
and the caller transaction guard. Task IDs in these scheduling checks are graph
fixture identifiers, not claimed catalogue codes.

`parallel-catalogue.json` projects code, English name, parent and child fields from
six actual nodes returned by `TaxonomyService.getFullTree()` using the bundled
`C3_Taxonomy_Catalogue_25AUG2025.xlsx` in the CI application tree
`6b46a502ec435666c216edc89a513ed66a1155e4`. It is a topology fixture, not an assertion
that these nodes are the correct architecture for time recording. No fabricated
catalogue entries or live-model quality claims are involved.

`ReformulationStorageBoundaryTest` adds three checks for the exact remaining
coverage-gate failures on `2f72ff8`: malformed checkpoint identity, null/blank and
oversized UTF-8 results rejected before persistence, and immutable first
cancellation actor/time even on repeated entity operations. It changes neither
production validation nor any coverage threshold. Existing database/scope tests
remain required; these pure boundary checks do not replace them.

## Verification recorded in this change

- Before the scheduler change the real-engine check failed with
  `Independent subtrees never overlap`.
- Fresh Java 21 compilation against CI runtime tree
  `6b46a502ec435666c216edc89a513ed66a1155e4` passed (deprecated-API notes remain).
- All six parallel checks passed on three consecutive executions. The three
  storage checks passed against the same runtime. JUnit wrappers call these exact
  check methods, but the normal Maven/JUnit reactor was not available locally.
- Author self-review only; independent review and all exact-head CI gates remain
  required. The canonical `./mvnw verify -DexcludedGroups='real-llm'` could not start
  in the isolated source subset because the complete Maven checkout/dependencies
  were unavailable. The standalone checks are not reported as a full reactor run.

Parallel checkpoint/lease behavior under multi-process contention and the complete
civil source-to-architecture acceptance are not established by the loopback unit
fixture. Existing process-recovery and database suites remain CI requirements.
Detailed cost/partial-result UI, large cross-taxonomy review inputs and packages
6–8 remain unfinished. Separate adoption is still mandatory.
