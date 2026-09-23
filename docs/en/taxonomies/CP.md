# CP – Capabilities: grouping and scoring

[Shared scoring contract and implementation scope](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/CP.md)

## Subject and grouping criterion

CP addresses functional abilities, capacities and capability needs or gaps. Grouping follows the source catalogue. A capability describes what should become possible; it does not alone select a process, service or software product. A tree edge is not a realization relationship.

## Current scoring

CP shares BP's category / parent-budget calculation. Two equally weighted children may receive 30 each from parent 60 even when both are indispensable. Own and applicable source-ancestor descriptions provide LLM context. Shared root, missing-answer and rounding limitations apply.

## Multiple matches and consequences

A requirement can need both capture and evaluation capabilities. Joint need is not competition for a score budget. Conversely, a plausible general capability proves neither an existing implementation nor full coverage of every subordinate capability. Concrete contributions and relationships are needed.

## Target design and limits

Capability decomposition, application domain and technical realization must remain distinct. Multiple classification paths must not duplicate capability need. Child/parent consistency requires the same measure and scope; a sum contract does not establish that.

Acceptance: jointly required capabilities coexist; a software product is not conflated with its capability; realization edges require actual justified proposals.

## Implementation sources

[CP prompt](../../../taxonomy-analysis/src/main/resources/prompts/CP.txt) · [Parser](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [Relation search model](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/RelationSearchModel.java)
