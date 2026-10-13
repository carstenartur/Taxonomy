# Resilient analysis and partial architecture

Approved in the Taxonomy conversation on 2026-10-13. Scope: existing local and
Artemis analysis, continuation journal, result projection and recovery UI.

## Contract

A completed invalid model answer leaves only its assessment unresolved. Continue
independent work without another confirmation, without inventing zero scores,
negative findings or relationships. Each answer is stored under its original
scope, requirement, provider policy and catalogue identity. There is no automatic
provider retry of failed or uncertain work.

Distinguish durable question states: SUCCESS (accepted answer), UNASSESSED
(completed failed assessment), CLARIFICATION (valid relation answer with an actual
question), FAILED (execution/ownership/storage uncertainty requiring a decision),
SKIPPED (explicitly left open), READY and ATTEMPT. A valid relation answer with one
unresolved decision preserves its other validated decisions. The whole run may
finish COMPLETED_WITH_GAPS while the journal retains the open questions.

Generate an architecture independently of complete relation success. Include only
available assessed nodes and validated relation evidence. If detailed rendering
fails, build a bounded, hierarchy-only view from the same immutable result data;
keep the full evidence report. Empty evidence is an explicit empty state, not an
invented architecture. Cancellation and memory/time guards must not admit more
provider work. Database, authorization and stale-owner failures must not be
converted into successful persistence.

Explicit retry may target one or several open questions, atomically bound to the
same revision. Reuse accepted answers. Do not retry unselected failures. Reject a
mixed invalid selection before changing any state. Keep the prior architecture
visible while rebuilding; do not admit global absence claims with partial input.

The UI describes completed requests, affected names and IDs, failure impact and
real clarification questions. Routine incomplete results do not auto-open a
modal. Details are opened explicitly; prompt/raw response remain unmodified and
are loaded only on demand. German/English wording and keyboard operation apply.

No new provider, queue, workflow, polling loop, schema migration or relaxed
verification threshold. Extend the existing state column and archive validator.

## Fail-closed boundaries verified during continuation

A failed distributed work grant keeps its actual execution cause as well as its
conservative full charge. Only an ordinary relation evaluation failure allows
additional independent grants; cancellation, resource stops and unknown failure
codes do not. Aggregation must not hide a blocking cause behind an earlier
non-blocking assessment failure.

The fallback may retain individually assessed elements when hierarchy metadata
is cyclic or exceeds the bounded ancestry scan, but it must leave their containment
unasserted and explain the incomplete hierarchy check.
