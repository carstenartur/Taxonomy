# Independent Task 4a review

Reviewed 2026-09-27 at `f3cb56622dbac9722bff0c69bed69a91a7bb2f2c`,
diff `e89ebdf4..HEAD`, against `reformulation-task4.md` and the Task 4a
scope recorded in `reformulation-task4-report.md`.

**Verdict: approve the scoped Task 4a checkpoint. No blocking findings.**
This approves the initial remote-response protocol corpus and its playback
contracts. It does not approve complete application acceptance or model quality.

## Correctness assessment

- Dispatch uses semantic lookup rather than invocation position. NODE and
  RECONCILE use the production prompt prefixes and input markers. REWORD is
  identified from the two affected-section preservation contracts actually used
  by production; there is no invented wire task field.
- Matching includes the exact complete original text, with a separately checked
  SHA-256, node identity, child/section identity sets, complete parsed boundary
  edge values, and answer values/state/disposition/history. Object-key ordering
  does not affect lookup. Duplicate child/section identities, duplicate answer
  identities and duplicate fixture scopes are rejected rather than collapsed.
- The production node prompt builder and response parser are exercised directly
  for the initial leaf case. Source review also confirms the reconciliation and
  node repair suffix spellings agree with their production builders. The other
  contract cases construct protocol inputs; they are not evidence of an executed
  full application workflow.
- Unknown or malformed requests enter the shared failure ledger before being
  rethrown, including runtime parsing failures. All three coverage verification
  entry points check that ledger, so a caller catching the immediate exception
  cannot turn the covered run green. Repeated/ambiguous input markers fail closed.
- The new fixtures explicitly identify their requirements and responses as
  authored test assumptions. They contain the terminal/browser restriction,
  numerical constraints and unmapped-original assumptions without claiming
  external provenance. Their intentionally minimal response bodies are consistent
  with the report's initial-protocol-corpus scope.
- The flood fixture and relation playback implementation are unchanged. The
  surrounding playback change adds reformulation dispatch and strengthens fatal
  failure handling; the existing scoring/relation dispatch remains in place.

## Evidence and acceptance boundary

Inspected existing Surefire XML/text evidence for `ScenarioLlmPlaybackTest`:
11 tests, zero failures, zero errors and zero skips. The existing
`/workspace/scratch/38625e9262ff/task4a-green.log` records the same counts,
`BUILD SUCCESS`, and completion at `2026-09-27T19:04:18+02:00`
(17:04:18 UTC). This review did not rerun the already verified tests.
The class is explicitly selected in the root `civilian-acceptance` profile and
`.mvn/verification-suites.json`.

Task 4b must bind the corpus to actual selected graphs and production requests,
including statement/question preservation and authored scoring fronts, as the
implementation report already states. Bounded synthesis, application transitions,
browser behavior, persistence/restart, exports, CI and actual-provider quality
are not established by these 11 contract tests and remain open. Their absence is
not a Task 4a finding. No production changes, redundant tests, commits or
publication were made during this review.
