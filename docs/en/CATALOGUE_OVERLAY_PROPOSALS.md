# Reproducible catalogue-overlay proposals

The checked-in C3 workbook remains the baseline for node identity, titles, descriptions, source state, and source provenance. The checked-in `nato-taxonomy.json` file remains the reviewed overlay. Proposal generation does **not** rewrite either input.

## Purpose

When a new C3 catalogue is introduced, reviewers need to see which Information Product mappings still match the source, which provisional mappings may deserve a different parent, and which new draft nodes are not yet represented in the reviewed overlay. The repository-owned generator creates two deterministic review artifacts:

- `catalogue-overlay-proposal.json` for machine-readable comparison and later tooling;
- `catalogue-overlay-review.md` for human review and CI artifacts.

The artifacts contain no generation timestamp. Their content depends only on the workbook bytes, overlay bytes, and the versioned deterministic algorithm. Identical inputs therefore produce byte-identical output.

## Run locally

From the repository root:

```bash
tools/catalogue-overlay/generate.sh
```

Default inputs and outputs are:

```text
taxonomy-app/src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx
taxonomy-app/src/main/resources/data/nato-taxonomy.json
target/catalogue-overlay/catalogue-overlay-proposal.json
target/catalogue-overlay/catalogue-overlay-review.md
```

Paths can be overridden without changing the reviewed overlay:

```bash
tools/catalogue-overlay/generate.sh \
  --catalogue path/to/catalogue.xlsx \
  --overlay path/to/reviewed-overlay.json \
  --output target/proposal.json \
  --report target/review.md
```

The generator rejects an output path that resolves to the workbook or reviewed overlay.

## Decision authority

Each proposal states its authority explicitly:

- `REVIEWED_OVERLAY`: `reviewRequired=false`; the current reviewed parent and secondary classifications are locked and are never changed by the generator.
- `AUTOMATED_PROPOSAL`: a deterministic lexical recommendation for a provisional or newly discovered strict-scope node. It is evidence for review, not an accepted classification.

A low-evidence result is marked unresolved instead of inventing a winner.

## Validation and drift detection

Generation fails closed when it detects:

- an unexpected workbook column contract;
- duplicate node codes or UUIDs;
- overlay schema or base-catalogue mismatch;
- source title or state drift;
- unknown, self, cross-root, or cyclic parents;
- invalid or duplicate secondary classifications;
- a node classified as `PRODUCT` that has children.

New strict-scope nodes that have no overlay entry are emitted as `NEW_MAPPING` or `NEW_UNRESOLVED`. They do not cause the generator to mutate the overlay.

## Review and promotion workflow

1. Generate and inspect both artifacts.
2. Review every `REVIEW_REQUIRED_*`, `NEW_*`, and unresolved row, including ranked alternatives and fan-out changes.
3. Apply accepted decisions to a new `mappingVersion` in `nato-taxonomy.json` on a branch.
4. Run the normal runtime overlay validation and full CI matrix.
5. Merge the overlay update only through the normal reviewed Git workflow.

The generator has no promotion mode. This separation is intentional: a proposal cannot silently become production catalogue state.
