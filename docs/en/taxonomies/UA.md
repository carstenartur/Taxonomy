# UA – User Applications: grouping and scoring

[Shared scoring contract and implementation scope](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/UA.md)

## Subject and grouping criterion

The UA prompt addresses end-user tools, operational applications and interfaces. Current grouping follows the source catalogue. An application class is not automatically a vendor product, deployed instance or information product. Metadata `PRODUCT`, not the everyday term software product, selects independent IP scoring.

## Current scoring

UA categories share a parent budget. Communication and evaluation functions might receive 40 and 20 from parent 60. These weights neither count required programs nor prove that two separate applications must exist. Own and applicable source ancestor descriptions remain available. Shared rounding, root and error limits apply.

## Email client through several routes

**Target design, not an implemented multiple hierarchy:** “Function → Communication → Email client” and “Application type → Client → Email client” are different views of the same original entry. The first classifies purpose, the second application type. Groups must name their axis. Membership creates neither a second application nor a second license obligation and is not an architecture relationship between components.

A client may provide email and calendar functions. Only its required contribution should be assessed for a particular requirement. Email suitability does not demand every additional feature. A jointly needed planning application and an alternative email client are different necessity situations; similar scores do not distinguish them.

## Target design and limits

Function, application type and operating model stay separate. Different deployed instances sharing one classification must not be merged; different access paths to one catalogue entry must not duplicate it. Current single-parent navigation and category arithmetic do not yet fulfil this target completely.

Acceptance: two routes preserve one catalogue identity; requirement contributions retain their reasons; jointly needed applications coexist; alternatives are not adopted together without a decision.

## Implementation sources

[UA prompt](../../../taxonomy-analysis/src/main/resources/prompts/UA.txt) · [Category/product routing](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [Catalogue model](../../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/model/TaxonomyNode.java)
