# BR – Business Roles: grouping and scoring

[Shared scoring contract and implementation scope](../TAXONOMY_SCORING.md) · [Deutsch](../../de/taxonomies/BR.md)

## Subject and grouping criterion

BR means Business Roles, not Business Rules. The prompt considers actors, stakeholders, organizational units and responsibilities described or implied by the requirement. A catalogue role is neither a particular person nor an automatically granted permission. Current hierarchy comes from the source catalogue; no new BR groups are generated.

## Current scoring

BR uses category / parent-budget scoring, not independent product suitability. Two roles may receive 30 each from parent 60. These numbers measure neither staffing nor authority. Shared normalization, zero, root and failure limitations apply. Original ancestor descriptions remain in LLM context.

## Multiple matches and consequences

Reporting, assessing and authorizing roles can all be required. Multiple matches prove neither that one person may hold all roles nor that separation of duties is required. Those decisions need requirement evidence or explicit design decisions. Role classification does not grant application permissions.

## Target design and limits

Functional role, organizational membership and permission are potentially different axes. Additional access must be typed; moving a display group must not create a new role identity. Branch relevance and allocated weight need separate interpretation. Today's sum contract is not justified as a measure of necessity.

Acceptance: multiple required responsibilities coexist; low weight does not mean optional; unresolved responsibility or permission separation produces a question rather than an invented policy.

## Implementation sources

[BR prompt](../../../taxonomy-analysis/src/main/resources/prompts/BR.txt) · [Category/product routing](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [Shared context](../../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java)
