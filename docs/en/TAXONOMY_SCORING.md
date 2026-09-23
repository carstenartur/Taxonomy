# Grouping and scoring the sub-taxonomies

> **Implementation scope:** source `e73dcfe43964b68aaa6bc7d967471fe209da9a8a` from PR #1118, not an already deployed installation. The separate #1113 parser changes are not part of this baseline. The **binding change requirements** below were clarified on 23 September 2026; documenting them does not prove end-to-end runtime enforcement. This documentation increment changes no code, scores, catalogue data or historical snapshots.

[Deutsch](../de/TAXONOMY_SCORING.md) · [Context and navigation](../dev/hierarchy-context-and-navigation.md) · [Technical score envelope](../dev/ANALYSIS_SCORE_SEMANTICS.md)

## Shared execution, different questions

There are not eight separately implemented scoring algorithms. Ordinary LLM category calls share a parent-budget parser; prompts differ in subject matter. Only entries explicitly classified as `PRODUCT` use independent product scoring. Being a leaf or a user application does not select that contract.

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

## Binding change requirement: preserve the original structure

**Original Excel entries and their arrangement remain authoritative.** Preserve IDs, titles, descriptions, source status, original parent references and source ordering. Do not reorganize sensible existing parent-child relationships or insert new intermediate nodes into them. This applies to original intermediate nodes, leaves and all sub-taxonomies. Source provenance does not turn a draft entry into an approved concept.

**Additions are permitted only at documented IP attachment gaps.** Permitted grounds are a missing/unresolvable parent reference or a specifically evidenced semantically unsuitable parent. Low scores, title-word similarity or a more convenient search tree are insufficient. An unclear case stays unresolved. Preserve sensible relationships inside an affected subtree; supplement only its missing attachment.

Keep additions in a separately versioned navigation layer, never overwriting original `Parent` fields or source order. Original and augmented navigation views must be distinguishable. **One understandable hierarchy is sufficient; multiple routes and facets are not required.** This supersedes earlier general regrouping or mandatory faceted-navigation proposals. Existing overlay assignments also need checking against this narrower scope.

## Local navigation is not an official taxonomy statement

Invented group nodes must be visibly identified as local navigation aids. Provenance and role must be explicit and catalogue-version-bound; lowercase codes and `reviewRequired` are not provenance checks. Internal review cannot give a local group official status.

**For now, architecture relationships with a local group as either source or target are prohibited.** Apply this to AI proposals, manual/API input, persistence/adoption, architecture projection and export. A group must not become an architecture element or an official taxonomy classification of one. Navigation membership is separate: a local branch can lead to original IP entries whose attachment is permitted by the gap rule.

Reject violations visibly. Do not silently hide a relationship or redirect it to an original parent or the first leaf. If only a local group matches, keep the search or need unresolved. Original intermediate nodes remain eligible when their type and requirement evidence fit. Preserve historical evidence; expose conflicts on reuse.

An AI relationship **between original entries** is not an official publisher assertion either. Track concept provenance separately from relationship provenance.

## Current context handling

Source hierarchy, added classification, navigation and architecture relationships are different contracts. Persistence has one primary `parentCode`; the overlay also adds `secondaryClassificationCodes`. PR #1118 retains applicable source-ancestor descriptions but stops automatic inheritance at overlay-assigned parents. Missing parents, cycles and cross-root semantic paths fail explicitly. This does not yet implement the new attachment and endpoint restrictions throughout the application.

## Current category arithmetic: a weight budget

The prompt requires integer scores and reasons summing to parent budget `P`. For positive returned total `S`, the parser proportionally normalizes differences:

```text
w_i = P * r_i / S
Floor values, then assign remaining units to the largest fractional remainders.
P = 60; returned child values 80, 40 -> 40, 20.
```

Values remain unchanged when `S = P`; an all-zero response stays zero. A returned sum above `P` creates a `TaxonomyDiscrepancy`. That sum finding is not the still-missing semantic child/parent consistency check.

Normalization allocates weights. It proves neither fulfilment, necessity, probability, cost share nor mutual exclusion. Multiple processes, roles, services, applications or products can be jointly required; a small allocated share can be indispensable.

**Known root defect:** `analyzeAllTaxonomies` and `analyzeStreaming` send each root separately through this parser with `P = 100`. A positive singleton response `20` becomes `100`; `0` stays `0`. This contradicts intended independent root relevance and still needs correction. Mock values can differ.

## Current IP product arithmetic

`analysisRole=PRODUCT` enables independent suitability against the original requirement. Candidates are code-sorted, assessed in configurable batches of at most ten, then filtered by the default threshold 50: `49, 80, 90` becomes structured `0, 80, 90`. This is not an allocation totalling 100.

```text
Version 1: effective relevance = round(direct parent relevance * suitability / 100)
Parent 40 and suitability 80 -> effective value 32.
```

An unevaluated direct parent yields effective fallback `0` with a warning, not evidence of unsuitability. Multiplication is an existing weighting heuristic, not validated conditional probability. A wrong parent can distort search or the derived value. Mixed sibling sets allocate the parent budget to categories and independently score products; adding these values as a fulfilment total is invalid.

## Suitability, necessity and search priority

Equal high scores can describe jointly needed contributions, alternatives or merely plausible candidates. Contributions, evidence and conditions establish need. Opt-in relationship search distinguishes `REQUIRED`, `OPTIONAL` and `ALTERNATIVE`, but this is not yet a complete product-variant decision mechanism.

**Change target:** Under the same requirement context and genuine semantic containment, branch relevance should be consistent (`child <= parent`). Keep contradictions visible with original evidence rather than hiding them by capping/multiplication. This does not require sibling sums to equal the parent. Local navigation groups may have a distinct search priority, not product relevance. Their insertion must not change an original item's suitability or need. Traversal must allow multiple required branches without requiring multiple paths per entry. Unresolved IP attachment must not silently exclude relevant entries.

## Errors, stored values and limitations

This baseline still inserts zeros for missing category replies and failure placeholders. Interpret zero together with errors, warnings, status and the original reply. #1113 addresses this separately; integration is not assumed here. A below-threshold reply is not an original model zero either.

`LlmCallDetail` and incremental events already contain normalized/filtered values. `AnalysisResult.rawScores` precedes additional product-parent weighting, not every transformation of the provider reply. The original reply, `scoreDetails`, `scoreSemanticsVersion`, warnings and frozen catalogue belong to the evidence. Future migration must preserve original values separately from transformations.

Automatic traversal expands positive categories; products are endpoints. Unvisited does not mean negatively evaluated. Call, prompt and memory limits can leave partial results. Manual values, mock/replay data and local embedding similarity are not interchangeable necessity evidence. Local embeddings bypass natural-language prompts. Prompt overrides do not automatically change the parser contract. This documentation increment fixes none of these runtime limitations.

## Acceptance of the next implementation

Preserve original IDs, content, status, sensible parent relationships and order. Supplement only documented IP gaps; represent each original entry once. Retain inherited BP context. Navigation through a local group works, but local sources, local targets and forged provenance in architecture relationships are rejected. Original intermediate nodes remain eligible when appropriate. Never redirect a prohibited edge. Jointly required products can coexist; alternatives are not adopted together automatically. Missing, explicit-zero and below-threshold replies remain distinguishable. This acceptance is still open, not satisfied by structural or documentation checks alone.

## Implementation sources

[Prompts](../../taxonomy-analysis/src/main/resources/prompts) · [LlmService](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [LlmResponseParser](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [AnalysisScoreSemantics](../../taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java) · [TaxonomyService](../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java) · [IP grouping rules](../../taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java)
