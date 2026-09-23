# Grouping and scoring the sub-taxonomies

> **Implementation scope:** 23 September 2026, source `e73dcfe43964b68aaa6bc7d967471fe209da9a8a` from PR #1118. This describes that source, not an already deployed installation. The separate parser changes in #1113 are not part of this baseline. Sections marked **Target design** are not implemented capabilities. Documentation changes do not change scores or catalogue data.

[Deutsch](../de/TAXONOMY_SCORING.md) · [Context and navigation](../dev/hierarchy-context-and-navigation.md) · [Technical score envelope](../dev/ANALYSIS_SCORE_SEMANTICS.md)

## Shared execution, different questions

There are not eight independently implemented scoring algorithms. Ordinary LLM category calls share the parent-budget parser; their prompts differ in subject matter. Only entries explicitly classified as `PRODUCT` by metadata take the independent-product path. Being a leaf, a user application or a software product in everyday language does not activate that path.

| Page | Subject | Current ordinary path |
|---|---|---|
| [BP – Business Processes](taxonomies/BP.md) | Required activities and workflows | Category / parent budget |
| [BR – Business Roles](taxonomies/BR.md) | Actors and responsibilities | Category / parent budget |
| [CP – Capabilities](taxonomies/CP.md) | Required abilities | Category / parent budget |
| [CI – COI Services](taxonomies/CI.md) | Community-/domain-specific services | Category / parent budget |
| [CO – Communications Services](taxonomies/CO.md) | Communication and transmission services | Category / parent budget |
| [CR – Core Services](taxonomies/CR.md) | Foundational IT services | Category / parent budget |
| [IP – Information Products](taxonomies/IP.md) | Information produced, read or changed | Families: category; `PRODUCT`: independent suitability |
| [UA – User Applications](taxonomies/UA.md) | End-user application functions | Category / parent budget |

## What does grouping mean?

Source hierarchy, added semantic classification, navigation grouping and architectural relationships are different contracts. Current persistence has one primary `parentCode`. The IP overlay adds that assignment and optional `secondaryClassificationCodes`; these do not yet implement a complete multidimensional search tree.

PR #1118 keeps source ancestor descriptions in assessment context but stops automatic inheritance at an overlay-assigned parent. Source provenance is inspectable, not proof that every source edge is semantically correct. Broken, cyclic and cross-root semantic paths fail explicitly. Deterministic `reviewRequired=false` classification is not expert semantic approval.

**Target design: distinct classification axes.** Function, subject domain and artefact/product type can be separate access paths. An email client can appear under “Function → Communication” and “Application type → Client”, referring to one original identity. These illustrative labels do not invent C3 catalogue codes. The communication facet is not automatically the CO sub-taxonomy, and an email client is not an IP information product merely because software is called a product.

Membership needs facet, group identity, original identity, relationship kind, provenance, scope and review state. Two genuinely applicable semantic superclasses may both constrain meaning. Alternative navigation routes must not instead be merged into an invented AND-context. Duplicate access must not duplicate nodes, coverage counts or model calls. Different requirement contributions must nevertheless retain separate evidence.

## Current category arithmetic: allocation of a weight budget

The prompt asks for integer scores and reasons summing to parent value `P`. For parsed nonnegative values `r_i` with positive total `S`, `LlmResponseParser` rescales when that total differs:

```text
w_i = P * r_i / S
Floor to integers, then assign remaining units to the largest fractional
remainders (largest-remainder method).
```

Values are unchanged when `S = P`. An all-zero reply stays zero rather than manufacturing positive evidence. Example: `P = 60`, returned values `80, 40` produce `40, 20`. A raw total above the budget already creates a `TaxonomyDiscrepancy`; this sum discrepancy is not the still-missing semantic child/parent consistency check.

**Rationale and limit:** This implements hierarchical weight allocation, not demonstrated fulfilment, necessity, probability, cost share or mutual exclusion. Multiple processes, roles, capabilities, services and applications can all be required. A small allocated share can still represent an indispensable contribution. Adding required siblings can mechanically reduce existing siblings' shares.

### Root special case: the implementation differs from its stated intention

