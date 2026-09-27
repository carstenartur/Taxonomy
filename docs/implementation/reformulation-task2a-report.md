# Task 2a — baseline binding and positive-review guard

Status: implementation checkpoint, not yet accepted. Root owns integrated gate.

## TDD evidence to date

- Baseline API/database RED: `ReformulationIsolationTest#storedForeignBaselineNeverLeaksThroughScopedProposalRead`, 1 test, expected 409 but received 200 after swapping persisted baseline payload with another requirement's valid frozen payload.
- Baseline GREEN: same focused Maven command, 1 test, 0 failures, build success (35.960 s). The service now compares the decoded scope, source version and snapshot with the physical proposal and canonical request scope before exposing it.
- Positive-review API/database RED: `ReformulationPositiveReviewGuardTest`, 5 tests; 2 expected failures: current local conflict and imported current conflict both returned 200 instead of 409; 3 compatibility tests passed. Build failure due solely to those two assertions (35.240 s).
- Independent positive-state RED after first guard implementation: `ReformulationPositiveReviewGuardTest#blockingLocalEvidencePreventsEachPositiveRequirementStateWithoutReviewChange`, 3 parameterized cases; APPROVED passed, IMPLEMENTING and SATISFIED each returned 200 instead of 409 (38.020 s). Minimal gate extended to those workflow-positive states.
- Added separate CONFIRMED-only, DEFERRED, CONFLICT finding, same keys in different scope, and structurally invalid imported portable-evidence cases; covering run pending.
- Next: cover independent positive axes, blocking findings, DEFERRED and separate scope; run focused GREEN, related adoption/evidence regressions and architecture ratchet. No whole-reactor acceptance claim.

## Self-review pending

- Verify exact current version ID for local receipt and business key/version number/content hash for imported evidence, each within canonical tenant scope and under existing aggregate lock.
- Check failed metadata patches remain atomic, ordinary requirements and nonblocking OPEN/DEFERRED evidence remain reviewable, and older evidence does not block a new version.
- Confirm baseline tampering produces a generic 409 without frozen foreign bytes, including branch distinct from historical analysis basedOnBranch.
