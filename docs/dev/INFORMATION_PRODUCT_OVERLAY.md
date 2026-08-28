# Information Product catalogue overlay

## Decision

The checked-in C3 Excel workbook remains the baseline catalogue. Taxonomy applies
`taxonomy-app/src/main/resources/data/nato-taxonomy.json` as a versioned, fail-closed overlay before
catalogue persistence or reconciliation.

The overlay does **not** copy titles, descriptions, UUIDs or source references. It contains only
reviewable corrections and analysis metadata:

- the primary parent of a node;
- whether the node is a concrete `PRODUCT` or a `PRODUCT_FAMILY`;
- optional secondary classifications;
- confidence, review status and a written justification;
- the expected source title and state, which detect baseline drift.

The effective catalogue fingerprint combines the SHA-256 digest of the Excel bytes, the overlay
bytes and the overlay mapping version.

## Why the overlay exists

The source workbook contains an approved Information Product hierarchy plus 866 draft entries.
Before this overlay, 853 concrete products had no parent and were attached to the virtual `IP` root;
13 additional draft product-family entries contained missing parents, a self-reference or invalid
level values. That produced an 863-candidate sibling group and an unbounded LLM request.

The checked-in overlay:

- classifies all 853 concrete draft products;
- repairs all 13 draft product families;
- reduces direct `IP` children to the single source root `IP-1000`;
- derives effective levels from the repaired parent graph instead of trusting inconsistent level
  cells;
- keeps ambiguous classifications visible with `reviewRequired=true` and secondary alternatives.

Of the 853 concrete-product mappings, 504 are currently accepted by the deterministic mapping
rules and 349 remain explicitly provisional for domain-expert review. Provisional mappings are
active primary parents so the hierarchy is usable, but their review metadata is exposed through the
API and must not be confused with an externally approved catalogue decision.

## Schema contract

The current schema is version 2 and uses `mode: OVERLAY`.

```json
{
  "schemaVersion": 2,
  "mode": "OVERLAY",
  "baseCatalogue": "C3_Taxonomy_Catalogue_25AUG2025.xlsx",
  "mappingVersion": "information-products-2026-08-28-v1",
  "validation": {
    "requireExplicitPatchForRoot": "IP",
    "requireExplicitPatchForState": "draft"
  },
  "nodePatches": [
    {
      "code": "IP-1659",
      "expectedTitle": "AEW Logistics Situation Reports",
      "expectedState": "draft",
      "parentCode": "IP-2139",
      "analysisRole": "PRODUCT",
      "secondaryClassificationCodes": ["IP-2175"],
      "confidence": 0.89,
      "reviewRequired": false,
      "justification": "..."
    }
  ]
}
```

Unknown nodes or parents, duplicate patches, cross-root relationships, self-references, cycles,
unpatched nodes covered by strict validation and source-title/state drift stop startup. The loader
never silently moves such nodes to a virtual root.

## Persisted databases

When catalogue rows already exist and `TAXONOMY_INIT_RELOAD_EXISTING=false`, startup loads those
rows, reapplies the overlay idempotently, updates changed parent/level values and preserves existing
relations. `TAXONOMY_INIT_RELOAD_EXISTING=true` remains the explicit destructive path for replacing
the complete Excel baseline.

## Analysis semantics

Category children retain hierarchical budget scoring: their integer percentages are normalized to
the parent score.

Concrete products use a different contract:

1. products are sorted by code;
2. they are sent in deterministic batches of at most ten (`TAXONOMY_ANALYSIS_PRODUCT_BATCH_SIZE`, default 10);
3. every product receives an independent 0–100 suitability score;
4. values are never normalized or forced to sum to a parent budget;
5. values below `TAXONOMY_ANALYSIS_PRODUCT_MIN_SCORE` become explicit zeroes;
6. when a relevant product family has no product above that threshold, the analysis returns a
   structured `productCoverageGaps` entry.

A failed or incomplete concrete-product batch makes the overall analysis `PARTIAL` and
adds an explicit warning. Its zero placeholders are not treated as evidence of an actual
catalogue gap, so no confirmed `productCoverageGaps` entry is emitted for that batch.

A parent may contain both category and product children. Categories are evaluated with the parent
budget, while direct product children are evaluated independently. This preserves a genuine source
hierarchy without inventing artificial groups solely to satisfy prompt-size limits.

## Review workflow

`TaxonomyNodeDto` exposes `analysisRole`, secondary classification codes, confidence,
`classificationReviewRequired` and the mapping justification. Low-confidence mappings therefore
remain usable as provisional primary parents while still being identifiable for expert review.

Changes to the overlay should include:

- an updated `mappingVersion`;
- an explanation in the pull request;
- overlay integrity tests;
- review of the largest product-family sibling groups;
- confirmation that the effective hierarchy remains acyclic and every `PRODUCT` is a leaf.
