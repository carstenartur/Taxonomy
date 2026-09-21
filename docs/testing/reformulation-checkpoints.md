# Reformulation checkpoints and explicit retries (package 5, first slice)

This extends packages 1–4 of [the implementation plan](../superpowers/plans/2026-09-20-requirement-reformulation.md).
It is **not** the completion of all package-5 work or the adoption/export stages.

## User workflow

An active wording run now offers **Cancel run / Lauf abbrechen**. Cancellation
requires the current proposal revision and is scoped to the selected requirement,
proposal and workspace. It does not edit the original requirement, the proposal
text, questions, answers or unsaved browser inputs. The first cancellation actor
and timestamp are persisted on the run. Repeating cancellation leaves them intact.

Only one new active run is accepted per proposal. If an older application version
left several active runs, every active run remains visible and cancellable, even
outside the last-three history window.

After a restart, a previously running job is deliberately **not** taken over
silently. Cancel the old run and start a new run. Already committed, matching step
results are reused. This is an explicit retry, not an automatic lease/recovery
scheduler. A request already in flight may still finish and consume provider
resources, but a cancelled worker cannot save another checkpoint or publish a
proposal revision. Work not committed before interruption may need to run again.

## Stored results and scope

The existing proposal aggregate owns authorization and the proposal-then-run lock
order for lookup, completion, cancellation and publication. Provider calls stay
outside these short transactions. The package-private checkpoint store is not a
second authorization or orchestration layer.

Only successfully parsed node/rewording/reconciliation results enter the immutable
checkpoint table. Results are keyed by proposal and scope plus an exact-input
fingerprint: full input (including original, children, decisions and boundary
context), task kind, output type, provider, model, endpoint hash, prompt content,
prompt/schema versions, reconciliation configuration and checkpoint format version.
Map key order is normalized; array order is retained. Model output IDs therefore
survive retry, rather than being regenerated from another remote response.

There is no cross-proposal or cross-workspace reuse. Failed suppliers and responses
that arrive after cancellation are not cached. An individual serialized result is
bounded to 8 MiB. This does not introduce automatic cache retention/eviction or a
new global cost scheduler.

PostgreSQL gets additive migration `V24__reformulation_node_checkpoints.sql`.
Other supported Hibernate-managed schemas receive the mapped table/columns through
their existing schema lifecycle. No repository/architecture mutation or per-step
Git commit is added.

## Regression tests

- `ReformulationCheckpointTest` starts **two fresh application JVMs** against the
  same file-backed HSQL database. A deliberately small persisted analysis-tree
  fixture drives the real walk-up, strict parser, remote gateway and reconciliation.
  A loopback HTTP server supplies deterministic model responses. After the first
  child completes, traversal is interrupted and the first application is closed.
  The second application cancels the old run and retries: the child question keeps
  its ID and only two remaining node calls plus reconciliation reach the server.
  The same test checks exact-key reuse, key ordering, changed inputs, failed work,
  workspace/proposal isolation, late-result rejection and original-version safety.
- `ReformulationCancellationApiTest` uses the existing real Spring/MockMvc fixture
  to check CSRF, required/current ETag, tenant isolation, cancellation audit and
  unchanged proposal/requirement data.
- `ReformulationCancellationControlsTest` runs the shipped JavaScript controls
  against a small DOM/API boundary in Node, without npm dependencies. It checks
  explicit cancellation, legacy active runs, UI mutation fencing and retained
  unsaved input. This is a control-contract test, not a full browser acceptance.

Run in a normal checkout:

```bash
./mvnw -B -ntp -pl taxonomy-app -am test \
  -Dtest='ReformulationCheckpointTest,ReformulationCancellationApiTest,ReformulationCancellationControlsTest,ReformulationSynthesisTest,ReformulationQuestionWorkflowTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify -DexcludedGroups='real-llm'
```

The checkpoint test is specifically a recovery test with a stored analysis fixture;
it does not replace package 8's complete source-to-analysis civil acceptance flow.
A graceful application close between steps is tested; an operating-system kill in
the middle of a database commit is not claimed by this test.

## Remaining package-5 scope

Automatic leases/takeover, durable per-task scheduling with bounded parallelism,
large-fan-out grouping, detailed per-run token/cache accounting, retention policy,
and partial-result presentation remain separate follow-up work. The existing
provider context limits and parser checks stay in effect; this change does not
claim that arbitrarily large parents now fit a model context. Controlled adoption,
portable export evidence and the final civil workflow remain packages 6–8.
