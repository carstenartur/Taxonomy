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

Evidence: not run yet. This commit is tests-first WIP, not acceptance evidence.
Next: after root confirms exact-tree remote durability, execute:

```sh
python .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ScenarioLlmPlaybackTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expect the new semantic dispatch and duplicate-scope contracts to fail before
implementation. Then implement bounded playback support, checkpoint before GREEN,
and stop for independent review. Real application/browser/restart, fixture score
selection, exports, CI and real-provider quality gates remain open (Task 4b+).
