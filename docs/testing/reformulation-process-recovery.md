# Automatic recovery of requirement reformulation runs

This continues package 5 of the approved requirement-reformulation plan. It does
not implement adoption, large-child batching or the final source-to-analysis
civil acceptance. Original requirements, active architecture and human proposal
edits remain unchanged by recovery.

## Execution authority

Newly dispatched runs and their immutable recovery envelopes are inserted in one
transaction. A recovery envelope retains the authenticated actor, exact workspace,
project/requirement/proposal identities, original proposal revision, frozen prompts,
provider/model and endpoint hash. Older runs without an envelope are deliberately
not reconstructed from current settings: their explicit cancel/retry path remains.

The existing portfolio aggregate remains the authority. Transactions acquire the
proposal lock, run lock and lease row in that order. Claims use the database clock,
a random process-local owner identity, an increasing epoch and an expiration.
Heartbeat, checkpoint hits, checkpoint writes and final publication verify that
exact claim. A worker that loses a claim cannot fail the winner. The actual LLM
request always occurs outside those transactions.

After application readiness, bounded timer ticks find queued or expired claims.
The shared portfolio executor performs the work. A cursor advances through due
runs so an authorization failure on one entry does not indefinitely hide later
entries. Current permissions are checked again; persisted actor strings do not
grant a new permission. Retries read the captured source revision, not a newer
manual edit. Final publication uses the existing manual-edit/reference-closure
checks and retains conflicts as candidates.

| Property | Default | Meaning |
|---|---:|---|
| `reformulation.recovery.lease-ms` | 120000 | Ownership lifetime; a crash is recoverable after expiration. |
| `reformulation.recovery.heartbeat-ms` | 10000 | Live-owner renewal interval; at most one third of lease lifetime. |
| `reformulation.recovery.poll-ms` | 5000 | Periodic due-run scan. |
| `reformulation.recovery.max-in-flight` | 2 | Local recovery dispatch bound within the shared executor. |
| `reformulation.recovery.max-attempts` | 3 | Maximum claims including the initial execution; exhaustion is recorded as a failed run. |

The PostgreSQL clock uses `clock_timestamp()`, not transaction-start time after a
lock wait. SQL Server and Oracle have their own server-clock expressions; HSQL
uses its timestamp expression. Migration V25 is additive and links each lease to
its existing run. V24 and older migrations are not rewritten.

This is not exactly-once execution of a remote provider. A process can die after
paying for a response but before committing its checkpoint. That request may be
repeated; already committed matching responses retain their original statement
and question identities. Power-loss durability, provider billing guarantees and
rolling-version compatibility are not established by the process test.

## Executable checks

`ReformulationProcessRecoveryTest` uses `ReformulationRecoveryHarness` to launch
fresh application JVMs. Its producer commits a real first-child LLM result and
blocks at the next remote request. The harness calls `destroyForcibly`, verifies
the process has terminated, then starts a new application against the same file
HSQL database. No manual cancel/retry occurs. The original question ID and
requirement version must survive; exactly three remaining remote calls complete
the small authored analysis tree.

A separate fresh JVM checks an unexpired claim, renewal, lease takeover, stale
cache reads/writes/completion/heartbeat, foreign workspace denial, cancellation,
manual-edit preservation, exact source revision, attempt exhaustion and bounded
queue pagination. These tests control scheduler lifetime to test lease contracts;
the actual process-recovery case uses the real automatic coordinator.

The pre-existing duplicate-dispatch driver still holds its first remote response
while a second identical delivery is attempted. Its JUnit wrapper now forks a
JVM because the Git bootstrap guard and JGit caches are JVM-global. Coverage-agent
arguments are inherited by normal-exit child JVMs; the intentionally killed
producer cannot be expected to flush coverage on shutdown.

Only the remote LLM HTTP replies are supplied by the test. The source analysis
snapshot is an explicitly authored small fixture, not evidence that the full
catalogue or the live model produced that architecture. Real Spring, persistence,
workspace provisioning, provider gateway, prompts, parser, walk-up, reconciliation,
checkpoints and proposal publication execute. This is not package 8's complete
source-to-architecture acceptance.

Focused Maven selection:

```sh
./mvnw -B -ntp -pl taxonomy-app -am test \
  -Dtest='ReformulationProcessRecoveryTest,ReformulationDispatchClaimTest,ReformulationCheckpointTest,ReformulationCancellationApiTest,ReformulationSynthesisTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Before merge, the full reactor, architecture/dependency checks, coverage gates,
PostgreSQL migration/validate startup, other database lanes, and existing browser
suites remain mandatory. New module edges are subject to the existing ratchet;
no policy threshold is relaxed by this change. A local run against exact CI-runtime
libraries is supporting evidence, not a claim that those complete gates passed.
