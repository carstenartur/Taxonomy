# Flood scenario: independent functional architecture reference

## Purpose and authority

The machine-readable reference is [flood-quality-reference.json](../../taxonomy-app/src/test/resources/scenarios/flood-quality-reference.json), version `flood-functional-architecture-v3`. It was authored independently from the unchanged FLOOD-001 requirement, the actual prompt contracts and full catalogue descriptions. It is not generated from `flood-information.json`, its scores, its answers or its `relationPlayback` claims.

The reference defines **19 required directed relations**, **88 permitted additions**, **13 rejected or unresolved required-claim dispositions**, **one precisely bounded open input-identity question with seven exact relation bindings**, and **20 constraints bound to specific elements or relations**. Each relation and constraint retains the relevant complete, verbatim requirement clauses. Its original requirement text has SHA-256 `a5546798b6117e9d66cc3c63ce316ae9985b980aa949df7669d3c28fb5d9d989`; that identity must agree with the saved relation-search report. A1 remains explicitly a **test design assumption** throughout. The original 19 core relations, 20 constraints, seven required-claim exclusions, requirement clauses and open physical decisions were compared structurally with the retained pre-refinement reference and remain unchanged.

This is a bounded reference architecture in the selected catalogue vocabulary. It is not a claim that only one physical implementation is valid, that a live model would produce these answers, or that an external domain expert has validated the solution. A different but equivalent architecture needs independent review and a reference revision; it must not become acceptable simply because a playback response contains it. Exact repeatability and fixture equality establish neither model quality nor optimal prompts.

The [relation protocol](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/relations/RelationSearchProtocol.java) requires scoped contributions, exact requirement quotes, separate read/write functions, explicit direction and honest necessity. It prohibits inferring relations from relevance scores or catalogue features absent from the requirement. The [compatibility matrix](../../taxonomy-knowledge/src/main/java/com/taxonomy/relations/service/RelationCompatibilityMatrix.java) supplies the actual allowed source/type/target root combinations. All reference triples were checked against it.

Catalogue evidence comes from the full descriptions in [the retained catalogue workbook](../../taxonomy-knowledge/src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx), SHA-256 `6b19743eff1487a76ea3e5b788d90831ba1705da31790cf58f2d69a979b14130`. Runtime hierarchy repairs also matter: [nato-taxonomy.json](../../taxonomy-knowledge/src/main/resources/data/nato-taxonomy.json) places `IP-2130` under `IP-1069`, correcting its invalid workbook parent. A reviewer must use the offered runtime path, rather than reconstructing a different tree from the uncorrected spreadsheet.

## Required relation reference

Every arrow below is the stored canonical direction. The complete rationale, condition and original clauses are in the JSON. `REQUIRED` describes the reference function, not an unconditional delivery through every supported channel.

| ID | Directed typed relation | Requirement-scoped function |
|---|---|---|
| R01 | `UA-1580 CONSUMES IP-1116` | Read the published environmental/observation representation. |
| R02 | `UA-1580 CONSUMES IP-2130` | Read published area-related hazard warnings. |
| R03 | `UA-1580 SUPPORTS BP-1535` | Present that information understandably for retrieval and dissemination. |
| R04 | `BP-1017 CONSUMES IP-2130` | Acquire published warning inputs. This does not classify the separate raw observation batch as a recognized picture. |
| R05 | `BP-1535 CONSUMES IP-2130` | Retrieve existing warning content in the public-information contribution. Subscriber dispatch is a separate contribution. |
| R06 | `BP-1535 USES CR-1098` | Route deliveries according to selected areas and channels. |
| R07 | `BP-1535 USES CR-1097` | Use the durable delivery queue required by A1. Idempotency remains an explicit property. |
| R08 | `CR-1060 SUPPORTS BP-1535` | Provide text notification functionality. |
| R09 | `CO-1048 SUPPORTS BP-1535` | Provide short-message access for the selected text channel. |
| R10 | `CO-1066 SUPPORTS BP-1535` | Provide the selected phone/voice channel. |
| R11 | `CR-1037 SUPPORTS BP-1535` | Provide the selected email channel. |
| R12 | `BP-1521 USES CR-1022` | Manage account/contact entities through the identity lifecycle. |
| R13 | `BP-1530 USES CR-1022` | Delete or deprovision an account. |
| R14 | `CR-1007 SUPPORTS BP-1140` | Prevent unauthorized access to subscriber information. |
| R15 | `CR-1006 SUPPORTS BP-1140` | Establish the entitled identity for protected account actions. |
| R16 | `CR-1007 SUPPORTS BP-1536` | Protect stored subscriber information. |
| R17 | `BP-1535 USES CR-1198` | Keep an auditable delivery history. |
| R18 | `UA-1522 USES CI-1060` | Use IT service-management functions for administrative oversight. |
| R19 | `CI-1060 SUPPORTS BP-2009` | Support operation and oversight of this information service. |

The text, phone and email capabilities are all required by F3. A particular delivery uses a channel when the subscriber selected it. Treating the whole phone or email function as optional would omit a required function. The two text layers are different catalogue abstractions; their presence does not identify a carrier, vendor, network or mandatory transport connection.

