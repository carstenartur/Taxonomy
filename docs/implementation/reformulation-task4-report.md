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
