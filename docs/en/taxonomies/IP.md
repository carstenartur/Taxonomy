# IP – Information Products: grouping and scoring

[Shared scoring contract and implementation scope](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/IP.md)

## Subject and existing hierarchy

IP means information products such as reports, plans and information artefacts that are read or produced, not software products generally. The source has 1,071 IP entries. The existing overlay assigns 866 entries, including 853 classified as `PRODUCT`. These are structural audit counts, not expert semantic approvals. The runtime tree has one primary parent; secondary classification codes are metadata. #1118 does not treat overlay assignment as evidence of inherited source semantics.

## Exact criterion for the five new proposals

**English words in the original title, and nothing more.** The generator selects original entries marked `PRODUCT` by the overlay. It lowercases titles with `Locale.ROOT`, splits at characters that are neither Unicode letters nor digits, and looks for an exact match with at least one listed word. Full descriptions remain in the audit but do not participate in grouping. This uses no LLM, embedding or semantic-subordination assessment.

| Proposed access point | Exact words | References in inspected catalogue |
|---|---|---:|
| Hazard / warning information | `hazard`, `hazards`, `warning`, `warnings` | 26 |
| Reports | `report`, `reports` | 222 |
| Plans | `plan`, `plans` | 61 |
| Requests | `request`, `requests` | 48 |
| Orders | `order`, `orders` | 50 |

These rules mix subject/purpose with artefact form. They are **not one consistent semantic decomposition**, exhaustive product coverage or a completed IP tree. A warning report can match several groups. A synonym without a listed word is missed, and accidental word matches can misclassify. Counts can overlap and must not be summed as disjoint coverage. A group with 222 reports is not yet a bounded model candidate batch.

Each proposal is `NAVIGATION_GROUP`, `reviewRequired=true`, `affectsScores=false`, `inheritsSemantics=false` and `createsArchitectureElement=false`. Stable IDs start with `local:ip:navigation:`; `ip-reports` is a display code only. `parentId=IP` in proposal JSON does not insert nodes into the runtime tree. Original IDs, descriptions and active parents are unchanged.

## Current scoring

Families and categories use parent-budget allocation. Concrete `PRODUCT` entries receive independent suitability scores against the original requirement in batches of at most ten. The default threshold 50 converts lower values to structured zeros. Multiple products may score highly together; their total need not equal 100 or the family value.

Downstream weighting remains `round(parent relevance * suitability / 100)`: 40 and 80 produce 32. This is not justified conditional probability arithmetic and the new navigation groups do not repair it. The shared contract explains missing-parent fallbacks, failure zeros and original-response versus stored/effective values. Only completed successful product assessment without a suitable candidate can establish a confirmed catalogue coverage-gap finding.

## Joint need versus alternatives

Hazard reporting and action tracking might jointly require a hazard record, action plan and effectiveness evidence. Two reporting forms could instead be alternatives for one contribution. Suitability alone proves neither joint necessity nor interchangeability. Requirement contribution, access mode and conditions must justify the decision. These examples invent no original C3 IDs.

## Target design: explicit facets rather than mixed branches

Subject, purpose and artefact form should have separate definitions. The same warning report can appear under “Subject → Hazards” and “Artefact form → Report”. Groups need inclusion/exclusion criteria; membership should use title and description with provenance. Retain original and semantically reviewed relationships. Do not silently swap semantic containment and navigation-only membership.

Alternate access references one original identity. It must not duplicate counts, multiply different parent scores or cause unnecessary repeated model assessments. Different requirement contributions still retain separate evidence. A low-scoring provisional route must not block all other access. Faceted runtime navigation and coordinated score migration remain open in #1111.

Acceptance: one product through two facets remains one object; jointly needed products coexist; alternatives are not both adopted automatically; navigation-only regrouping does not change suitability; semantic classifications use reviewable definitions instead of title-only rules.

## Implementation sources

[Grouping and audit](../../../taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java) · [Overlay](../../../taxonomy-knowledge/src/main/resources/data/nato-taxonomy.json) · [Family prompt](../../../taxonomy-analysis/src/main/resources/prompts/IP.txt) · [Product prompt](../../../taxonomy-analysis/src/main/resources/prompts/IP-product.txt) · [Derived values](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java)