`analyzeAllTaxonomies` and `analyzeStreaming` assess roots separately with `P = 100`. Comments describe independent branch relevance. The ordinary category parser also normalizes this singleton, however: a returned `20` becomes `100`, whereas `0` stays `0`. Roots do not share one cross-root budget, but an individual positive model value is not retained in this path. This is a limitation to fix, not a recommended semantic rule. Mock data may show different root values.

## Current IP product arithmetic: suitability and a derived value

Only `analysisRole=PRODUCT` selects product scoring. Candidates are code-sorted and independently assessed against the original requirement, with 0–100 scores and reasons, in configurable batches of at most ten. The default minimum is 50. Returned values below it become zero in the structured result: `49, 80, 90` become `0, 80, 90`, not an allocation summing to 100.

Generic downstream consumers still receive this version-1 `AnalysisScoreSemantics` heuristic:

```text
effective relevance = round(direct parent relevance * product suitability / 100)
Example: parent 40, suitability 80 -> effective value 32.
```

Without an evaluated direct parent, effective relevance is zero with a warning. That fallback is not evidence of unsuitability. The product prompt asks about the complete requirement; it does not expressly estimate a conditional probability. Multiplication is therefore an existing weighting heuristic, not validated probability arithmetic. A wrong parent can prevent assessment or unjustifiably reduce the derived value. #1118 does not change this formula.

In mixed sibling sets, categories receive the parent budget while products receive independent suitability scores. Adding those different values into one supposed fulfilment total is invalid.

## Suitability is not necessity

Two scores of 90 might concern two jointly required products, two interchangeable options or two merely plausible candidates. The number alone cannot distinguish them. Concrete requirement contributions and checked relationships must explain actual need. Opt-in relation search distinguishes `REQUIRED`, `OPTIONAL` and `ALTERNATIVE`; its navigation and verification outcomes are not percentage shares. An alternative relationship is also not a complete product-variant adoption mechanism.

**Target design:** Branch relevance under the same requirement context and genuine semantic containment should satisfy `child <= parent`. Contradictions must retain original evidence and lead to an inspectable consistency finding, not silent capping or multiplication. This does not require siblings to sum to the parent. Pure navigation regrouping must not change an original item's suitability or necessity. Navigation-group search priority, when introduced, is a different value from architecture-element relevance.

## Errors, partial results and stored values

The #1118 baseline still fills missing category entries with zeros and uses zero placeholders in failure paths. Read a zero together with call errors, warnings, status and the original reply. #1113 addresses parts of this separately and is not assumed integrated here. A product-threshold zero is not an original model zero either.

`LlmCallDetail` and incremental score events carry values after parser normalization or thresholding. `AnalysisResult.rawScores` is before additional product-parent weighting, not necessarily before all transformations of the provider reply. The full response in the LLM log is the relevant evidence for that distinction. `scoreDetails`, `scoreSemanticsVersion`, warnings and the frozen catalogue retain the interpretation needed for a snapshot.

The inspected automatic traversal expands positive category entries; products are endpoints. An unvisited item is not thereby negatively assessed. Call, prompt and memory budgets and failures can leave partial results. Manual values, mock/replay data and local embedding similarity are not interchangeable evidence of model-justified need. Local embeddings bypass natural-language scoring prompts; the context fix does not prove identical embedding-context handling. Prompt overrides can change a question without changing its parser contract.

## Coordinated follow-up and acceptance

Work in #1111 must separate root relevance, branch relevance, allocated weight, product suitability and necessity; fix singleton-root normalization; retain original values and transformations in a versioned envelope; validate multidimensional access using semantic definitions; and avoid making a doubtful parent the only access path.

Acceptance examples: BP meaning defined only in an ancestor remains available. Two jointly required products can coexist. Two alternatives are not automatically adopted together. One product found through two facets remains one object. Navigation-only regrouping does not change its assessment. A same-measure child/parent contradiction is explained. Missing, explicitly zero and below-threshold replies remain distinguishable.

## Implementation sources

[Prompts](../../taxonomy-analysis/src/main/resources/prompts) · [LlmService](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [LlmResponseParser](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [AnalysisScoreSemantics](../../taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java) · [TaxonomyService](../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java) · [IP grouping rules](../../taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java)
