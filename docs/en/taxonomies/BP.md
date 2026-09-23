# BP – Business Processes: grouping and scoring

[Shared scoring contract and implementation scope](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/BP.md)

## Subject and grouping criterion

The BP prompt focuses on activities, operational workflows, execution steps and procedures. Original catalogue entries and parent relationships are the baseline. New IP navigation proposals do not alter BP. A classification edge does not itself mean execution order or a complete process decomposition.

## Current scoring

BP uses category / parent-budget scoring. With parent 60, returned weights 80 and 40 produce 40 and 20. This is not a claim that the second activity is only partly needed. The shared singleton-root normalization limitation applies.

Assessment includes original descriptions and applicable source ancestor descriptions. A parent defining “security incident handling” supplies the context for a briefly named child “perform assessment”. These labels are illustrative, not new original C3 identities. Shared ancestor text need not be repeated for each sibling.

## Multiple matches and consequences

Hazard reporting, action planning and effectiveness verification can all be necessary. BP is not inherently an exclusive choice in contrast to products. Small weight does not imply optionality. Sequence, role assignment and information access each require their own justified relationships.

## Target design and limits

Genuine semantic containment with the same measure should yield consistent branch relevance. A child above its parent calls for reviewing the parent assessment, context or classification, not just lowering the child. Navigation-only groups must not change meaning. This is not yet a complete consistency checker or replacement for today's sum contract.

Acceptance: ancestor-only scope reaches children; jointly needed activities are not treated as alternatives; display regrouping does not change need.

## Implementation sources

[BP prompt](../../../taxonomy-analysis/src/main/resources/prompts/BP.txt) · [Parser](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [Shared context](../../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java)
