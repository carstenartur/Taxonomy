# Analysis score semantics

Taxonomy uses more than one 0–100 scoring contract. Equal numeric values are not necessarily
interchangeable.

## Score kinds

| Kind | Meaning | Comparison contract |
|---|---|---|
| `ROOT_RELEVANCE` | Independent relevance of one taxonomy root | Comparable as root-level relevance; root values do not need to sum to 100 |
| `HIERARCHICAL_RELEVANCE` | Absolute relevance carried through a parent budget | Comparable with other effective relevance values |
| `PRODUCT_SUITABILITY` | Independent suitability of one concrete `PRODUCT` conditional on its direct product family | Evidence only; never use directly as a hierarchy share, architecture anchor or relation score |

Only nodes whose frozen catalogue metadata has `analysisRole=PRODUCT` use the product-suitability
contract. Product families and all other ancestors remain hierarchically scored categories.

## Effective product relevance

Generic downstream consumers need one comparable value. Taxonomy therefore retains raw product
suitability and derives effective relevance deterministically:

```text
effective product relevance
    = round(direct family relevance × product suitability / 100)
```

Example:

```text
product-family relevance = 40
product suitability       = 80
product effective relevance = 32
```

The product is displayed as `Suitability 80%; effective relevance 32/100`. It is not displayed as
`200% of parent`, and generic ranking sees 32 rather than 80.

## Data contract

`AnalysisResult` exposes:

- `rawScores`: original provider evidence, including independent product suitability;
- `scores` and `effectiveScores`: comparable relevance used by existing generic consumers;
- `productSuitabilityScores`: raw values for concrete products only;
- `scoreDetails`: node-level kind, raw value, effective value, parent identity and parent value;
- `scoreSemanticsVersion`: version of this interpretation contract;
- `scoreSemanticsWarnings`: bounded compatibility warnings.

Old snapshots that contain only `scores` remain readable. Taxonomy derives their semantics from the
frozen taxonomy tree. An unresolved product parent fails closed to effective relevance zero and
produces a warning instead of promoting conditional suitability to global relevance.

## Streaming

Incremental SSE score batches remain raw because a concrete-product batch normally does not repeat
its already emitted family score. They carry score kind and parent identity, allowing the browser to
combine the batch with accumulated raw family evidence. Only complete and terminal-error events
publish the authoritative full raw/effective envelope.

## Downstream rules

- product coverage gaps continue to use completed raw suitability evidence and the configured
  suitability threshold;
- architecture selection, gap and pattern processing, recommendations, relationship hypotheses,
  portfolio comparison and generic exports use effective relevance;
- decision reports rank by effective relevance and show raw suitability separately;
- a local parent share must never be rendered below 0 or above 100;
- browser labels and accessible names identify product suitability explicitly;
- raw and effective values, typed details and frozen hierarchy belong to immutable snapshot and
  report evidence.

## Required regression example

Every implementation path must preserve this invariant:

```text
family = 40, product suitability = 80
raw suitability = 80
effective relevance = 32
rendered parent share is never 200%
```