The allowed set contains narrowly scoped picture preparation (`CI-1096 PRODUCES IP-1116`), geographic preparation (`CI-1051 SUPPORTS BP-1866`), capability mappings for actual dissemination/protection, explicit access-control dependencies, records-management dependencies and a declared short-message/text-service binding. These additions must retain their stated conditions. Their absence does not reduce required-relation recall. The identity evaluator permits the triples; the separate functional gate must still check the meaning and conditions of each addition.

### Independently reviewed additions and necessity

The reference was extended additively when review of the actual source contributions, complete catalogue descriptions and typed prompts established another legitimate representation. The added relationships were reviewed before being written as playback answers. Permitted additions do not increase the 19-edge core denominator, and a new answer cannot admit itself to the reference.

| Reference additions | Bounded meaning |
|---|---|
| L13–L17 | Known-warning input to dissemination, optional capability-facing email/text/protection views, and F2 service-management capability. |
| L18–L23 | Optional service-facing views of already required queue, routing, delivery audit, identity lifecycle and service operation. |
| L24–L27 | Explicitly selected existing-water read-provider views and a separately identified forwarded-warning output. The former unconditional hydrography claims remain prohibited. |
| L28–L38 | Actual existing-warning collection/processing, authentication/access-control and processing-capability functions, plus optional security capability-facing views. |
| L39–L46 | Eight optional coarse views: email and identity lifecycle under IT services; text and email under communications; the corresponding capability-facing views. |
| L47–L57 | Additional existing-information preparation, presentation, organisation, known-warning input, bounded water-provider and area/channel routing views. |
| L58–L61 | Optional authentication/authorisation support views of the protected F4 contact/subscription and account-removal functions. |
| L62 | Optional process-facing public reading of the existing environmental view, distinct from the unknown F5 upstream input identity. |
| L63–L66 | Optional delivery-history product views: two writers of the already required A1 history and two readers only when F2 oversight explicitly uses that history. |
| L67–L75 | Optional process-facing views of existing identity/access protection, explicitly selected existing-water input, public information organisation/preparation and service oversight. |
| L76–L80 | Optional process-facing email/text and public-reading service views. The F3 channels remain required; the selected existing-water provider remains only a partial, conditional input fit. |
| L81–L84 | Optional use of the existing history service for the explicitly selected F2 reader, access identification for stored subscriber details, and the same history as an input to the selected administration service/capability view. |
| L85–L87 | Optional identity-assurance views for protected F4 contact/area changes, the deletion request and access to stored subscriber details. No authentication is invented for every internal write or cleanup operation. |
| L88 | Optional direct identity-lifecycle support for the explicit account-deletion share of protected subscriber information. No access-revocation integration, storage technology or audit-retention rule is inferred. |

`REALIZES` uses this repository's passive **capability → service** convention. A capability-facing representation is not reversed UML realization, a second implementation or an additional required user function. These optional views have explicit selection conditions. `FULFILLS` describes the scoped service's functional contribution to a capability and does not claim every feature of the broader capability is implemented. L05/L06, for example, retain required email/text functions; L04 describes an optional selected application-to-picture-service decomposition.

The CP-1122 source contribution is explicitly restricted to the additional F3 email and F4 identity/contact/deprovisioning view. Environmental processing, queueing, access protection and service administration keep their own assessed contributions. Their exclusion from this particular coarse view is not a claim that those functions are unrelated to IT. CP-1127 similarly retains actual channel shares without asserting a bearer, satellite/radio system or security platform. The phone function remains represented through the permitted CO service-to-process relation; the compatibility matrix supplies no invented CO-to-capability `FULFILLS` edge.

BP-1535 is assessed as three distinct contributions: `public-information` for F1 reading/presentation, `subscriber-delivery` for F3 area/channel dispatch, and `reliable-delivery` for A1 queue/retry/audit. IP-2130 has separate `default` published-warning input and `forwarded-warning-notification` output roles. The latter is only a notification forwarding an already published warning; it creates no official warning authority and does not turn ordinary observation or service-state messages into warning products. These roles must remain distinguishable in answers, evidence and exclusions.

The 21 additions L67–L87 were reviewed against the complete source contributions and full runtime candidate descriptions exposed by the first exhaustive bounded discovery probe. A service supporting a process was not automatically treated as a service the process uses. Each `USES` view needs the stated function and an explicit choice of the additional process/service representation. Likewise, reading the A1 history does not by itself prove use of the service that writes it: L81 additionally selects the existing records function for that access. No new reporting UI, retention policy, forecast, measurement programme or identity provider follows from these additions.

Authentication establishes the entitled identity; authorisation remains a separate access decision. The protected deletion request can use an established identity without authenticating after deletion or at every cleanup step. Access to stored subscriber details can reuse an existing authentication context without a login for each internal write. The public read role gains no authentication requirement. These conditions preserve the required F4 behaviour and A1 protection while bounding the optional extra representation.

