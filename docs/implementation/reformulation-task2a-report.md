# Task 2a — baseline binding and positive-review guard

Status: focused Task 2a verification green; independent review and root's integrated gate pending.

## TDD evidence to date

- Baseline API/database RED: `ReformulationIsolationTest#storedForeignBaselineNeverLeaksThroughScopedProposalRead`, 1 test, expected 409 but received 200 after swapping persisted baseline payload with another requirement's valid frozen payload.
- Baseline GREEN: same focused Maven command, 1 test, 0 failures, build success (35.960 s). The service now compares the decoded scope, source version and snapshot with the physical proposal and canonical request scope before exposing it.
- Positive-review API/database RED: `ReformulationPositiveReviewGuardTest`, 5 tests; 2 expected failures: current local conflict and imported current conflict both returned 200 instead of 409; 3 compatibility tests passed. Build failure due solely to those two assertions (35.240 s).
- Independent positive-state RED after first guard implementation: `ReformulationPositiveReviewGuardTest#blockingLocalEvidencePreventsEachPositiveRequirementStateWithoutReviewChange`, 3 parameterized cases; APPROVED passed, IMPLEMENTING and SATISFIED each returned 200 instead of 409 (38.020 s). Minimal gate extended to those workflow-positive states.
- Added separate CONFIRMED-only, DEFERRED, CONFLICT finding, same keys in different scope, and structurally invalid imported portable-evidence cases.
- Covering GREEN: `ReformulationPositiveReviewGuardTest,ReformulationIsolationTest`, 13 + 6 = 19 positive test cases, zero failures/errors/skips; build success (51.223 s). Imported STRUCTURAL_LOSS goes through checksum-valid portable DSL validation and real materialization, rather than an adoption path which correctly refuses structural loss.
- Extended the baseline regression to mutate all seven serialized identity coordinates independently. This is an additional boundary matrix on the already RED/GREEN baseline validation behavior, not a separately claimed RED cycle.
- Expanded focused run: `ReformulationPositiveReviewGuardTest` 13/13, `ReformulationIsolationTest` 7/7, `ReformulationEvidenceRoundTripTest` 3/3, `ArchitectureContextDependencyRatchetTest` 22/22: **45 tests, zero failures/errors/skips**, build success (55.509 s).
- Existing `ReformulationAdoptionTest`: **3 tests, zero failures/errors/skips**, build success (57.755 s), including fresh-process persistence/restart. Across these two final commands: 48 positive tests. Upstream Maven reactor modules were built but not indiscriminately test-selected (`surefire.failIfNoSpecifiedTests=false`).
- The guard runs only for explicit APPROVED/IMPLEMENTING/SATISFIED status or CONFIRMED review metadata requests after the existing aggregate lock. Local receipts are selected by exact tenant scope, requirement ID and current version ID; imported evidence by scope, project/requirement business keys, current version number and content hash. A CONFLICT question or CONFLICT/STRUCTURAL_LOSS finding blocks; OPEN/DEFERRED do not.

## Self-review

- Existing project then requirement pessimistic locks cover the guard's scoped evidence reads. No command-ID-global search, schema change or new approval path was introduced.
- Failed metadata patches leave the entire requirement view unchanged. Ordinary requirements, clean adopted evidence with OPEN/DEFERRED questions, unrelated requirement and newer current version pass. Portable STRUCTURAL_LOSS is checksum-valid DSL materialized into a separate repository, not an impossible structurally invalid local adoption.
- Frozen baseline scope uses canonical `PortfolioScope` repository/workspace/branch plus physical proposal project/requirement/source version/snapshot. All seven mutations return generic 409 without the injected value; historical source version creation stays allowed. Analysis payload `basedOnBranch` remains separate and unaltered.
- Limitations: Task 2a does not implement ancestry/v2 schema or infer positive review from draft/adoption. No entire reactor or real-provider quality claim; root owns the integrated gate and independent review.
- Task 2b handoff: this guard reads the current v1 portable `Payload` and revision contract. If Task 2b introduces a v2 ancestry schema, reuse its strict codec/validated review projection; do not make this guard a permissive second decoder.

Next command for independent review: `git diff 363618842d905807689f2b9b932d9642bd49c5d9..HEAD -- taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/reformulation taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/ProjectPortfolioService.java taxonomy-app/src/test/java/com/taxonomy/portfolio/reformulation docs/implementation/reformulation-task2a-report.md`.

## Independent-review fix round 1

- Review found mixed-case imported business identity bypass and malformed/null stored-baseline error handling. The review's original P/R evidence into physical p/r direction was inaccurate: portfolio creation normalizes keys to uppercase. The reachable inverse is valid lowercase p/r portable DSL into existing physical P/R; materialization reuses P/R but retains lowercase evidence bytes/hash. No SQL-mutated fixture is used.
- RED: corrected real import/API case, 1 test, expected 409 but received 200 (36.068 s). Earlier first fixture stopped on canonical physical key assertion, not a valid guard RED; this was corrected before the meaningful RED.
- RED: JSON `null` baseline returned 500 and malformed `{untrusted-foreign-marker` returned 422; 2 parameterized API/database tests expected 409 (40.646 s combined with the obsolete first import fixture). Exact response leak check is in both tests.
- Narrow fix: case-insensitive business-key selection and comparison while preserving exact portable row/payload binding and content hash/version/scope; proposal-local baseline decode failures are generic 409 without changing the shared JSON codec.
- Covering GREEN after fix: `ReformulationPositiveReviewGuardTest` **14/14** and `ReformulationIsolationTest` **9/9**; total **23 tests, zero failures/errors/skips**, build success (50.855 s). The earlier 45+3 regression runs remain evidence for the pre-review checkpoint; they were not repeated in this focused correction round. Independent scoped re-review and root's integrated gate remain pending.
