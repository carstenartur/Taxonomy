# Analysis dispatch and durable-effect concurrency (#1161 P02/P03)

This describes the transport foundation in PR #1169, not completed distributed
production analysis. P04–P10 of #1161 still own production root/result wiring,
sharding, relation fan-out, cancellation/progress, permits and deployment.
The compatibility default remains local execution.

## Transaction boundaries

Dispatch intents remain in the caller's operation transaction. A duplicate first
insert must not roll back the caller's other changes or commit an intent whose
operation later rolls back. `AnalysisInsertIfAbsent` flushes pre-existing JPA
state, takes a JDBC savepoint on the same Hibernate-managed connection, then
performs only the tentative JDBC insert. Only a recognized unique-key violation
is contained by rollback to that savepoint. No failed JPA flush is caught and
reused. Every duplicate must have a visible, matching winning row; unrelated
constraints, connection errors and source mismatches still fail closed.

The supported unique-error signatures are PostgreSQL/HSQLDB `23505`, Oracle
`23000/1`, and SQL Server `23000/2601` or `23000/2627`. SQL Server does not release
individual savepoints; transaction completion releases them. HSQLDB invalidates
the target savepoint on rollback, so that path does not release it again. The
transaction must support JDBC savepoints and visibility of a committed winner
under the ordinary read-committed isolation. Stronger isolation can legitimately
require retry of the enclosing operation; a non-visible winner is never treated
as success. Database-matrix verification is distinct from local HSQLDB evidence.

Batches acquire insert locks in stable task-key order, independently of input
order. Returned pending IDs retain the original publication order. Broker I/O is
still immediate after commit, outside the operation transaction. Recovery is
bounded and event-triggered; no timer or periodic database polling is introduced.

## Prepared work and atomic result commit

`AnalysisTaskPreparation` replaces only the new worker-side preparation port;
the existing same-thread `AnalysisTaskHandler` API remains unchanged.
Preparation includes expensive computation/provider calls and must have **no
durable effects**. It returns `PreparedAnalysisCompletion`: the computed
completion plus a short durable-result mutation.

`AnalysisTaskCompletionStore.commit` reserves the completion key before invoking
that mutation and commits both in the same short database transaction. A
concurrent loser returns the matching winner without invoking its mutation.
If the winner's mutation fails, its reservation and result changes roll back
together and a redelivery can retry. Independent task keys have no process-wide
or operation-wide execution lock. Two concurrent deliveries may compute twice;
this is an at-least-once computation contract, not an exactly-once provider-call
promise. Only the winning database mutation produces a durable result.

A result callback must join the store transaction and revalidate current durable
operation authority, revision and cancellation before its writes. It must not
start `REQUIRES_NEW`, acknowledge JMS, perform external I/O, or mutate an external
system. Those effects cannot be made atomic by this local transaction. Production
handlers satisfying that contract are part of the remaining #1161 work; an empty
handler configuration does not certify production clustered execution.

Task/completion reuse also checks the immutable envelope source (operation,
task/type, schema, repository/workspace/branch/commit, requirement, roots and
deadline). Attempt counters and completion timestamps are not source identity.
JMS completion publication and delivery acknowledgement occur only after the
result transaction returns successfully. Failure after result commit is therefore
replayed from the ledger without applying the business mutation again.

## Regressions and verification

`AnalysisDispatchInsertConcurrencyTest` runs eight cases against real
Hibernate/HSQLDB transactions: simultaneous absent-row insertion with both sibling
operation writes retained, caller rollback, duplicate-caller rollback, preserved
publication order, unrelated constraint failure, source mismatch, reversed
concurrent batches and precise unique-error classification.

`AnalysisDurableEffectTest` runs six real-database cases: simultaneous duplicate
results, exception rollback/retry, error rollback/retry, independent-key overlap,
preparation outside the result transaction with durable replay, and source fencing.
They assert business-table contents as well as completion counts. Deterministic
latches pause actual persistence reads; only observation is proxied, not database
behavior. Their shared `*Cases` classes can also execute directly with Java 21.

`ArtemisAnalysisTransportTest.simultaneousDuplicateDeliveriesCommitOneActualDatabaseEffect`
adds two real worker/store instances, a persistent disposable TCP Artemis broker,
and a real HSQLDB result table. Both preparations must overlap. The duplicate
messages deliberately omit `_AMQ_DUPL_ID`, so broker deduplication cannot hide a
broken effect boundary. Both deliveries publish the same completion; the business
mutation and completion table each have exactly one row. Existing restart,
redelivery, malformed-message, poison/DLQ and shard-subscription tests remain.

Focused Maven execution from a complete checkout:

```sh
./mvnw -B -pl taxonomy-app -am test \
  -Dtest=AnalysisDispatchServiceTest,AnalysisDispatchInsertConcurrencyTest,AnalysisDurableEffectTest,ArtemisAnalysisTransportTest,ArtemisAnalysisTransportConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

That command is a focused iteration, not full CI. The authoritative entry point
remains `./mvnw -B verify -Pci -DrunOnnxTests=true`, together with the repository's
separate database matrix. Local direct Java execution of the fourteen cases is
not a Maven/JUnit, broker, external-database or complete-application acceptance
claim. Published commit-specific CI results belong in the PR and #1161 tracker.
