# CR – Core Services: grouping and scoring

[Shared scoring contract and implementation scope](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/CR.md)

## Subject and grouping criterion

The CR prompt considers foundational IT/C2 services, infrastructure and platform functions. Current grouping follows the source catalogue. A foundational service is not a hardware order, and a leaf is not automatically a concrete product selection.

## Current scoring

CR uses category / parent-budget scoring, not a dedicated infrastructure formula. Two children scoring 30 from parent 60 share weight; these are not availability values, costs or quantities. Own and applicable source-ancestor descriptions remain available. Shared normalization and failure limits apply.

## Multiple matches and consequences

Identity checking, storage and information access can all be required. A service may become necessary through a justified dependency although its name is absent from the original text. Such need must be labeled derived rather than rewritten as an explicit original requirement. A low direct score does not prove a dependency unnecessary.

## Target design and limits

Platform function, operating model and technology can be separate axes. Uncertain grouping must not silently select technology or deployment. Multiple routes to one service must not create multiple implementation obligations.

Acceptance: jointly needed foundational services coexist; derived need retains its rationale; technology navigation preserves original identity.

## Implementation sources

[CR prompt](../../../taxonomy-analysis/src/main/resources/prompts/CR.txt) · [Assessment calls](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [Relation search model](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/RelationSearchModel.java)