L88 was established by the next actual candidate review. `CR-1022 SUPPORTS BP-1536` has a direct F4 anchor: the source service maintains/deprovisions the subscriber identity, and the stored-information contribution explicitly supports account deletion. The additional lifecycle view is optional, while F4 deletion remains required. In contrast, `CR-1022 SUPPORTS BP-1140` would introduce a direct account-status/revocation integration not present in the assessed lifecycle contribution. The full catalogue's general support for authentication cannot supply that missing scoped function. Cryptography is also a possible later mechanism, but the current contributions do not assign encryption/decryption, key handling or cryptographic-integrity operations. Their exclusion here is not based on a missing product or algorithm and does not deny cryptography's protective function.

### A1 delivery-history product at the supported category frontier

Independent review of the full catalogue established a missing bounded data view. `IP-2054` (Knowledge Management Information Products) conveys operational, procedural or functional facts about managing knowledge and information. A1 explicitly requires an auditable history of delivery work and outcomes, which fits this broad category. The category is a real non-container concept; no specialist child has to be invented to retain its relevant function.

The six `IP-2049` management children and all nine `IP-2054` specialist products were reviewed individually. Facility, personnel, finance, resource and future organisational-planning products do not describe the requested history. Nor do approved security-product catalogues, captured-document exploitation, host-nation capabilities, lessons/remedial actions, manual periodic statistics, radar/radio signatures or a retention/disposition matrix. All nine specialised products remain explicit zero. `IP-2099` Geospatial Support Reports carries a different operations/SITREP/AAR purpose; the A1 audit clause does not establish that report product.

The reviewed IP root remains **90**: **55** for the bounded public environmental picture, **25** for published area-linked alerts and **10** for the delivery-history information. The new positive frontier is `IP-2054`, beneath `IP-2049`; its specialised products receive no forced share. The complete response corpus now has **57 scoring scopes, 298 individual answers and 38 positive frontier concepts**. These comparative relevance weights are authored judgments, not confidence values or measured requirement-coverage percentages.

The `IP-2054/default` source role is exclusively A1 delivery work, attempts and outcomes. L63 permits the records/audit service to write that history; L64 permits the A1 reliable-delivery process-facing output view. L65/L66 permit an application/process reader only if F2 oversight is explicitly implemented using that same history. All four are **OPTIONAL additional product views** of an already required function. They introduce no report service, reporting UI, retention duration or priority over F4 deletion. C19 and the original core relation R17 still independently require auditable delivery history.

## Why the former seven-edge architecture was insufficient

The old scenario supplied the same small relation corpus on every pass. Its playback verification checked a stored claim against the supplied proposal; a matching response was therefore evidence of replay fidelity, not an independent semantic assessment. [ScenarioRelationPlayback](../../taxonomy-app/src/test/java/com/taxonomy/acceptance/ScenarioRelationPlayback.java) and the actual [relation prompt builder](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/relations/RelationSearchProtocol.java) are distinct evidence: the prompt asks the right semantic questions, while authored answers can still answer them incorrectly.

| Former claim | Independent assessment |
|---|---|
| `UA-1580 CONSUMES IP-1116` | Retain the read relation, limited to existing environmental information. |
| `CI-1052 FULFILLS CP-1041` | Reject as required. Hydrography access/computation does not establish subscriber dissemination. |
| `CI-1052 PRODUCES IP-1116` | Reject the former required hydrographic-interpretation claim. F1 asks for a view of published information, not new specialist analysis. |
| `BP-1017 CONSUMES IP-1116` | Do not assert as required. F5 names an observation batch without proving it is already a recognized environmental picture. |
| `CI-1052 SUPPORTS BP-1017` | A necessary specialist hydrography provider for the feed is not established. |
| `UA-1580 USES CI-1052` | A direct dependency on specialist processing is not established merely by the display requirement. |
| `UA-1580 USES CR-1097` | A1 requires queued delivery, not mandatory direct UI-to-queue coupling. R07 binds the delivery process. |
| Optional `CO-1048 COMMUNICATES_WITH UA-1580` | The text-channel statement does not establish the display application as the channel endpoint or subscriber recipient. |

`CI-1052` describes access to hydrographic information and computations including depths, tides, currents and bed topography. `CI-1096` is the more relevant optional abstraction for compiling an environmental representation, but only the requested subset is justified: published inputs, not new forecasting or formal multi-agency agreement. `CR-1037` explicitly describes electronic mail; military Tactical Messaging is not a substitute. `CR-1097` describes temporal decoupling through queues, so its name alone does not prove durability or idempotency.

Likewise, `BR-1223` describes local institutions, transparency, rule of law and military-supported civil governance. Administrative oversight of an information service does not justify that specific role or an `ASSIGNED_TO` edge. A broader role category may remain relevant without a justified specialist child. Positively scored ancestors and leaves need complete source assessment; they do not all need fabricated architecture connections.

## Twenty functional and quality constraints

The JSON's `binding.elementIds` and `binding.relationIds` identify where each requirement must be inspectable. A taxonomy name, a score or a line in an unrelated QA manifest is not sufficient evidence. The actual snapshot and delivered architecture must retain the scoped behavior or limitation with the original clause and the correct source identity.

