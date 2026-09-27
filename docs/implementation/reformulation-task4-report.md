# Task 4 report

## Task 4a — tests-first checkpoint, 2026-09-27

Only the remote-response corpus and contract tests are in scope. Existing flood
fixture and relation playback remain unchanged. Two authored fixtures explicitly
label test assumptions, including terminal-only/no-browser, numeric limits and
unmapped original content. Catalogue IDs reuse established flood bindings.

New contracts exercise NODE, RECONCILE and REWORD without invocation-order dispatch,
full exact source, child and boundary identities, answer values/state, caught
unknown calls and duplicate fixture scopes. REWORD has no separate wire task field:
it uses the production node prompt with affected-section preservation instructions.
Fixtures here are provider replies, not prepared application architectures.

RED evidence at published test tree `9fcc0ac5ae5fd9019c5001fee123b0a267e34794`:
10 tests, 1 failure, 1 error, no skips. New positive dispatch failed with
`Missing requirement`; duplicate reformulation scopes were silently accepted.
The existing relation contracts passed. Log: scratch `task4a-red.log`.

Implementation adds semantic reformulation dispatch, exact source/hash validation,
child/section identities, complete boundary-edge content and answer history/state.
Malformed calls are retained in the shared fatal ledger even if callers catch them;
all three coverage entry points inspect that ledger. REWORD is distinguished using
the actual affected-section preservation contracts. A real production prompt-builder
and response-parser contract covers the wire boundary. No production code changed.

GREEN evidence at published commit `c4bd1b51f9fcef991bcd12e124f664ed79bc245c`,
tree `97289619b4b34d0db6450987ed78b1006a8a95df`: `ScenarioLlmPlaybackTest`
11 tests, zero failures/errors/skips, BUILD SUCCESS, finished 2026-09-27
17:04:18 UTC (Maven printed 19:04:18 +02:00). Scratch log `task4a-green.log`.
Exact focused command used for both RED and GREEN:

```sh
python .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ScenarioLlmPlaybackTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The class is already explicitly selected by the `civilian-acceptance` profile
and `.mvn/verification-suites.json`; no competing selector was added.
Next step: independent Task 4a review of the published source tree and this report.
Implementation stops here until review. The fixture responses are initial protocol
contracts, not yet the complete application-path corpus: real selected graph IDs,
statement/question preservation and authored score fronts must be bound from actual
application requests in Task 4b without injecting prepared state.

Real application/browser/restart, fixture score
selection, exports, CI and real-provider quality gates remain open (Task 4b+).

## Resumed 2026-09-27

Task 4a accepted source recovered from 696130c8; full approved PR1135 corrections
through 43992e1d merged without discarding earlier Task4 work. The original exact
unapplied Task4b test draft is now applied with AnalysisStatus.SUCCESS corrected
from its unverified COMPLETED assumption. Test-only checkpoint precedes execution.
Expected RED: the sourced flood remote corpus has no application reformulation
responses; observed actual prompt identities will bind authored response cases.
No application memory/provider budgets or source requirements are changed.

### Task 4b application corpus checkpoint

Initial HTTP acceptance RED: normal analysis returned PARTIAL because the original
eight-front flood corpus exhausted the unchanged 24-call relation-search budget
(37 unfinished batches). A separate authored BP/IP score front now runs actual
analysis to SUCCESS under the defaults; the sourced flood text/bindings remain
unchanged. The original corpus remains the authority for the existing broad test.
Independent root-zero response contract: RED 12 tests / 1 error (`Budget mismatch:
BR`), then GREEN 12/12. Sibling budget validation remains enforced. The HTTP test
then reached the real NODE/BP-1060 provider request and failed on its missing reply.

New explicitly scoped BP/IP replies bind only runtime statement/question IDs from
validated input. They author shared and separate numeric decisions without inserting
application/DB/architecture state. Full prompts and fatal unmatched calls are saved
as test artifacts. Proposal execution is under test; no end-to-end pass claimed.
Next: finish the authenticated answer/reword/adoption/export/restart path, then
browser and separate real-provider comparison. Focused command:
`./mvnw -pl taxonomy-app -am test -Dtest=ScenarioLlmPlaybackTest,ReformulationCivilianAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false`.

### Lifecycle checkpoint

The HTTP driver now creates answers and an explicit deferral, edits a statement,
checks stale If-Match rejection, invokes targeted synthesis, compares the historical
revision and frozen architecture, previews/confirms/replays an adoption command,
downloads four formats, runs actual post-adoption analysis/new offer, checkpoints,
and starts a separate file-database application process for history/lineage checks.
Still under verification: no complete lifecycle success has been recorded yet.
Observed run lists are oldest-first; the driver now waits for all returned runs to
be terminal and asserts the newly returned run ID, never an older completed run.
No model result is injected into the application.

### Post-adoption regression and real-model runner

Actual HTTP evidence now reaches: initial SUCCESS analysis, completed eight-node
walk-up, merged/shared and distinct numeric questions, answer+deferral, completed
targeted rewording, protected manual variant (PARTIAL / MANUAL_DRAFT_PROTECTED with
a retained candidate and unchanged human revision), explicit adoption and duplicate
command replay, four exports, and successful actual reanalysis. The new offer after
adoption fails INPUT_TOO_LARGE_FOR_PROVIDER: inherited question discoveries repeat
the same long catalogue context, including retained origins. A focused lossless
round-trip/budget regression is added before changing prompt encoding. The 120000
character / 262144 byte / 30000 estimated-token default remains unchanged.

A separate `reformulation-real-llm` Maven profile and tagged comparison class now
cover five authored inputs with the same source, real catalogue front, provider and
model for walk-up and one complete prompt. They write actual transport attempts,
available usage, duration, validated outputs and a blank human-quality rubric.
Source/front assumptions are explicit. Missing credentials produce NOT_RUN, not
playback results. Provider environment presence was checked: no standard provider
keys/custom endpoint were configured. No real-provider or human quality pass claimed.

### Lossless inherited-context correction

Focused regression RED: 2 tests, 2 failures, no errors/skips. Node and reconciliation
prompts repeated all historical discovery contexts and exceeded the unchanged
production budget. Both builders now include historical questions and their origins
in the existing request-local discovery dictionary. Input encoding identity advances
to v5 so old step checkpoints cannot masquerade as this prompt encoding. No archive,
source, answer, rejection or unique context is discarded.
The new round-trip/default-budget regressions and existing adopted-lineage and
reconciliation context suites pass locally (BUILD SUCCESS, 2026-09-27 20:13 UTC).
Full authenticated lifecycle is being rerun against this correction.
