# Civilian quality corrections and Sparx OSLC AM read/pull

Follow-up to the civilian acceptance plan and accepted ADR 0009, authorized by
the user's request to finish the findings and continue #1075 without Sparx.
Preserve the real application/LLM-only playback boundary and existing common
integration identities, previews, reconciliation, journal and checkpoints.

## Tasks and verification

1. Correct `DecisionRationaleDocxRenderer` and the template body boundary.
   Regress empty break-only paragraphs and repeated narrow-column rationales in
   `DecisionRationaleTemplateRendererTest` and a focused document layout test.
   Place page breaks on meaningful headings; retain editable templates. Render
   repeated reasons once per section with explicit references and readable widths.
   Regenerate the actual civilian export, render it with LibreOffice, inspect all
   pages and record page count, empty-page and content-preservation evidence.
2. Complete the reference-case review as an integration-test architecture, with a
   sourced F1–F5/A1 traceability matrix and explicit implementation obligations.
   Extend `CivilianExportQa` with the existing Sparx XMI codec round-trip and
   independent format validation where available. No invented application nodes,
   altered fixture for screenshots, or certification by absent desktop products.
3. Implement the documented PCS OSLC AM read boundary in `taxonomy-export` and
   `taxonomy-interop`, with a distinct immutable profile and common integration
   service wiring. Verify official authentication/resource/paging contracts first.
   Test bounded XML/HTTP, endpoint allowlists, credential isolation, incomplete
   listings, stable GUID mapping, reviewed pull and durable retry. Reuse shared
   domain reconciliation. Do not advertise writes without atomic conflict and
   retry guarantees. Add accessible EN/DE configuration and operation guidance.
4. Run focused contracts, the real civilian acceptance, browser CI if affected,
   and exactly `./mvnw verify -DexcludedGroups="real-llm"`. One fresh whole-branch
   review follows implementation. Publish a follow-up PR; preserve the existing
   ready PR stack and keep actual EA/PCS/Visio acceptance explicitly unexecuted.

## Execution ledger

- Base: 0b2a8ca3, clean branch `fix/civilian-quality-and-sparx-oslc`.
- Word diagnosis: the template ends with an empty hard-break paragraph; generated
  sections add more empty break paragraphs. Seven equal columns repeat long
  exclusion rationales. The real baseline rendered to 87 pages, including an
  empty body page. Fix the representation, not the requirement or response corpus.
- Ruling: domain acceptance here means a reviewed test reference, not approval of
  a deployed public warning service. Source coverage and remaining operational
  obligations must remain separate and visible.
- Ruling: PCS contract tests are a separate transport test boundary; no synthetic
  PCS response counts as evidence of a real Enterprise Architect installation.
- Word RED: both Java layout regressions failed; the real archived DOCX also had
  33 break-only paragraphs. GREEN: both focused Java tests passed. The first
  independent render contains 67 rather than 87 pages, all reason texts and no
  empty page body. Subsequent spacing/aspect-ratio corrections are under review.
- Independent Structurizr 6.2.3 parsing found duplicate names in the actual export.
  Use custom elements with preserved identity/label properties: the flat taxonomy
  graph supplies no C4 software-system/container hierarchy. This also removes
  illegal top-level containers/components. LibreOffice Draw opens the real VSDX.
- The extended actual ArchiMate → reviewed canonical import → Sparx XMI test
  exposed a lost relation type: the exchange reader ignored the snapshot writer's
  `taxonomy.type` property. Preserve it. Explicitly reject generated candidates
  that fail native endpoint/type rules; record that review alongside the export.
- Ruling: OSLC AM v1 reads model package/element properties (including requirement
  elements), GUIDs and hierarchy; uncollected features/connectors/tags are explicit
  losses. It uses an administrator-supplied PCS session token reference, not generic
  Bearer authentication. Model login/SSO and conditional publication require later
  profiles/evidence. Page exhaustion is not authoritative deletion evidence.