| ID | Required behavior or limit | Principal binding |
|---|---|---|
| C01 | Understandable view of published warnings, affected areas and water observations. | `UA-1580`, `BP-1535`, R01–R03 |
| C02 | Source and observation time remain attributable to the displayed information. | `UA-1580`, `IP-1116`, `IP-2130` |
| C03 | Administrative oversight of this service. | `UA-1522`, `CI-1060`, `BP-2009` |
| C04 | Area subscriptions and user-selected channels determine applicable deliveries. | `BP-1521`, `CR-1098`, R06/R12 |
| C05 | Support the selected text notification channel. | `CR-1060`, `CO-1048`, R08/R09 |
| C06 | Support the selected phone notification channel. | `CO-1066`, R10 |
| C07 | Support the selected email notification channel. | `CR-1037`, R11 |
| C08 | Permit entitled contact-detail updates. | `BP-1521`, `CR-1022`, R12 |
| C09 | Permit area removal; do not continue treating removed areas as active subscriptions. | `BP-1521`, `BP-1530`, `CR-1098` |
| C10 | Permit account deletion/deprovisioning. | `BP-1530`, `CR-1022`, R13 |
| C11 | State that surface-water flooding is outside the service's scope. | `UA-1580`, `BP-1535` |
| C12 | Consume the latest available observation batch under the exact 15-minute wording. | `BP-1017` |
| C13 | Show stale data. | `UA-1580`, `BP-2009` |
| C14 | Show unavailable data; absence is not an all-clear state. | `UA-1580`, `BP-2009` |
| C15 | Retain official warning channels alongside the service. | `UA-1580`, `BP-1535` |
| C16 | Require a durable delivery queue under A1. | `CR-1097`, `BP-1535`, R07 |
| C17 | Retry delivery work idempotently under A1. | `CR-1097`, `BP-1535`, R07 |
| C18 | Protect subscriber details and privileged account actions. | `CR-1006`, `CR-1007`, `BP-1140`, `BP-1536` |
| C19 | Keep an auditable delivery history under A1. | `CR-1198`, `BP-1535`, R17 |
| C20 | Remain supplementary, without claiming a safety-critical replacement for official alerts. | Service scope, `UA-1580`, `BP-1535` |

### Details that remain unspecified

The exact stale threshold, the interaction between account deletion and historical-record retention, the concrete feed data-product identity, and the broker/persistence/idempotency implementation are not supplied. Those are explicit implementation decisions to resolve separately. Their absence does **not** waive stale indication, deletion, auditability, durability or idempotency.

The phrase “at most every 15 minutes” must stay attributable to F5. Do not silently substitute a stronger SLA or an end-to-end warning-delivery guarantee. Do not invent an indefinite retention policy, a precise deletion period, a forecasting algorithm, a particular login mechanism or an exactly-once guarantee. Rejecting a required input-REP relation leaves that physical identity unspecified; it does not remove the required observation intake function.

## Evaluation and export gates

[ScenarioRelationQuality](../../taxonomy-app/src/test/java/com/taxonomy/acceptance/ScenarioRelationQuality.java) loads the independent resource, verifies original requirement identity, and checks canonical type and direction. It records the reference checksum. A strictly complete result requires the source cohort to be completely assessed and retained, all planned searches completed, and explicit `searchExhausted` true. Warnings, unfinished work, unanswered source questions, pending searches or source limits prevent that complete result. `PARTIAL`, `FAILED` and `CANCELLED` have no fabricated precision/recall rates.

There is one explicit functional-acceptance exception, kept separate from strict search completeness. X03 asks: **“Is the F5 incoming observation batch already a Recognized Environmental Picture, or is its product identity unspecified?”** The protocol requires this genuine ambiguity to remain `UNRESOLVED`; it cannot be rewritten as a confident negative merely to obtain a green report. The input acquisition function and exact F5 cadence must nevertheless survive at BP-1017 in the snapshot and exported notes, without asserting an input-REP edge.

The same observation input reaches seven explicitly reviewed functional bindings. Requiring the identity at one binding while confidently rejecting the same unknown input at another would give inconsistent answers to the actual prompts. The additive X08–X13 entries therefore retain the following exact set; X03 itself and the original core/constraint requirements are unchanged.

| Disposition | Exact canonical binding | Input function |
|---|---|---|
| X03 | `BP-1017 CONSUMES IP-1116` | Acquire the latest observation batch. |
| X08 | `BP-1529 CONSUMES IP-1116` | Collect the existing observation material. |
| X09 | `BP-1534 CONSUMES IP-1116` | Organise the public observation share for retrieval, distinct from private subscriptions. |
| X10 | `BP-1866 CONSUMES IP-1116` | Prepare incoming observations with source/time/freshness context. |
| X11 | `CI-1096 CONSUMES IP-1116` | Assemble the downstream view from existing inputs whose observation datatype remains unspecified. |
| X12 | `CP-1020 REQUIRES IP-1116` | Supply the collection capability's observation input. |
| X13 | `CP-1097 REQUIRES IP-1116` | Supply the processing capability's observation input. |

