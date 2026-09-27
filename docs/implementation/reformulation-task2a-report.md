# Task 2a — baseline binding and positive-review guard

Status: implementation checkpoint, not yet accepted. Root owns integrated gate.

## TDD evidence to date

- Baseline API/database RED: `ReformulationIsolationTest#storedForeignBaselineNeverLeaksThroughScopedProposalRead`, 1 test, expected 409 but received 200 after swapping persisted baseline payload with another requirement's valid frozen payload.
- Baseline GREEN: same focused Maven command, 1 test, 0 failures, build success (35.960 s). The service now compares the decoded scope, source version and snapshot with the physical proposal and canonical request scope before exposing it.
- Positive-review API/database RED: `ReformulationPositiveReviewGuardTest`, 5 tests; 2 expected failures: current local conflict and imported current conflict both returned 200 instead of 409; 3 compatibility tests passed. Build failure due solely to those two assertions (35.240 s).
- Independent positive-state RED after first guard implementation: `ReformulationPositiveReviewGuardTest#blockingLocalEvidencePreventsEachPositiveRequirementStateWithoutReviewChange`, 3 parameterized cases; APPROVED passed, IMPLEMENTING and SATISFIED each returned 200 instead of 409 (38.020 s). Minimal gate extended to those workflow-positive states.
- Added separate CONFIRMED-only, DEFERRED, CONFLICT finding, same keys in different scope, and structurally invalid imported portable-evidence cases; covering run pending.
- Covering GREEN: `ReformulationPositiveReviewGuardTest,ReformulationIsolationTest`, 13 + 6 = 19 positive test cases, zero failures/errors/skips; build success (51.223 s). Imported STRUCTURAL_LOSS goes through checksum-valid portable DSL validation and real materialization, rather than an adoption path which correctly refuses structural loss.
- Extended the baseline regression to mutate all seven serialized identity coordinates independently; covering run pending. This is an additional boundary matrix on the already RED/GREEN baseline validation behavior, not a separately claimed RED cycle.
- Next: cover independent positive axes, blocking findings, DEFERRED and separate scope; run focused GREEN, related adoption/evidence regressions and architecture ratchet. No whole-reactor acceptance claim.

## Self-review pending

- Verify exact current version ID for local receipt and business key/version number/content hash for imported evidence, each within canonical tenant scope and under existing aggregate lock.
- Check failed metadata patches remain atomic, ordinary requirements and nonblocking OPEN/DEFERRED evidence remain reviewable, and older evidence does not block a new version.
- Confirm baseline tampering produces a generic 409 without frozen foreign bytes, including branch distinct from historical analysis basedOnBranch.
- Task 2b handoff: this guard reads the current v1 portable `Payload` and revision contract. If Task 2b introduces a v2 ancestry schema, reuse its strict codec/validated review projection; do not make this guard a permissive second decoder.