- Official contract sources checked: Sparx 17.2 `oslc_user_cred`, `oslc_serv_provid`,
  `oslc_query_cap`, `ret_res_feat` and `oslc_res_shape` pages. The common bounded
  HTTP transport, model-owner binding and reviewed operation journal are reused.
- A concurrent Maven compilation invalidated the running acceptance classpath
  (NoClassDefFoundError). This was an execution error; subsequent reactor builds
  run sequentially. No application retry was introduced to mask it.

- Actual HTTP walkthrough GREEN after isolating its repository/working copy through
  REST (the original workspace correctly included pre-existing Copilot elements).
  No test-only database insertion or clearing is used. Review boundaries: 44
  generated candidates → 36 native relations → 8 mapped Sparx relations; all 38
  element labels and accepted connector semantics are preserved. Every rejection
  is saved, with the original snapshot unchanged.
- Independent rendering uses a fresh temporary LibreOffice output directory before
  copying results. Direct overwriting in the persistent QA directory triggered a
  renderer I/O abort. Text preservation joins report body text across page breaks,
  excluding the measured 24pt/796pt running furniture; diagram labels use raw
  reading order because coordinate order interleaves labels and crossing edges.
- Ruling: the dense full-graph Visio view is a semantic handoff, not presentation
  approval. Draw opens it and preserves labels; crossings and reversed/overlapping
  connector captions remain an explicit visual limitation. Native Visio is absent.
- The shared connector gains inbound selection validation independent of outbound
  publication. XMI and AM reuse the same Sparx semantic validator; AM advertises
  no publication capability. Immutable profile tags are checked against the actual
  source profile, not a hardcoded XMI tag.
- PCS reader RED: four tests fail against the stub. GREEN: authenticated actual
  HTTP, discovery, stale content, rejected projections/foreign endpoints, redirects,
  reflected credentials, paging loops and later-page failure contracts. The Spring
  workflow separately checks persisted failure/retry, native journal apply, replay
  without refetch and changed remote content before apply.

- Native document QA GREEN: 55 Word pages, 261 preserved text assertions, no empty
  body pages; all 55 pages visually inspected, with selected pages at full size.
  Draw opens the VSDX and all 38 semantic labels are present. Detailed hashes and
  source/run identity are in docs/qa/civilian-quality-followup.json.
- Whole-branch independent review (0b2a8ca3..7236df7): no Critical findings, one
  Important PCS contract defect, no established Minors. Structured stereotypes
  were incorrectly treated as literals. Official oslc_select_param and
  oslc_quick_ref documentation plus a runtime reproduction confirm the defect.
- Final: fixed nested PCS stereotype handling — documented typed RDF fixture
  readsDocumentedNestedStereotypeAndReportsAmbiguousMultipleNames RED→GREEN;
  HTTP reader also exercises nested stereotypes. 20 codec/XMI/HTTP tests GREEN.
  Multiple/unknown structures are explicit losses requiring a type decision;
  no arbitrary canonical type is selected. Full suite remains the final gate.
- Final: Ruling: actual EA/PCS/Visio compatibility stays NOT_EXECUTED because no
  product is available; contract and Draw results cannot establish it. Cost if
  treated otherwise: users could rely on untested interoperability.
- Final: Ruling: remote reads use observed collection evidence, not an atomic
  provider snapshot or conditional write. No write capability is advertised.
  Cost if misunderstood: remote changes after observation cannot be prevented.
- Final: Ruling: dense Visio captions/crossings remain disclosed as layout work;
  the fixture tests semantic handoff and its focused browser view, not graphical
  approval of a 38-node poster. Cost: manual rearrangement for presentation.
- Final: Ruling: the sourced flood case is accepted only as an integration-test
  reference, with F3/F4/F5/A1 coverage obligations retained. Cost if misused:
  deployment would lack required account, delivery and operational contracts.
- The review did not assume pending build/browser success. Both are required
  execution steps; no review findings or unexecuted product checks are hidden.
