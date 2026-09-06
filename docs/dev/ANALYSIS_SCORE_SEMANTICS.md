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

- `rawScores`: canonical provider evidence before product-relevance weighting; malformed legacy
  entries with blank keys or null values are discarded once, keys are trimmed and values are
  bounded to 0–100; multiple source keys that collapse to the same canonical code fail closed;
- `scores` and `effectiveScores`: comparable relevance used by existing generic consumers;
- `productSuitabilityScores`: raw values for concrete products only;
- `scoreDetails`: node-level kind, raw value, effective value, parent identity and parent value;
- `scoreSemanticsVersion`: version of this interpretation contract;
- `scoreSemanticsWarnings`: up to 100 concrete compatibility warnings; when further warnings exist,
  one additional final suppression marker is appended.

Canonical score maps and typed detail maps are ordered by their stripped node codes, not by the
pre-normalization source strings. This keeps fingerprints, snapshots, reports and diffs stable even
when legacy input contains harmless surrounding whitespace.

`SnapshotDiff.scoreChanges` preserves sorted node-code order at the REST DTO boundary. Its constructor
makes an unmodifiable defensive copy, so changing the caller's map cannot alter an existing diff.
An absent score-change map becomes an immutable empty map. The persistence service passes the
ordered changes directly to this constructor rather than replacing them with an unordered copy.

Old snapshots that contain only `scores` remain readable. Taxonomy derives their semantics from the
frozen taxonomy tree. An unresolved product parent fails closed to effective relevance zero and
produces a warning instead of promoting conditional suitability to global relevance. Duplicate node
codes, reused node objects and hierarchy cycles are rejected rather than resolved by traversal
order. HTTP report requests must use canonical, already-trimmed score keys so semantic normalization
cannot collapse distinct request entries.

## HTTP decision-report input

The required `scores` map selects the report's node codes. When `rawScores` is absent or null,
`scores` retains its legacy meaning as raw analysis evidence. New-format clients supplying
comparable values in `scores` must also supply `rawScores` for every selected code.

A non-null `rawScores` map must cover the complete `scores` key set with valid 0–100 values.
An empty or partial raw map is rejected with HTTP 400 by all three export endpoints before
workspace resolution, catalogue access or report rendering. Zero is valid explicit evidence,
not a missing value. Additional raw entries remain bounded and validated but are excluded from
the report's selected key set. Derived maps never fill gaps in raw evidence, and the controller
never silently mixes or falls back between raw and effective values within one request.

## Streaming

Incremental SSE score batches remain raw because a concrete-product batch normally does not repeat
its already emitted family score. Their `scoreDetails` entries are deliberately incomplete semantic
hints containing only node identity, score kind, raw value and known parent identity. They do not
publish `effectiveRelevance` or `parentScore`, and `scoreSemanticsWarnings` exposes unresolved
batch-local context. The browser combines these hints with accumulated raw family evidence. Only
complete and terminal-error events publish the authoritative full raw/effective envelope.

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
