# CI – COI Services: grouping and scoring

[Shared scoring contract and implementation scope](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/CI.md)

## Subject and grouping criterion

COI means Community of Interest. The CI prompt addresses community-specific data exchange, shared information services and interoperability. Current grouping follows the source classification. Community membership is not the same as a technical transport interface or a concrete information product.

## Current scoring

CI uses category / parent-budget scoring, without a separate CI formula or independent-product scoring at every leaf. Two service categories may receive 30 each from parent 60; this does not determine how much of either service must be implemented. Source ancestor context and shared root/error limitations apply.

## Multiple matches and consequences

A domain information-access service and a domain exchange service may both be necessary. A high score proves neither an existing interface nor data compatibility. Process, application and information-product relationships require requirement-specific checking, not merely catalogue proximity.

## Target design and limits

Community and service function may be different axes. A service accessible through both remains one candidate; different requirement contributions retain their evidence. Navigation groups must not invent semantic community membership.

Acceptance: jointly required services coexist; community classification does not imply technical interoperability; alternate access does not duplicate services.

## Implementation sources

[CI prompt](../../../taxonomy-analysis/src/main/resources/prompts/CI.txt) · [Assessment calls](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [Relation search model](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/RelationSearchModel.java)