All seven entries have `questionGroup=F5_OBSERVATION_INPUT` and the exact same F5 question. This is **one missing piece of domain information affecting seven possible connections**, not seven independent requirement gaps. Each binding has both concrete search orientations; no root, sibling, different product or different relation type receives wildcard permission. The six clarified source contributions make their already present input boundary explicit and retain a genuine contiguous original quote, without adding acquisition, sensing or analytical functions.

Every source must be assessed and retained, all other planned searches completed, and all budget/technical/ownership interruptions absent. The evaluator checks every concrete unfinished entry, matching `UNRESOLVED` trace, task state and counter. Fourteen orientation records for this full set correspond to ten source/target-root tasks because multiple incoming BP/CP bindings share one search task. The production-facing warning list remains exactly its summary plus the first eight question entries in their actual order, including repeated source labels. All later questions and traces are still validated and retained; the display limit does not remove evidence. Other questions, unresolved targets, a missing phone branch, reversed relation, unassessed source or remaining search budget cannot use this exception.

When the separate functional conditions are met, the report may say `PASS_WITH_OPEN_POINTS`; the snapshot remains `PARTIAL`, strict search completeness remains false, and precision/recall remain null. The combined architecture gate additionally requires all 20 constraints and the untyped F5 input limitation at each actually open binding’s corresponding exported source element, including the original BP-1017 intake. It does not claim complete implementation decisions or hide this open point. A continuation may finish independent remaining work without discarding or relabelling the retained clarification.

The first realistic local candidate illustrated why the empty `unfinished` array was insufficient: it retained only 32 of 74 source assessments, 257 pending searches and `searchExhausted=false`, while using 91 of 256 relation calls. A complete semantic work plan must be able to continue within its actual bounds. Renaming that result `SUCCESS`, hiding remaining sources or raising scores would not close the evidence gap.

The relation report explicitly states `coverageAssessment=SEPARATE_SNAPSHOT_AND_EXPORT_GATE`. A relation `PASS` therefore does not claim that C01–C20 are fulfilled. The combined scenario must separately verify:

1. All required functions and constraints are present at their declared snapshot bindings, with their literal evidence and scope.
2. Every accepted required directed relation reaches the actual delivered Sparx file with its canonical type, direction and source identity intact.
3. The same source attribution, conditions, A1 provenance and relevant quality limits remain inspectable at the corresponding exported elements or relations.
4. Source and target contributions in readable evidence agree with the canonical edge direction, including relations discovered through an incoming search.
5. Proposed evidence, reviewed acceptance and actual external-product compatibility are reported honestly.

The retained earlier Flood integration exported only two of seven required relations through immutable Sparx profile v1. The separate native-v2 contract fixture was not an end-to-end export of the Flood architecture. Both old semantic profiles used the same restricted relation map. The fresh-copy handoff retained seven carriers but transformed five canonical types into `RELATED_TO`; its manifest correctly disclosed that loss, but disclosure did not make the architecture sufficient. The native export also lost the detailed requirement evidence before writing the final file.

The appropriate correction is an explicit new profile that preserves the exact canonical taxonomy type and direction through a bounded transport mapping and validated metadata. Existing immutable profile meanings must remain intact. A standard carrier with explicit Taxonomy semantics is acceptable only when the canonical relation survives reimport; a label attached to a demoted generic relation is insufficient. Repository round-trip tests do not establish compatibility with a real Enterprise Architect desktop installation.

## Verification of the independent evaluator

[ScenarioRelationQualityTest](../../taxonomy-app/src/test/java/com/taxonomy/acceptance/ScenarioRelationQualityTest.java) uses a separately hand-written 19-edge projection, not predictions copied from the oracle. Its mutations remove the phone branch, reverse read direction, add former unsupported claims, hide a source limit behind a successful status and empty unfinished array, remove retained sources, leave planned searches pending, clear explicit completion evidence and substitute another requirement's hash. A permitted scoped producer is also checked so the gate does not reject every addition indiscriminately.

The initial isolated JUnit execution used the repository's existing launcher and dependency classpath while the shared reactor contained incomplete parallel edits. New test resources were placed ahead of compiled test resources. RED ran against the unchanged old evaluator: **25 tests, 9 successful, 16 assertion failures, no aborted or skipped tests**. After the evaluator change, GREEN ran the same class: **25 tests successful, zero failures, aborts or skips**, with process exit 0. Retained local evidence is under `target/flood-quality-regression-runner/` (`red.log`, `green.log` and the verification manifest).

The subsequent necessity/open-identity changes were also observed red before changing the evaluator: **48 cases, 40 successful and eight assertion failures**. The corresponding isolated GREEN completed **48 of 48**, with zero failure, abort or skip and exit 0. Mutations include promoting optional provider/capability views, removing an optional condition, changing X03's question/target/direction, losing its trace, changing task state, retaining a budget remainder, dropping a source and pretending the partial snapshot is complete. Evidence is under `target/flood-quality-necessity-runner/`. Those results predate the final additive reference entries; coordinated application/scenario verification remains the authority for the current complete tree.

