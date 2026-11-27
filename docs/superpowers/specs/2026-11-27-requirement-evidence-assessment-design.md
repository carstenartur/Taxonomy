# Requirement contribution and evidence assessment

## Intent and authority

Carsten approved implementation of the preceding design discussion on 27 November 2026, including a real guided application session through a self-provided vLLM-compatible endpoint and correction of observed defects. The implementation extends the existing analysis, snapshot, reformulation and review flows. It does not introduce a second analysis engine.

The user needs to distinguish the object and kind of each requirement statement, its contribution to a catalogue node, the evidence supporting that contribution, and human adoption. A product can directly automate a process activity or indirectly support a different business process. Neither relation kind determines confidence.

## Chosen approach

1. Prompt wording alone was considered but cannot retain or reliably display semantics.
2. **Chosen:** enrich existing scoring exchanges with validated, source-bound node assessments; carry them through existing local/streaming/distributed results and snapshots; reuse them for presentation and reformulation. This adds no compulsory model round trip.
3. A separate ontology/reasoning service was considered and is unnecessary for this bounded change.

The preceding chat recommendation is the approved design authority. These engineering details are implementation decisions, not additional claims of user approval.

## Semantics

Add an immutable `RequirementNodeAssessment` DTO. Each catalogue node may carry multiple assessments for different original-text passages. Use these exact fields and enum values:

- `requirementQuote`: verbatim substring of the exact analysed requirement; empty only for an unresolved or neutral assessment that asserts no fact.
- `subjectKinds`: nonempty list of `PRODUCT`, `SERVICE`, `PROCESS`, `INFORMATION`, `ROLE`, `CAPABILITY`, `UNSPECIFIED`.
- `requirementKinds`: nonempty list of `FUNCTIONAL`, `QUALITY`, `CONSTRAINT`, `ORGANIZATIONAL`, `CONTEXT`, `GOAL`, `UNSPECIFIED`.
- `contributionType`: `DIRECT_SUBJECT`, `PROVIDES`, `SUPPORTS`, `REQUIRES`, `CONSTRAINS`, `UNDETERMINED`.
- `evidenceStatus`: `SUPPORTED`, `UNRESOLVED`, `CONFLICT`, `NOT_APPLICABLE`, `OUT_OF_SCOPE`.
- `evidenceBasis`: `EXPLICIT`, `INFERRED`, `UNSPECIFIED`.
- `necessity`: `REQUIRED`, `OPTIONAL`, `ALTERNATIVE`, `CONTEXT`, `UNDETERMINED`.
- `scope`: `DELIVERY`, `EXISTING_ENVIRONMENT`, `UNSPECIFIED`, `EXCLUDED`.
- `condition`, `rationale`, `question`: bounded strings; rationale must be nonblank.

`SUPPORTED` needs a nonempty original quote, determined contribution and evidence basis, and no open question. `UNRESOLVED` needs a concrete question. `CONFLICT` needs original evidence and an explanation and must not be inferred from a low numeric score. Neutral states have no open question. Optional/alternative positive claims need their condition. Existing infrastructure remains distinct from newly requested delivery. Enum lists are deduplicated and immutable; duplicates in provider input and unknown fields/IDs fail validation. Limits: at most 8 assessments per node, quote at most 8000 characters, rationale 2000, question/condition 1000 each.

Source validation checks quotes against the original input, never a catalogue description, translation or generated summary. An assertion's exact quote and explanation are different fields. Validation establishes source integrity, not semantic truth.

Canonical wire property: `nodeAssessments`, `Map<String,List<RequirementNodeAssessment>>`, on `LlmCallDetail` and `AnalysisResult`. Scoring responses add `assessments` to each existing node value. Legacy score/reason replies remain readable but produce no fabricated semantic assessment. Absent metadata is displayed as not assessed. Mock/embedding/manual values do not receive invented metadata.

Requirement aspects can be displayed as the deduplicated subject/requirement kinds of the recorded passages. They belong to the saved analysis/source version. They do not silently rewrite the portfolio's existing editable requirement type.

## Scores, relationships and review

