# Analysis scope and resource QA design

Date: 2026-10-01. Baseline: `d6ecb6b16699270fc476ca2fe1e6e21d5e4a816c`.

## Contract

- Add immutable `AnalysisScope(Set<String> taxonomyRoots, AnalysisMode mode)` and
  `FULL` / `TAXONOMIES_ONLY`. Request/result field: `analysisScope`.
- Missing/null request scope and empty API root set preserve legacy all/full behavior.
  Canonicalize ordering; reject unknown, null and blank roots before admission or paid work.
  Browser explicit none is invalid, distinct from the API's empty-means-all convention.
- Selected roots constrain scoring and relation sources. FULL targets retain all
  compatible taxonomies; explain this in the UI.
- TAXONOMIES_ONLY skips hierarchical relation search, heuristics, hypothesis persistence
  and relation-dependent architecture derivation.
- Results retain the full navigation tree and scope metadata. Coverage and progress
  apply only to selected roots. Never fabricate zero scores for unselected nodes.
- Freeze scope in continuation identity, retry, continue, cancellation, replay,
  draft save/restore and JSON exchange. Equivalent root order has the same identity.
  Old all/full signatures remain valid.
- Keep next-run controls separate from completed evidence. Replacement manual or
  interactive evidence clears the previous result scope, retaining next-run controls.
- Saved JSON scope is optional provenance: preserve absent/null metadata for manual,
  interactive and legacy evidence. Validate every declared root against the catalogue
  and all included score/raw/coverage identities against explicitly selected roots.
  Partial evidence remains valid; unknown unscoped legacy nodes retain warning behavior.
- Intentional partial Copilot runs must not generate global completeness, gap or
  recommendation claims.
- Delayed input/stale checks must preserve newer completion, warning and error
  feedback while retaining stale-text actions and revert behavior.

## Refactoring boundaries

Use one recursive scoring implementation for full/streaming paths, with an optional
callback. Preserve scores, reasons, product suitability, warnings and failure evidence.
Preserve legacy SSE catalogue order; full and explicitly scoped runs use priority order.
The legacy SSE endpoint remains taxonomy scoring, without new relation phases.
Do not retain an unused score-context map in streaming.

Within each relation search, cache immutable scalar child lists (including empty
results), validate/decode fresh responses once, continue validating replay, and maintain
progress counters by target root plus task indexes by source. Preserve exact prompts,
batches, call order, logical call counts, evidence and durable recovery. Do not change
provider quotas or introduce concurrency implicitly.

Repair export containment once after final diagram selection: nearest surviving
ancestor or null, cycle-safe, no source graph mutation, semantic edges unchanged.

## Verification and exclusions

Use failing behavioral tests before implementation. Cover scope rejection, selected
scoring, taxonomy-only side effects, partial results, continuation identity and exchange.
Compare relation prompts/evidence/counts with deterministic reference fixtures, including
invalid/replayed responses and progress re-assessment. Exercise real ArchiMate/Visio
validation after ancestor filtering. Run UI contracts, reactor tests and available
quality gates; obtain independent review.

No live-model quality or end-to-end latency claim follows from deterministic tests.
Broader batching/parallelism needs bounded scheduling, context propagation, cancellation
fencing and a semantic reference corpus. Preserve tenant and transaction boundaries;
do not invent a universal workflow/CRUD superclass.