The seven-binding refinement was then exercised with **71 isolated JUnit cases**. Before the evaluator change, RED had 63 successful cases and eight assertion failures: seven new group/evidence assertions exposed the missing grouping and incorrect warning de-duplication, and one earlier fixed-count reference assertion needed the six approved additive dispositions. After the focused change and exact reference assertion update, **71 of 71 were successful**, with zero failure, abort or skip and process exit 0. The new mutations reject wrong source/type/product identities, assertion of an open edge, warning reordering/de-duplication, and lost or changed evidence beyond the displayed first eight questions. `target/flood-input-identity-regressions/` retains source/resource copies, compile/test logs and hashes. The first GREEN used the 66-addition reference. A fresh isolated compile and execution against the current **88-addition reference** again passed **71 of 71**, with zero failure, abort or skip and exit 0; `current-v3/verification.json` records its exact commands and source/resource hashes. Neither result is an application, provider or export execution.

This is evidence for the owned evaluator and its mutation tests. The coordinated full build, real scenario continuation, actual snapshot/export constraint assertions and required remote CI remain separate gates. No provider call or desktop import was used to obtain this evaluator result.

## Authored negative decisions and final captured-request check

[flood-relation-exclusions.json](../../taxonomy-app/src/test/resources/scenarios/flood-relation-exclusions.json) contains **383 explicit rejection rules and 14 exact orientations of the shared F5 input question**, bound to source identity, source contribution, relation type, direction and phase. Candidate IDs and subtree IDs form a union. Subtree ancestry comes from actual offered scoring scopes and known paths; ID prefixes and unknown parentage cannot create a match. There are **34 distinct named `reviewFamily` values**: **33 shared catalogue families** covering military command functions, new environmental analysis, unrequested channels and infrastructure programmes, plus **one `scoped-functional-boundary` family containing 204 rules**. Eight further rules have no `reviewFamily` field. Grouping those absent fields as an extra bucket produces the previously reported 35 groups; it does not mean 35 named families. The scoped rules explain why a public reader is not a broker client, account data is not a public picture, a channel is not the administrator and a delivered forwarding output is not its publisher's upstream warning.

All three obsolete open provider/output questions were removed after independent review established their explicit conditional optional representations. The new forwarded-warning output role has its own incoming consumption/production, capability-input and role-specific exclusions. It does not inherit the published-input answers or BP-1535's other contribution identities. X03 remains the original open disposition; X08–X13 bind the same single input-identity question to its six other reviewed functional manifestations.

The authoring helper formats manually written semantic decisions across exact compatible query routes. It does not create negatives from zero classification scores, an absent positive claim, an unvisited target or an old generic negative response. Redundant selector bindings with the same actual source/part/type/direction are removed using known ancestry so two true reasons do not exceed the protocol's 800-character rationale limit. This is authoring-time formatting; the runtime matcher has no generic fallback. It also rejects a contradiction between a positive claim and a negative/open rule, including a rule that would cut off a positive endpoint at an ancestor.

At the earlier pre-IP-2054 freeze, the set contained **78 claims: 36 REQUIRED and 42 OPTIONAL, across 39 source contributions**. The then-retained 319 real request records were replayed through the actual matcher with fresh resources and the independent conflict-validator classes. The final diagnostic accepted **314 requests**, found **zero missing authored decisions**, and rejected **five stale CP-1122/CP-1097 contribution texts** that predated their explicit scope refinements. Thirty-one outcomes changed from the former generic playback, including navigation towards now justified matches or the concrete open identity. The old requests were evaluated as recorded rather than silently rewritten to agree with current contributions; the retained diagnostic manifest records their input hash and exact failures.

This diagnostic establishes coverage of the applicable retained requests and absence of constructor-detected positive/exclusion conflicts. It is **not** a new end-to-end application run, proof that every future descendant is covered, a live-model quality measurement or evidence that search budgets are exhausted. Unknown fresh scopes still fail closed. The coordinated real application run must expose any remaining scopes and prove continuation, snapshot evidence and delivered-architecture coverage before acceptance.

The earlier static inventory of all 39 source contributions and the exact compatibility routes identifies **348 possible source/part/type/direction/target-root scopes**. At that earlier freeze, 235 had a positive/open path or an explicit root rejection; **113 scopes had not been exercised by the retained calls and lacked a root answer**. This inventory is recorded in `target/flood-relation-exclusions-authoring/compatibility-route-inventory.json`. It makes the remaining coverage work visible; it does not assign outcomes to those scopes or count them as completed searches. The real current scenario run and a bounded semantic review of that finite remainder are required before declaring the corpus complete.

The exact local records are `target/flood-relation-exclusions-authoring/captured-request-final-audit.json`, `captured-request-final-audit.log`, `final-authoring-verification.json` and `catalogue-evidence.json`. The verification manifest records the command, exit status, class/resource/input hashes and exact reasons for the five stale requests. At that earlier freeze the reference SHA-256 was `1a8472d4e43bb17fec39f066d270076247ffd64722b5cbdb6e5183366eba61ea`; the exclusions SHA-256 was `00ae2e9002f2e2c35bb371143d36b45e7f7abf1a4faec14c408d3a70e0eb90a9`.