Existing numerical scoring is retained as its versioned ranking/allocation contract, visibly separate from semantic status and necessity. No new probability or fulfilment percentage is asserted. Several assessed contributions may all be REQUIRED regardless of budget shares. A supported indirect relationship may be green; an unresolved direct match remains amber. Do not introduce thresholds such as 80/50 for this status.

The semantic mapping is requirement-to-catalogue evidence. Existing directed architecture relationships retain their own types, conditions, verification and human adoption. Node metadata never proves an edge. Relationship and reformulation prompts must preserve this distinction and may not turn source context or generated hypotheses into new requirements.

Traffic status is derived: conflict red, unresolved amber, supported green, neutral/absent grey with distinct labels. Mixed nodes retain their individual claims and expose the most actionable status. Always show type and text alongside colour. Green remains an AI proposal until the existing human review accepts it. No silent acceptance, rewriting or automatic reanalysis is added.

## Integration and history

Carry the map through normal and streaming scoring, child calls, durable recovery, distributed aggregation and public result serialization. Do not depend on truncated raw transcripts to reconstruct it. Snapshots and exports retain the recorded map and catalogue/source version. Old snapshots remain neutral and are not reinterpreted. New text causes a new analysis; existing source identity checks govern reuse. Prompt-policy identity changes invalidate incompatible cached exchanges.

Show assessments in the main analysis workflow and saved requirement detail using a shared renderer, with original quote, contribution type, source kinds, status, conditionality and question. Include the evidence in relevant JSON/HTML/Word report paths. Expose assessed versus unassessed scope and keep score labels separate. Reformulation receives recorded assessments as interpretations, retains original text and explicit adoption boundary, and preserves questions.

## Independent example and evidence

Use the Sächsisches Krankenhaus Altscherbitz public procurement notice for modernization of the backup infrastructure, procedure 230039, notice `33fd4d73-a507-4d18-b4b7-aa0aa1cc8e20`, version 01, transmitted on 10 November 2026. The selected source text is section 2.1 on PDF pages 1–3 and the scope summary in section 5.1 on PDF page 4. The PDF identifies itself as a non-binding eForms rendition of the notice. The full procurement specification, award and deployed solution are outside this example. [Official public notice](https://oeffentlichevergabe.de/api/notices/33fd4d73-a507-4d18-b4b7-aa0aa1cc8e20?format=pdf)

Start with the existing VEEAM, NetApp and tape environment and its specified modernization. The first stage covers the VEEAM license extension, additional NetApp storage and replacement tape library. The second stage adds the second site, snapshot and replication mechanisms, integration and the tape library's media break and long-term retention purpose. The third stage adds consideration of existing backup jobs, policies and operating processes, optimization when needed in consultation with the buyer, and planning through acceptance support and commissioning. Backup frequency, quantitative retention periods, recovery targets, authorization roles and acceptance criteria remain open in this source.

Freeze the source facts, catalogue digest and project-authored taxonomy expectations in the scenario reference before live model responses. Evaluate source fidelity separately from the authored mapping expectations. Use three staged requirements and preserve original and adopted versions. Retain prompts, fresh completion IDs, raw replies, model/relay identity, exact source revision and screenshots. Describe session-generated responses delivered over HTTP as coming from a vLLM-compatible assistant relay. Reserve claims about vLLM runtime, deployed small-model performance and live inference for measurements that actually establish them.

## Constraints and acceptance

- Java 25, Maven Wrapper and existing JUnit/browser infrastructure; no new GitHub-only workflow or runtime service dependency.
- Existing source and other sessions' work are preserved; isolated branch starts at `724f4111be87feb93682a928496bc8fda37d4bbf`.
- Tests cover malformed/invented quotes, legacy absence, indirect-supported versus direct-unresolved, mixed statements, conditions, neutral states, streaming and durable round trips, frozen history and no human auto-adoption.
- The live example uses ordinary application HTTP/UI boundaries. Inspect desktop and narrow layouts and actual report output. Fix observed functional and visual defects with focused regressions.
- Measure and report actual outcomes; distinguish source integrity, software contracts and human semantic review. No universal accuracy, calibrated confidence, runtime superiority or compliance certification claims.
