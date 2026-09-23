# IP – Information Products: grouping and scoring

[Shared scoring contract and binding source constraints](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/IP.md)

## Original structure and limited additions

IP means information products, not software products generally. The audited source contains 1,071 IP entries; the existing overlay assigns 866 entries, including 853 as `PRODUCT`. These are structural counts, not expert approvals. Runtime navigation uses one primary parent; additional classification codes are metadata.

**Binding change requirement:** Preserve all original nodes, descriptions, IDs, source status and original arrangement. Do not change sensible parent relationships or source ordering. New groups are allowed only at documented IP attachment gaps, not for wholesale regrouping. Low AI scores do not prove a bad parent. Existing overlay assignments must also be checked against this narrower permission.

Additions are separately versioned navigation, not replacements for original parent fields or ordering. Preserve sensible relationships within an affected subtree. One understandable hierarchy is sufficient; multiple facets are not required. This supersedes the earlier general faceted-regrouping target. Identifying and attaching all eligible cases remains open.

## Criterion used by the five existing proposals

The generator uses **English words in original titles only**, selecting entries marked `PRODUCT` by the overlay. It lowercases with `Locale.ROOT`, splits on characters other than Unicode letters/digits, and matches exact tokens. Descriptions remain in the audit but do not determine grouping. No AI or semantic-subordination assessment is performed.

| Proposal | Exact words | References in inspected catalogue |
|---|---|---:|
| Hazard / warning information | `hazard`, `hazards`, `warning`, `warnings` | 26 |
| Reports | `report`, `reports` | 222 |
| Plans | `plan`, `plans` | 61 |
| Requests | `request`, `requests` | 48 |
| Orders | `order`, `orders` | 50 |

These rules mix subject/purpose and artefact form. They are neither a consistent semantic decomposition nor exhaustive. Word matches can mislead; synonyms can be missed. Counts overlap and are not disjoint coverage. A 222-report group is not a bounded model candidate batch. The rules **do not yet enforce** the newly required documented-IP-gap restriction.

All proposals remain unapproved `NAVIGATION_GROUP` with `reviewRequired=true`, `affectsScores=false`, `inheritsSemantics=false` and `createsArchitectureElement=false`. Identities start with `local:ip:navigation:`; `ip-reports` is a display code only. They are not inserted into the active tree and do not authorize rearranging correctly placed original entries.

## Navigation allowed, architecture relationships prohibited

**For now, no local group may be the source or target of an architecture relationship**, even after internal review. Groups are neither required information products nor official classifications of architecture elements. A central provenance/role check must enforce this for AI, API/manual input, persistence/adoption, projection and export. Proposal metadata alone does not demonstrate that enforcement.

Parent-child membership in augmented navigation remains necessary and permitted. A local branch leads to original entries; a justified relationship ends at an appropriate **original** leaf or intermediate node. A group match alone proves no edge. Do not redirect it to the nearest original parent or an arbitrary leaf; leave unresolved target search open. Do not infer provenance from letter case or trust provenance supplied by an API caller.

Concept provenance and proposed-relationship provenance remain separate: an AI edge between original IP entries is not an official publisher assertion either.

## Current scoring and remaining limitations

Families/categories use the parent budget. Concrete `PRODUCT` entries receive independent suitability against the original requirement in batches of at most ten. The default threshold 50 turns lower values into structured zeros. Multiple products can score highly at once; their sum need not equal the family score.

Downstream weighting remains `round(parent relevance * suitability / 100)`: 40 and 80 produce 32. The heuristic is not validated conditional probability. The shared contract explains failure zeros, absent parent scores, root normalization, and original-response versus stored/effective values. Only complete successful assessment without a suitable candidate may establish a confirmed catalogue coverage-gap finding. #1118 does not change these formulas.

A hazard record, action plan and effectiveness evidence can be jointly needed; other reporting forms can be alternatives for one contribution. Suitability does not distinguish these cases. Concrete contribution, access and conditions must justify need and relationships.

## Acceptance

Preserve original identity, content, status, sensible parent relationships and order. Attach only documented IP gaps; lose or duplicate no original entry. A local navigation level changes neither suitability nor necessity. A matching local branch remains navigable, but architectural edges to it are visibly rejected. Original intermediate nodes remain possible; jointly required products coexist. Unresolved attachment must not silently exclude matching original entries. Semantic placement, active navigation, endpoint enforcement and coordinated scoring changes remain open in #1111.

## Implementation sources

[Grouping and audit](../../../taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java) · [Overlay](../../../taxonomy-knowledge/src/main/resources/data/nato-taxonomy.json) · [Family prompt](../../../taxonomy-analysis/src/main/resources/prompts/IP.txt) · [Product prompt](../../../taxonomy-analysis/src/main/resources/prompts/IP-product.txt) · [Derived values](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java)