### Failed-run correction and first bounded discovery

The subsequent real normal-budget scenario failed on previously unasked `IP-1116 CONSUMES INCOMING` candidates `BP-1533`, `BP-1147` and `BP-1866`. Review found different correct outcomes: navigate through information management, reject CIS/account-operation as a semantic public-picture reader, and retain the genuine F5 input uncertainty at data preparation. Full-catalogue review extended that same uncertainty to the finite seven-binding set above. The displayed-view process input is separately permitted by L62; it does not resolve the unknown upstream datatype.

The next consistent response freeze contained **83 claims (36 REQUIRED, 47 OPTIONAL), 40 source contributions, 57 scopes and 298 scoring answers**. The actual Java fixture constructor, including positive/negative/open-path conflict checks, completed successfully. A diagnostic replay of the **186 records actually present in the captured file at inspection time** accepted 182 and rejected four obsolete IP scoring requests whose parent budgets preceded the reviewed change from 90/65 to 80/55. It found no additional missing authored decision in that particular captured input. This file and diagnostic are retained under `target/flood-relation-exclusions-authoring/input-identity-review/`. Those counts are a bounded historical replay, not the later discovery probe or a full successful scenario.

The independently frozen first discovery probe consumed exact copies of all three resources, the real runtime catalogue/overlay, the relevant Java sources and baseline classes. It exercised the actual relation search and disk checkpoint continuation under the normal per-pass limits. It reached a fixed point after **95 cycles and 2,244 attempted calls**: all 75 sources were assessed and no budget-pending work remained, but **237 full prompts were unknown** (115 root questions and 122 deeper questions), blocking 339 candidate decisions. Fourteen explicit F5 unresolved assessments remained separately visible. Those blocked prompts prevented verification of some already authored positive orientations, so source assessment and a budget fixed point alone did not establish complete search evidence.

The ten compatible query routes added by the new product role increase the old finite matrix from 348 to **358 source-part/type/direction/root routes**. The actual route index and complete unknown prompts are retained under `target/prompt-budget-review/discovery-20261017-ip2054-v1/`. These real inputs, complete source contributions and full candidate descriptions formed the finite review set. Legitimate additional functional representations received independent review before the answers were added; genuinely unrelated functions received explicit scoped reasons. None of the earlier unvisited scopes was assigned a negative merely because it had not been observed or lacked a positive claim.

### V2 response freeze and bounded verification

The consistent v2 corpus contained **104 claims (36 REQUIRED, 68 OPTIONAL), 40 source contributions, 57 scoring scopes and 298 scoring answers**. The 21 new relations were the narrowly conditioned optional representations L67–L87 described above; the original core, constraints and single input-identity question stayed unchanged. The exclusions contained 377 explicit rejection rules across the shared catalogue families and individually scoped functional boundaries, plus 14 exact open orientations. All actual constructor checks, including positive/exclusion/open ancestor-path conflicts, completed successfully. A structural comparison against the independently frozen first probe also confirms that every scoring answer, requirement/binding, source role, previous claim and previous permitted relation stayed unchanged; `discovery-v2/freeze-verification.json` records those boundaries and the hashes.

The bounded matcher audit accepted **237 of 237 actual full unknown prompts** from the first probe. It also evaluated each actually offered candidate separately, preserving its recorded source and prompt context, so an error in one candidate could not hide later members of the same batch: **339 of 339 candidate decisions were accepted**, with zero remaining full-prompt or candidate failures. The full prompts are original recorded inputs; the single-candidate inputs are explicitly labelled diagnostic derivatives. Results and exact rationales are retained in `target/flood-relation-exclusions-authoring/discovery-v2/actual-offered-scope-audit-final.json`. This is a recorded-input matcher audit, not a new live search, application execution, provider response or export result.

The second real search probe independently copied and hash-checked its v2 resources under `target/prompt-budget-review/discovery-20261017-ip2054-v2/`. It reached its bounded fixed point after **116 cycles and 2,759 calls**. All **104 claims were verified in both orientations (208)**, all 75 sources were assessed, and the 14 F5 unresolved assessments remained intact. It exposed **eight further unknown full prompts with 15 offered candidates**, concerning collaboration/data-science services versus the separate reliability/oversight/history roles, digital identity/cryptography versus protection processes, and information management versus prepared subscriber dispatch. At that point the complete candidate batches still needed semantic review: an answer for the first candidate could not stand in for the second. Those unanswered prompts prevented closure until the following independent review and fresh execution.

### Current v3 freeze: all fifteen actual candidates reviewed

All 15 offered candidates from those eight prompts were reviewed independently against their complete descriptions, actual source contributions and original clauses. The initial isolated-candidate check found seven already valid saved rejections and eight missing decisions. The final judgment was **14 scoped rejections and one narrowly conditional OPTIONAL match**, L88. The seven previously valid answers stayed word-for-word unchanged. The other seven new decisions distinguish communication/collaboration from A1 reliability/history or F2 administration, prepared F3 dispatch from information-lifecycle custody, and direct access enforcement from the separately assigned identity-lifecycle function.

