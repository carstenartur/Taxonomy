# Finalization retirement and phase-bound verification

Follow-up to PR #1101 review comments 4067147579, 4067147600 and 4067147646.
The source baseline is ffd7d43. No automatic requirement adoption is added.

## Two orders, one finalization decision

Previously the worker checked `!local.retired()` before calling `recovery.finish`.
A stop between that check and acquisition of the database locks could still publish
the draft. A real application regression pauses precisely at that boundary, consumes
the interrupt as a JDBC driver may, and reproduced an unwanted COMPLETED run.

The transactional recovery service now validates and acquires the existing scoped
proposal/run/lease locks first. It then calls the worker's synchronized, one-shot
finalization permit. Retirement and that permit use the same local monitor. A stop
that wins first rejects finalization and leaves the run and checkpoints recoverable.
A finalization that wins under the database locks is already admitted and may finish
its transaction, even if shutdown starts afterwards. This explicit linearization
point replaces an unsafe check before waiting for the locks.

No local monitor is held during database/network I/O. Shutdown does not wait for
an admitted transaction, nor interrupt its commit. It still retires the delivery
and admits no more provider work. A failed finalization transaction is not followed
by a second terminal mutation from the same one-shot permit; persistent lease
recovery remains available. Old direct service callers retain the three-argument
method and its existing database fence; actual workers use the guarded overload.

Two new fresh-application checks prove both orders. The first reproduced the old
completion-after-retirement defect; the second failed for the absent lock-scoped
permit. Both pass after correction. Existing local-capacity and graceful-stop
checks remain, and the JUnit workflow requires all four tests. Controlled advice
only pauses the real transactional service; persistence, provider gateway, parser,
original/version checks and run state remain real. No cloud LLM is called.

## Reports belong to their Maven phase

The focused profile now configures Surefire's report directory as
`target/surefire-reports-reformulation-usage`. Its verifier reads only that path.
The first full analysis/upstream run still writes `target/surefire-reports`.
Both are archived. A successful first-phase Python-policy report cannot certify
the second phase if Maven stops before that suite executes.

A single combined `mvn clean test` is not used as the freshness boundary: Maven
processes modules sequentially and can fail before cleaning a later module. Separate
report destinations prevent this cross-phase reuse without deleting prior evidence,
changing selected tests or replacing Maven's nonzero process exit. The profile
agreement check failed before the new report configuration and passes afterwards.
CI checkout is clean and target directories are not restored from caches; these
paths distinguish phases inside that job, not arbitrary manually reused worktrees.

The historical transport-only limits in `reformulation-usage.md` now explicitly
refer to the implemented persistence/worker/UI integration instead of contradicting it.

## Verification boundary

Local Java 21 compilation and executable checks do not substitute for the new
Maven/coverage/database/browser runs. The preceding ffd7d43 focused run passed
2,406 archived JUnit tests. Its results are historical for this follow-up; current
head verification and review remain necessary. Original requirements and saved
user decisions are not changed by these fixes.
