# Reformulation dispatch claim regression

A durable scheduler may deliver a task more than once. A losing delivery must not
mark the worker that acquired the QUEUED -> RUNNING transition as failed.

Previously the execution service caught a failed claim and invoked `finishRun`.
Because the winner was already RUNNING, that call changed its envelope to FAILED.
The fix allows failure publication only after that invocation acquired its claim.
A rejected claim propagates without publishing, checkpointing or calling the LLM.
Provider thread-local context is cleared in both paths.

`ReformulationDispatchClaimTest` runs the real execution service, proposal locks,
Spring application, database, parser and provider gateway. Only provider HTTP
responses are authored test data. The first response is held by a latch while the
same queued runnable is delivered a second time. The run must remain RUNNING,
then complete and publish exactly one proposal revision. Replaying completion
must preserve its result, and the original requirement version stays unchanged.
The JUnit wrapper runs in-process to retain ordinary coverage instrumentation.
The standalone driver is also executable with the application's classpath.

Observed locally on the PR-1098 runtime tree `d823eb15bca5e99b5c675b4132e234597c06c492`:

- Before correction: `Losing dispatch changed the winning run to FAILED`.
- After compiling the corrected production class: `REFORMULATION_DISPATCH_CLAIM_OK`,
  two provider calls (one node and one reconciliation), one proposal revision.
- The separate actual JavaScript API-adapter contract also passes after its
  cancellation-path fix. Traversal, extra paths and unrelated operations remain rejected.

This does not implement automatic process takeover, lease expiration or parallel
subtree scheduling. It protects a required recovery boundary. Full Maven, database
and browser verification remain CI gates; the standalone local driver is not a
claim that all of those gates ran locally.
