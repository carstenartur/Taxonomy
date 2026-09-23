# CO – Communications Services: grouping and scoring

[Shared scoring contract and implementation scope](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/CO.md)

## Subject and grouping criterion

The CO prompt focuses on voice, data, networks, transmission and communication infrastructure. The source hierarchy remains the baseline. A “communication” application facet must not automatically be equated with the entire CO sub-taxonomy.

## Current scoring

CO uses the shared category / parent-budget parser; the prompt's subject differs from BP or CP, not the arithmetic. Voice and data candidates might receive 20 and 40 from parent 60. These are not bandwidth, availability or reliability percentages. Source context and shared zero/error limitations apply.

## Multiple matches and consequences

Voice and data may both be required. Email, SMS and push are alternatives only when intended as interchangeable realizations of the same contribution. Similar scores do not establish that. A notification requirement does not justify silently choosing its channel.

## Target design and limits

Service function, communication medium and technical realization can be different axes. An email client is an end-user application, not automatically its underlying communication service. Connecting these requires a justified architectural relationship, not an invented taxonomy parent.

Acceptance: jointly required voice and data remain possible; an unspecified channel produces a question or variant; functional navigation does not change original identity.

## Implementation sources

[CO prompt](../../../taxonomy-analysis/src/main/resources/prompts/CO.txt) · [Parser](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [Relation search model](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/RelationSearchModel.java)
