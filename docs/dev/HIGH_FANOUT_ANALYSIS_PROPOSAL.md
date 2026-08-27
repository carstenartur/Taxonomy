# High-fan-out taxonomy analysis proposal

Status: **design proposal**. Implementation is tracked in
[#910](https://github.com/carstenartur/Taxonomy/issues/910).

## Problem

The ordinary hierarchical scorer assumes that every direct child of a parent fits
into one LLM request. It sends the complete sibling list, including node codes,
names and descriptions, and asks the provider to distribute the parent score over
all returned keys.

That assumption is invalid for catalogue-like collections. Information Products
currently expose a very large flat candidate set (observed: 863 direct nodes). A
single request then has an unbounded prompt and an equally large required JSON
response. Increasing HTTP or SSE timeouts would only hide the underlying analytical
and capacity defect.

A flat product or facet catalogue is also not improved by inventing an artificial
hierarchy solely to reduce prompt size. Taxonomy must support a genuine high-fan-out
parent directly.

## Decision proposal

Whenever a parent has more than ten direct children, use two separate stages:

1. exhaustive relevance screening in deterministic batches of at most ten;
2. percentage allocation only across the nodes that survived screening.

The threshold and batch sizes should be configurable, but the defaults should all
be ten so the semantics remain easy to inspect and test.

## Stage 1: exhaustive relevance screening without percentages

The complete sibling collection is sorted deterministically and split into batches
of at most ten nodes. Every node is evaluated exactly once in the normal path.

The screening prompt asks whether each candidate has a genuine relation to the
requirement and parent context. Candidates do not compete with one another and the
prompt contains no percentage, parent-score distribution or sum constraint.

A successful response must contain **exactly every candidate key in the batch**.
Each value contains a Boolean decision; only positive candidates require a concise
reason:

```json
{
  "IP-1234": {
    "relevant": true,
    "reason": "The requirement explicitly needs an employee time record."
  },
  "IP-1235": {
    "relevant": false
  }
}
```

The parser rejects a response with missing, unknown or duplicate candidate keys.
This prevents an omitted model response from being silently interpreted as a
negative decision. A failed or unparseable batch remains incomplete and retryable;
it must never become ten false negatives.

For 863 children this produces 87 bounded screening batches rather than one
unbounded prompt.

## Stage 2: weighting and global normalization

After all screening batches have completed:

- no relevant nodes: every child receives zero and traversal stops;
- one to ten relevant nodes: one ordinary distribution prompt allocates the parent
  score across only those nodes;
- more than ten relevant nodes: batches of at most ten return independent relevance
  weights, for example integers from 1 to 100. These are not percentages and do not
  sum to the parent score inside each batch. The server globally normalizes all raw
  weights to the parent score using the existing largest-remainder algorithm.

The last rule is essential. Asking each weighting batch to distribute the complete
parent score would multiply the score budget and make the merged result
mathematically invalid.

Only children with a final positive normalized score are traversed further.

## Prompt limits

Every provider call must be checked before transmission against explicit limits:

- maximum candidates per prompt;
- maximum prompt characters or estimated tokens;
- maximum characters copied from one node description;
- maximum expected response size.

A limit breach selects the bounded procedure before an external call. It must not
truncate arbitrary candidates or descriptions silently.

Proposed settings:

```properties
taxonomy.analysis.high-fan-out.threshold=10
taxonomy.analysis.high-fan-out.screening-batch-size=10
taxonomy.analysis.high-fan-out.weighting-batch-size=10
taxonomy.analysis.prompt.max-characters=<measured value>
taxonomy.analysis.prompt.max-node-description-characters=<measured value>
```

Invalid combinations should fail at startup rather than changing analytical
semantics implicitly.

## Runtime, quota and cost policy

High-fan-out screening deliberately replaces one unsafe request with a known number
of bounded calls. The operation must therefore calculate its work before dispatch:

```text
863 candidates / 10 = 87 screening calls
+ weighting calls for the positive set
```

The UI and operation record should expose the estimated call count, configured
provider RPM, expected minimum duration and whether the provider is metered. A
provider-specific concurrency limit may reduce runtime, but it must never exceed
configured rate limits or start the same deterministic batch twice.

For a metered provider, an operator-configurable confirmation threshold should stop
a surprisingly expensive run before the first call. For an unmetered local provider,
the same estimate remains useful as duration and capacity evidence.

## Evidence and provenance

Each high-fan-out result must retain enough evidence to reproduce and review the
decision:

- parent code and parent score;
- stable candidate fingerprint;
- deterministic batch number and total batch count;
- exact candidate codes sent in each batch;
- provider/model and prompt-template fingerprint;
- explicit positive or negative screening result for every candidate;
- concise reason for every positive candidate;
- independent raw weight and final normalized percentage;
- retry/failure history;
- marker distinguishing ordinary from high-fan-out scoring.

Screened-out nodes should have explicit provenance such as `SCREENED_OUT`, not just
an unexplained zero.

## Progress and lifecycle

The operation can report real work instead of an invented percentage:

```text
Screening Information Products: batch 17 of 87 (170/863 candidates)
Weighting 14 relevant candidates: batch 1 of 2
Normalizing final child scores
```

Cancellation, restart recovery and event replay should integrate with the durable
analysis-operation work in #805 and #808. Completed batches must remain reusable
after reconnect or restart when their candidate, requirement, provider and prompt
fingerprints still match.

## Catalogue hierarchy validation

High fan-out may be genuine, but import fallback must be measured separately. A
source node declared below level one must not be silently attached directly to a
virtual root merely because its parent reference could not be resolved. Import
reporting should distinguish:

- genuine root children from the source catalogue;
- nodes reattached because of an invalid or missing parent;
- cycles or self-parent references;
- flat catalogue facets that intentionally have no deeper hierarchy.

No artificial IP grouping should be introduced until this evidence shows that the
source catalogue actually defines such groups.

## Required tests

1. Ten children use ordinary scoring; eleven use the high-fan-out path.
2. 863 children produce exactly 87 screening batches, each containing at most ten
   candidates.
3. Screening prompts contain no percentage or sum-to-parent instruction.
4. Every successful screening response contains exactly the candidate keys sent in
   that batch; missing or unknown keys fail the batch.
5. Zero positives produce deterministic all-zero scores.
6. Up to ten positives use one final distribution.
7. More than ten positives use independent weights followed by one global
   normalization whose sum equals the parent score exactly.
8. Repository result order cannot change deterministic batching or final scores.
9. A failed screening batch produces a partial or retryable operation, never false
   negatives.
10. Long descriptions cannot violate the configured prompt budget.
11. Estimated calls, minimum duration and cost-policy decision are available before
    dispatch.
12. A genuine flat IP or product catalogue works without fabricated hierarchy.
13. Broken parent references are reported independently from high-fan-out analysis.

## Review questions

- Should provider-specific lower batch ceilings be allowed while preserving the
  same two-stage semantics?
- Should ambiguous positive candidates receive a second independent screening pass?
- Should final raw weights come from the LLM, deterministic semantic similarity, or
  both with discrepancy evidence?
- At what estimated-call threshold should a metered run require explicit additional
  confirmation?
