# Bounded parent reformulation

This continues package 5 of the approved requirement-reformulation plan. A parent
whose complete prompt fits the existing provider budget keeps its single-call
path. The provider gateway exposes a side-effect-free preflight using the same
budget policy that still guards the final HTTP request. Preflight does not issue
HTTP requests or consume recording/throttle slots.

An oversized parent can be divided into at most 16 sibling groups, followed by
one parent composition. The groups contain whole child formulations and/or whole
terminal-contribution records; no original text is shortened. All groups are
planned and preflighted before any model request. A single indivisible child,
an oversized fixed context or an excessive group count fails with
`INPUT_TOO_LARGE_FOR_PROVIDER`, not a truncated success.

Every group receives the complete original, source anchors, direct parent
contributions, user answers and directed boundary edges. Question prerequisites
and statement decision dependencies bring the required sibling question contracts
and supporting statements into the group. Strong coupling may therefore make an
input unsplittable; silently removing dependencies is not an alternative.

The final composition receives group summaries, stable statement references and
complete decision contracts. Repeated large discovery context descriptions are
projected as references in that prompt only. The canonical stored questions retain
their full discovery contexts, rationale, source spans, node/edge references and
original identities. Existing/new statement and question bodies remain in the
returned document; a short parent summary does not replace them. A visible
`GROUPED_SYNTHESIS` semantic-review finding records this abstraction. It does not
claim the model saw or semantically revalidated every detail simultaneously.

`NODE_GROUP` and `NODE_AGGREGATE` use the existing scoped checkpoint executor.
They are wired into Phase A and both affected-rewording paths, including the
cross-taxonomy reconciler. Completed subgroups survive an interrupted parent;
retries reuse their exact validated outputs and identities. Cache reads and
writes retain the existing active-run/lease checks. No new database migration,
requirement version, adoption operation or per-group Git commit is introduced.

The aggregate itself is also budget checked. If all decision contracts together
cannot fit, the run fails explicitly; successful group checkpoints stay available
for a later attempt. One bounded repair remains allowed per actual provider call,
so 16 groups plus an aggregate can require up to 34 requests including repairs.
This bounds a parent, not the total number of parents in a run.

## Executable coverage

`BoundedNodeSynthesisTest` invokes a plain driver with the real OpenAI-compatible
gateway, budget policy, prompt builder and strict parser. A loopback HTTP server
supplies authored responses; no cloud credentials are used. The driver checks:

- oversized and small parents, exact original/edge preservation, and oversized
  originals rejected before any request;
- terminal contribution grouping with original full discovery context retained;
- question prerequisites and statement-to-question dependencies across groups;
- transaction guarding before budget checks;
- group-count limits rejected before any request.

The focused fixture uses a 16,000-character budget to make the boundary observable.
The initial 22,398-character parent failed before this feature. The grouped result
requires two group calls and one parent composition in that case. These are
constructed input contracts, not evidence of live-model wording quality.

`ReformulationGroupCheckpointTest` launches two fresh application JVMs with the
same file HSQL database. Its authored large child formulations exceed the actual
production budget. The producer commits one group and interrupts before the next.
After explicit cancellation/retry of this legacy direct-run fixture, the second
JVM makes exactly two remaining calls, preserves the first question identity,
reuses an identical result with zero extra requests and rejects reuse once the
run is completed. The active requirement and its version count stay unchanged.

This is subgroup checkpoint/restart coverage, not a second claim of automatic
process recovery or the final source-to-architecture civil acceptance. The existing
forced-process-death recovery suite covers the automatic coordinator separately.
Normal-exit child JVMs inherit the actual JaCoCo agent when present.

## Remaining bounds

There is no parallel child execution in this slice. A cross-taxonomy reconciliation
review whose whole decision graph is oversized is still rejected; this slice bounds
node formulation and rewording, not that separate review schema. Detailed run cost,
retention and partial-result UI remain outstanding. Adoption and historical exports
remain the following plan packages.

Local evidence uses freshly compiled Java 21 overrides and exact current-tree CI
runtime libraries. Maven/JUnit wrappers, full reactor, architecture inventory,
coverage and all database/browser lanes must run in CI. No local full-reactor or
independent-review success is implied.
