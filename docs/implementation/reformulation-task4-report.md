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

Next: after root confirms exact-tree remote durability, execute focused GREEN:

```sh
python .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ScenarioLlmPlaybackTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Then inspect positive class/count evidence and stop for independent review.
Real application/browser/restart, fixture score
selection, exports, CI and real-provider quality gates remain open (Task 4b+).
