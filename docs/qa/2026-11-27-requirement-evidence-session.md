# Requirement contribution and evidence — source-backed live session

## Scope and reference

The case is the Sächsisches Krankenhaus Altscherbitz procurement notice, procedure `230039`, notice `33fd4d73-a507-4d18-b4b7-aa0aa1cc8e20`, version `01`, transmitted on 10 November 2026. The [official PDF](https://oeffentlichevergabe.de/api/notices/33fd4d73-a507-4d18-b4b7-aa0aa1cc8e20?format=pdf) was retrieved and visually checked on 27 November 2026. It is an eForms rendition of the published notice, not the full binding procurement specification or evidence of the deployed system.

The reference and independently authored mapping expectations were committed in `c1929b7b` before live model inference. The fixture is `taxonomy-app/src/test/resources/scenarios/altscherbitz-backup-reference-v1.json`. The original PDF digest is `f271b93870c4dc44b73f6096544650af478884728c6859ef9d2525601eddcccf`. The original bilingual catalogue workbook digest is `000b695bcd09a2ec7aff889cf62b2f83929861a3f147eaa3c2b60e2bf77b7f9d`.

The three authored intake stages expose progressively more of the published requirement: product delivery, storage functions across two sites, then operating processes and implementation/acceptance support. Existing installed products remain distinct from requested extensions and replacements. The condition on optimizing existing processes remains explicit. Quantitative backup schedules, retention periods, recovery objectives and detailed acceptance criteria are not established by this selected notice.

The complete original catalogue is loaded with the existing versioned Information Products overlay (`information-products-2026-08-28-v1`, SHA-256 `f82b7d614d00700760bb009ece94949080148d244974b2363037bed3d3b10d3e`). Its 866 patches concern only IP nodes; the selected BP/CR/UA nodes are not patched. The first startup attempt had incorrectly disabled this required repair and failed at the workbook’s unresolved IP-1018 → IP-1008 parent, before any inference. The harness configuration was corrected, and the overlay bytes and restricted patch scope are asserted. The original workbook and frozen mapping expectations were not changed. The session selects Business Processes, Core Services and User Applications with `TAXONOMIES_ONLY`. It assesses contributions to taxonomy nodes. It does not claim to verify architecture edges or the correctness of a procurement decision.

## Endpoint and independence

The endpoint is the Java [interactive completion relay](../dev/assistant-completion-relay.md), using the ordinary `CUSTOM_OPENAI` gateway, with mock and replay modes disabled. A separate assistant receives the actual queued provider requests and supplies fresh answers. It receives neither the reference oracle nor evaluation results. The transport has the OpenAI/vLLM-compatible chat-completion shape; this run is an assistant-relay session, not a vLLM inference-engine or small-model benchmark.

The relay records the exact request, supplied response, delivered completion envelope and terminal receipt. Completion IDs are fresh and byte hashes identify each captured artifact. Token usage is not fabricated. These records establish transport provenance; they do not establish semantic truth or calibrated confidence.

## Guided workflow

`RequirementEvidenceLiveSessionTest` uses ordinary authenticated HTTP endpoints and a real browser. It creates a project and draft requirement, queues analysis, inspects the immutable snapshot and exports it, opens the existing clarification questionnaire, waits for an operator review, previews and explicitly adopts the next draft, and separately queues its analysis. Earlier snapshots are compared after the final version. The test also checks that the analysis has not changed the editable requirement type or silently confirmed the requirement.

At each review checkpoint, the run directory contains `<stage>-review.json` with the actual proposal and recorded questions. The operator writes `<stage>-decision.json` atomically. Its structure is:

```json
{
  "proposalId": "copy the exact proposal identifier",
  "revision": 1,
  "answers": [
    {
      "questionId": "copy the exact recorded question identifier",
      "action": "DEFER",
      "values": [],
      "otherText": "",
      "rationale": "The selected public source does not establish this detail."
    }
  ],
  "nextText": "the next reviewed source-backed requirement draft",
  "rationale": "Source-backed clarification for this demonstration; unresolved details remain open."
}
```

`ANSWER` is used only after the actual question has been compared with the source. The decision file is operator input to the normal revision/adoption APIs, not a provider answer or model playback. Unanswered details retain their recorded state and any adoption warnings remain visible.

After the exact adoption preview has been written, a second operator file, `<stage>-adoption-confirmation.json`, names its `previewId` and `previewHash` and supplies `confirmed`, `acknowledgeWarnings` and the adoption `rationale`. This confirmation is reviewed after the preview exists. Each original question receives an explicit disposition. A new session requires an empty output directory. Explicit recovery entry points can reopen the same retained session only after checking its source reference, model, phase, server snapshots and original delivery receipts. Recovery is labeled separately and never counted as new inference.

## Evaluation boundaries

The independent evaluator checks exact quote membership, recorded metadata, expected subject aspects, the authored alternative mapping groups and unsupported mandatory delivery claims. Its regression tests reject absent evidence and invented quotations. A separate reading of the actual interpretations checks source meaning, conditions and remaining uncertainty. A valid quotation alone does not prove that the interpretation is correct.

`checksPassed` describes only those authored checks. `INCONCLUSIVE_COVERAGE` remains explicit for a partial analysis even when its observed claims meet the checks. The revised global prompt teaches how conditional obligations differ from discretionary choices; correct handling of that distinction demonstrates instruction-following. It is not evidence of independently discovering the contract rule.

## Initial live results and actual decisions

The initial three-version session delivered **50 fresh completion responses**, distributed 8 / 17 / 25. All accepted requests have delivered receipts; no request is satisfied by a previous response. The responder read the actual requests without the oracle. Two earlier aborted first-stage runs each supplied eight additional real responses; these are failed-attempt evidence, not part of the initial 50-call result.

| Version | Input scope | Actual model calls | Recorded claims | Model evidence states | Authored mapping evaluation |
| --- | --- | ---: | ---: | --- | --- |
| 1 | Product extensions and replacements; technical/process details open | 8 | 16 | 10 SUPPORTED, 6 UNRESOLVED | INCONCLUSIVE_COVERAGE |
| 2 | Second-site storage, snapshot/replication, tape integration and retention | 17 | 38 | 32 SUPPORTED, 6 UNRESOLVED | INCONCLUSIVE_COVERAGE; expected SERVICE subject absent |
| 3 | Existing jobs/policies/processes, conditional optimization, monitoring, traceability and implementation support | 25 | 81 | 78 SUPPORTED, 3 UNRESOLVED | Authored mapping groups observed; INCONCLUSIVE_COVERAGE |

These counts measure recorded interpretations, not correct classifications. Version 3's analysis status is SUCCESS while its coverage still includes unknown nodes. The independent evaluator therefore retains the inconclusive coverage result. All 135 quotes occur in their respective inputs, but this alone does not prove the target interpretation.

The snapshot identifiers are `fc0d1670-31c6-4eba-acee-e0ab2a9f14f1`, `20c9645c-c2bf-45ad-a5d8-56b849821eb8` and `a82d7ea8-0800-44bc-b735-baae6b6405bf`. They remain immutable comparison evidence.

The first questionnaire contained eight actual questions. The operator answered the question about the recurring purpose at the level supported by the notice and explicitly deferred seven questions about detailed workflows, interfaces and user interaction. The independently frozen second draft was then reviewed through the application's actual adoption preview. The preview had no structural blocker and retained four warnings, including unresolved questions and unsynthesized edits. The operator acknowledged those warnings and adopted version 2.

The second questionnaire contained seven questions. Two process/scope questions were answered from the stated implementation and operating obligations. Five interface and user-interaction questions remained deferred. A separate exact-preview confirmation adopted version 3. Both adoptions preserve `analysisNeedsRefresh=true` until their separate analyses finish. The editable requirement type stayed TECHNICAL and review status stayed PROPOSED. These are source-backed test-operator decisions; no government stakeholder supplied or approved these answers.

### What the relationship changes actually show

At version 1, the BP root records an unresolved operating-process question. At version 2, the infrastructure's restoration purpose supports BP activities indirectly, while the operating steps remain open. At version 3, monitoring and traceable recovery become explicit operating activities: the authored process group is observed through BP-2009, SMC Service Operation. CR-1091, Infrastructure Storage Services, is observed for the additional storage function. BP-1503 was an allowed alternative in the authored reference, not a required exact prediction.

The absent SERVICE subject tag in version 2 is convention-sensitive. Its actual CR-1091 assessment says that a PRODUCT provides the storage function. That distinction is consistent with the prompt's separation between the passage's subject and the catalogue function. The pre-run expectation remains unchanged and the mismatch remains visible; it is not repaired by forcing the label.

### Independent semantic review of the initial result

A second assistant reviewed all 135 individual claims against the appropriate stage and the full supplied catalogue definitions. Root then recorded a decision for each exact claim. Every decision is bound to its claim hash and source-text hash. All six optimization claims preserve both need and consultation with the buyer. No reviewed interpretation invents a numeric backup frequency, retention duration, RTO, RPO, acceptance test or named authorization role.

The review nevertheless finds a clear target/scope error at UA-1193. In version 3, a traceable recovery process is classified as an explicit mandatory delivery constraint on a cyber attack/fault recovery user application. The adjacent restoration-support claim also overstates the application's explicit delivery scope. The passages establish process and infrastructure obligations; they do not establish that particular application as an explicit deliverable. Version 3 therefore has **FAILED_SEMANTIC_REVIEW**, despite valid quotes and matching authored groups.

Other claims receive qualifications: generic existing CPU/OS prerequisites add little decision value; backup restoration can support fault response without commissioning a full security-defence process; integrating a component can involve transition without requiring a complete service-transition process. Supplying tape retention is a defensible subfunction of information storage/protection, so that row is not reported as a definite wrong predicate merely because it lies under BP.

The recorded review dispositions are 14 accepted / 2 qualified for version 1; 33 accepted / 5 qualified for version 2; and 53 accepted / 26 qualified / 2 issues for version 3. These are judgements on this case, not externally certified accuracy percentages.

## Corrections made after the live findings

| Observed defect or weakness | Correction | Verification boundary |
| --- | --- | --- |
| Local and durable ordinary progress APIs dropped typed evidence, including scoreless clarifications | Carry immutable whole-node assessment previews through the public progress DTO and local/durable observation; bound previews to 128 KiB and disclose truncation | Real controller/observation regressions, including an unfinished durable worker; full final result remains authoritative |
| A late bounded progress diagnostic could leave a misleading incomplete-preview notice after final adoption | Localized independent evidence notice; accepted final result clears it and late diagnostic reads cannot revive it | Node regressions for both languages, scoreless and legacy controls, replacement and final state |
| A valid quotation could conceal an invented quantity or lost condition | Separate `RequirementEvidenceSourceFidelityQa`, with bounded case-specific checks and a required complete hash-bound semantic review | Valid-quote mutations cover retention, RTO/RPO, frequency, existing/new delivery, second site, optimization and open details; missing/stale review is inconclusive |
| Explicitness and mandatory scope of a true passage were transferred to a more specific target | General prompt policy `requirement-node-assessment-v3` binds evidence basis, necessity and scope to the exact mapped function; asks for clarification when a possible implementation is unestablished | Fresh component regressions of original child-call groups are recorded separately below; no oracle node IDs were inserted into the prompt |
| Lifecycle overview exhausted heap while reading adoption ancestry | Decode only needed frozen-context lineage strings and collect source closure once; retain canonical baseline, source and scope validation | Old code fails the bounded-allocation regression; new code passes; the real lifecycle endpoint subsequently returned HTTP 200 |
| The bounded QA runner held decoded snapshots while cohosting Chrome | Complete export checks before browser capture, release parsed snapshots, use explicit test-only phase reclamation and bounded browser/runtime settings | Test harness/resource correction only; no product data, saved snapshots or functional assertion is replaced |

Earlier task reviews also repaired source/ID validation during archive and cluster recovery, partial clarification retention, scoreless exports, selected-root report filtering, source-change staleness and revision-pinned continuation exports. Markdown rejects unsupported scoreless-only output clearly; JSON/HTML/Word retain it.

The source-fidelity checker was introduced **after the initial run**, although its facts were already frozen. Its deterministic checks cover a finite set of clear mutations. Broader interpretation still requires the recorded semantic review. The new v3 runs are post-fix regressions on a known case, not held-out validation or a calibrated benchmark.

## Failed attempts and recovery provenance

| Attempt | Recorded outcome |
| --- | --- |
| 01 | Required existing IP repair overlay disabled in harness; startup fails before inference. Corrected and pinned before the first model response. |
| 02–03 | Process exit 137 during startup/browser preparation; zero model calls. Resource pressure suspected; no kernel attribution was available. |
| 04 | Eight fresh responses and a saved first-stage result; harness fails on an empty previous-ID assertion. Fixed and regression-tested. |
| 05 | Eight further fresh responses and a saved first-stage result; native browser click is intercepted during smooth scrolling. Switched to instant, observed-position scrolling. |
| 06, first process | Eight fresh first-stage responses; browser/narrow view and all three export content checks pass. Questionnaire request returns HTTP 500. A retained-database diagnostic creates the actual questionnaire successfully; original cause unverified. |
| 06, explicit resume | First snapshot and original receipts revalidated with zero new first-stage inference. Two adoptions and the next 42 fresh responses complete all three analyses. Final browser preparation encounters a captured Java heap error in ancestry decoding; process later exits 137. |
| 08 | After the codec correction, a combined application/Chrome process exits 137. No additional model inference; no new Java heap error captured. |
| 09 | Real lifecycle GET returns HTTP 200 and the third-stage browser overview renders. The process then exits 137 before completing the browser/export checks. |
| 10 | A deliberately smaller 320 MiB heap fails the lifecycle read with a Java heap error before Chrome. No new model calls. |
| 11 | The lifecycle read and all third-stage JSON/HTML/DOCX content assertions pass at 384 MiB. Combined application/browser execution then exits 137. No new model calls. |
| 12 | A browser-only recovery with no relay and bounded compiler settings exits 137 during application startup. No completed browser check or new model call. |

All retained first-run snapshots and delivered responses remain unchanged. Recovery rechecked the three server snapshots and their original completion receipts, performed the previously failing lifecycle read and verified the third-stage export contents. The first two stages passed their actual desktop/narrow browser checks; the third-stage overview actually rendered, but its complete expanded desktop/narrow verification remains unfinished. No recovery attempt produced a new completion or a successful final-presentation marker. Exit 137 is a recorded termination, not proof of a particular kernel memory decision.

The additional complete v3 traversal was therefore not run. `RequirementEvidenceTargetedLiveTest` instead makes bounded fresh component calls through the real production `OpenAiCompatibleGateway` and `LlmResponseParser`, with the actual loopback `InteractiveCompletionRelay`. The manifest selects nine original child-call groups across versions 2 and 3, including storage controls. Each request keeps the original requirement, all original offered candidates, their definitions, allocation budget and response schema. Only the old evidence-policy block is replaced with the current production v3 contract. Original responses are never served as new answers. The responder sees the actual newly queued HTTP request and no evaluation material.

Each new answer has a fresh completion ID, successful delivery receipt and exact delivered-byte digest. The production parser checks the actual answer against the unchanged original requirement. Component transport/parser success and semantic judgement are separate fields. These are known-case component regressions, **not** new portfolio snapshots, full taxonomy traversals, new adoptions or a held-out accuracy benchmark.

## Verification completed

Counts below are counts for the named runs, not a sum of unique tests across overlapping suites.

| Area | Recorded result |
| --- | --- |
| Core implementation | Initial 169 focused Maven tests; final task-fix run 61 passed |
| Original shared UI/report implementation | 574 Node tests; focused native browser regressions and report regressions passed as recorded in task reports |
| Progress API correction | 26 focused local registry, actual HTTP/controller and durable observation tests passed |
| Progress UI correction | 183 focused Node tests passed; five new missing-notice/bridge assertions first reproduced RED |
| Ancestry correction | 7 codec boundary and 3 adjacent repository tests passed; bounded field-allocation test first failed on old code |
| Source-fidelity correction | 7 tests passed, including valid-quote mutations and stale/incomplete semantic review |
| Final focused gates | 88 tests in 11 suites passed across recorded runs: reference/fidelity QA, saved reports, reporting/API isolation, report boundary, derivation, dependency ratchet, architecture, cycles and completion |
| Relay transport | 9 real-loopback transport tests passed directly and through Maven; these are the same nine tests, not 18 unique cases |

All 20 production modules compiled in the reactor. Later scoped Java corrections were compiled and tested against the current reactor classpaths. The final required architecture import exceeded a 384 MiB test heap; the unfinished 53 architecture tests passed with 768 MiB. An ad hoc arch-only wrapper also attempted two empty Java source lists; those unused setup steps failed, while their reporting-module suites had already passed in the first run. The verification consolidation records each successful suite and preserves the failed attempt logs. It is not described as a successful wrapper invocation.

A complete application test compilation was previously terminated with exit 137, and one broad browser run stopped after 92 passing cases with a worker failure. Neither is reported as a completed passing suite. The complete canonical Maven architecture profile and a full repository test run are not claimed.

## Final component comparison and presentation limits

All nine planned component calls completed through the actual gateway and parser with process exit 0. The run retained nine distinct new completion IDs and verified their delivered-byte hashes. The requests offered 63 candidate positions: 22 positive scores and 41 zero scores. Forty zero-score positions had explicit empty assessment arrays; the remaining zero-score position, BP-1131, retained a substantive unresolved claim. Empty arrays and unoffered nodes are not counted as reviewed claims.

The independent review covered all **34 new claims: 25 accepted, nine qualified and zero clear new issues**. Their model evidence states were 29 SUPPORTED and five UNRESOLVED. The existing source-fidelity evaluator then verified each complete claim-bound review and found no deterministic source-fidelity violation. It deliberately evaluated a claim-only partial projection and retained `INCONCLUSIVE_COVERAGE` for all nine groups. No saved analysis or fabricated full-coverage result was created for that evaluation.

| Observed target | Fresh result | What this establishes |
| --- | --- | --- |
| UA-1193, specialized recovery application | Restoration support is SUPPORTS / INFERRED / CONTEXT / UNSPECIFIED. Process traceability is UNRESOLVED, with a question about a dedicated application workflow. Its positive allocation remains 13.0. | The two clear initial target/delivery overstatements do not recur in this selected call; the score is independent of required delivery and evidence completeness. |
| BP-1131, security defence | Zero score with an unresolved question about a defined fault/attack response | Missing target-specific evidence survives even at zero ranking; absence of a positive score is not negative evidence by itself. |
| CR-1091, storage controls in both versions | The second-site storage obligation and qualitative tape retention remain supported | Greater caution about specialized applications does not erase explicit storage delivery. No retention duration, replication frequency, RTO or RPO is invented. |
| BP-2009, operations control | Two supported CONSTRAINS claims for easier monitoring and traceable recovery | These are defensible quality constraints. Neither is DIRECT_SUBJECT as the original authored group required; BP-1503 was not offered. This is not a new exact-group pass. |

The nine qualified judgements remain part of the result. They concern directionally ambiguous SUPPORTS relationships and cases in which the general infrastructure function is better established than the more specialized catalogue node. Examples include a possible application helping a monitoring requirement versus the requirement supporting the application, and ordinary cross-site data exchange versus a particular higher-level IP service. No automated correction or new obligation was inserted to erase these qualifications. The per-claim rationales and the before/after audit remain available with the raw run evidence. This is evidence of improvement on selected known errors, not a general accuracy rate or proof that green model proposals are always correct.

The evidence package includes the actual HTML/JSON reports. Word export contents were checked by the live harness, but the complete Word layout was not visually inspected, so Word is not included as a visually validated deliverable. Browser captures remain internal inspection evidence. The third-stage complete browser check, a new full traversal under v3, the full repository test suite and external CI remain separate verification boundaries.

The independent code review found no remaining Critical or Important findings in its reviewed scope. Its final pass also verified the nine delivery records and original/new prompt hashes, and prompted two harness corrections: the existing `real-llm` class tag and a guard protecting interrupted runs from accidental overwrite. A subsequent guard smoke rejected reuse, preserved every recorded byte and left the request count at nine. Review approval does not certify the outstanding runtime or semantic boundaries above.

## Reproducing the two distinct checks

For a new full guided session, use `RequirementEvidenceLiveSessionTest` with an empty output directory and a fresh relay spool, following the real operator decision and exact-preview confirmation checkpoints above. Keep the original catalogue and configured overlay; this case uses BP, CR and UA with `TAXONOMIES_ONLY`. The selected-source fixture is a reference input, not a completion fixture.

For a targeted prompt regression, prepare an explicit `TARGETED_RECORDED_CHILD_CALLS` manifest in an empty result directory. Each call names the captured original HTTP request, its matching provider record, original completion ID, unchanged requirement, full candidate ID list and inherited budget. Supply `taxonomy.evidence.targeted.output` and `taxonomy.evidence.targeted.spool`; the test starts its relay in the same process/network namespace. It is opt-in:

```bash
./mvnw -B -ntp -pl taxonomy-app -am test \
  -Dtest=RequirementEvidenceTargetedLiveTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexcludedGroups= \
  -Dtaxonomy.evidence.targeted=true \
  -Dtaxonomy.evidence.targeted.output=/absolute/path/to/targeted-run \
  -Dtaxonomy.evidence.targeted.spool=/absolute/path/to/empty-spool
```

A separate responder must supply each new queued request as described in [assistant-completion-relay.md](../dev/assistant-completion-relay.md). This command does not provide an unattended inference model. In the resource-bounded demonstration, the same class was compiled against the current Maven-resolved reactor classpath and run directly with a 160 MiB heap. Successful parsing does not replace the source/definition review of each newly captured claim.