The resulting v3 freeze contains **105 claims (36 REQUIRED, 69 OPTIONAL), 40 source contributions, 57 scoring scopes and 298 scoring answers**. The reference contains 19 core relations and 88 permitted additions, with the 20 constraints and exact one-question/seven-binding F5 boundary unchanged. The former lifecycle exclusion was narrowed only where the newly reviewed direct F4 function was established. No required function was demoted or new physical mechanism selected.

The fresh fixture constructor was conflict-free. The recorded-input audit accepted **8 of 8 full prompts and 15 of 15 actually offered candidates**, with zero missing matcher scopes. `target/flood-relation-exclusions-authoring/discovery-v3/individual-candidate-review.json` retains a separate entry for every candidate: full description, actual source role, canonical direction, original clause IDs, independent rationale and observed response. `before.json`, `after.json`, `audit-command.json` and `freeze-verification.json` retain the bounded before/after evidence and exact source/resource comparisons. This recorded-input result alone is not a full search or application pass.

The third real engine/checkpoint probe then ran the independently copied, hash-checked v3 resources to a fixed point with **117 cycles and 2,761 actual completion attempts**. It used the unchanged normal limits: 24 calls per pass, 32 sources, batch size 3, depth 8, 512 work items and 16,000 prompt characters. It assessed **75 of 75 sources**, with **433 source/root search tasks: 423 completed and exactly 10 unresolved tasks** containing the **14 exact orientations of the seven F5 bindings**. Pending work was zero. All **105 authored claims were verified in both orientations (210)**; there were **zero unknown prompts, zero unknown errors, no unverified authored orientation and no technical warnings**. The 14 retained questions all had the same exact canonical F5 wording and expected source/type/target identity. Their presence remains evidence of one missing input-identity fact, not seven independent missing requirements.

The complete v3 discovery evidence is under `target/prompt-budget-review/discovery-20261017-ip2054-v3/`; its `summary.json` has SHA-256 `045763d31f4dea35546caa26b293ed9623a245fea20c3fa0d63eb6882566832b`. This closes unknown authored answers for the actual reachable search paths on the frozen corpus. It does **not** claim strict search exhaustion: the genuine F5 question remains unresolved. The diagnostic used the real search engine and disk checkpoints with the authorized score map as input; it did not exercise classification, HTTP/provider admission, SQL, browser-driven production continuation or exports. The coordinated application run must still demonstrate those paths, the truthful partial/open-point status, the saved snapshot and the actual delivered architecture. No live-model quality or Enterprise Architect desktop compatibility conclusion follows from this probe.

### Effort and limits of complete continuation

The measured v3 discovery issued **2,761 distinct completion requests**, totalling **32,346,241 UTF-8 input-budget bytes including the actual response-schema envelope**: 41 EXTRACT, 2,510 NAVIGATE and 210 VERIFY requests. Prompt hashes and complete candidate scopes show no duplicate new provider question or repeated remote assessment of an already accepted question within this search. A read-only comparison of the actual responses with the frozen catalogue links all **530 opened parent branches** to a `DESCEND` in the same source/contribution/type/direction scope; each then offered exactly its real direct children. There were no unexplained descents or missing/extra children in that observed trace. This establishes the observed navigation accounting, not economic optimality.

Continuation still reconstructs search paths locally: **162,841 stored questions were replayed without a new completion/provider call**. The measured discovery is one fully continued relation search, not 117 independent verification passes. The [scenario](../../taxonomy-app/src/test/java/com/taxonomy/ScenarioArchitectureAcceptanceTest.java) deliberately selects `EXHAUSTIVE` with two independent verification passes and explicitly continues bounded partial work for QA coverage. The normal [manual Copilot defaults](../../taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/AiAutomationPolicy.java) are `METERED`, `FULL` and one verification pass; a fresh continuation budget requires an explicit, revision-bound [Continue action](../../taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/ProjectAnalysisContinuationService.java). The exhaustive QA total is therefore not automatic default consumption. These byte and local-replay measurements establish neither live-model latency/token cost nor the economic practicality of a complete real-model analysis. `economy.json`, `trace-accounting.json`, the full `attempts/` records and the frozen `prompt-catalogue.json` under the v3 discovery directory retain the evidence.

Current frozen resource SHA-256 values:

| Resource | SHA-256 |
|---|---|
| `flood-information.json` | `648bb5ce5bf1a03feb42b1a8ae22b48757c806746d2db766bd27e6203fc8b0b8` |
| `flood-quality-reference.json` | `efa10b90b4b0698bc8041ef57ccdf5d8f14789fd496f5e1f5711ba486becfe89` |
| `flood-relation-exclusions.json` | `68836303882a7798019e38002b74fe71c38741069e7ca148bc7133ef6a85461b` |

The reference's original 19 core relations, 20 constraints, original seven required-claim dispositions, requirement text and open physical decisions remain unchanged. The new six unresolved dispositions and permitted additions are independently reviewed refinements. The full scenario, complete source/search accounting, actual retained snapshot and delivered Sparx artifact remain the final evidence; this documentation does not substitute its authored expectations for those outputs.
