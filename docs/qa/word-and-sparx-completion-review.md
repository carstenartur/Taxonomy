# Word export and #1075 completion review

Word assessment updated 2026-09-20 for the frozen-report completion and final
quality fixes. The native/publication assessment below is retained for its owning
workstream. Structural tests and LibreOffice output do not establish Microsoft
Word or Sparx product compatibility.

## What the Word files contain

Both snapshot DOCX routes now use one saved analysis: the decision hierarchy and
persisted architecture graph are composed at matching project, requirement,
version, workspace, branch and commit coordinates. They do not regenerate the
architecture from current catalogue, preferences or pending proposals.

| Output / feature | Implemented behavior and evidence |
|---|---|
| Snapshot decision report, `decision.docx` | Complete decision chapters plus the frozen architecture overview, bounded readable detail panels, legend, native element/relation inventories, requirement, recommendation, gaps and provenance. |
| Decision tree | Consolidated whole-tree table retains alternatives and distinct missing/zero scores, with internal links to every chapter; cycles, inconsistent parents and leaf/parent contradictions are rejected. |
| Snapshot architecture report, `report.docx` | Standalone rendering of the same frozen graph/evidence, with semantic headings, native tables, captions, accessible image descriptions, TOC/static links and graph/snapshot properties. Localized running identity and page fields accompany an explicit body font. |
| Legacy live `/api/report/docx` | Structured native Word tables and the supplied `RequirementArchitectureView` graph; it remains an ad-hoc live report and does not invent immutable decision evidence. Pattern percentages retain their 0–100 semantics and bounded finite output. |
| Custom decision DOTX | Existing cover, header/footer, logos and body defaults remain administrator-controlled. Body/header/footer/footnote/endnote bookmark identities are reserved; conflicting generated anchor names are rejected rather than renaming administrator links. |
| Generated language | English/German labels include service-generated frozen provenance phrases. Saved source labels/reasons and administrator-authored template prose are preserved. |
| Actual Microsoft Word acceptance | **NOT_EXECUTED**. POI structure and LibreOffice rendering are useful evidence, not representative Microsoft Word execution or accessibility certification. |

Evidence must be read at its recorded source revision:

- The older [run 35487419613](https://github.com/carstenartur/Taxonomy/actions/runs/35487419613)
  and [civilian-quality-followup.json](civilian-quality-followup.json) describe the
  earlier 52/55-page decision-only renderer. They do not describe the completed
  frozen Word implementation.
- Corrected [civilian run 35501208857](https://github.com/carstenartur/Taxonomy/actions/runs/35501208857)
  exercised both real browser Word downloads and the saved-source mutation
  regression. Its retained LibreOffice 24.2 evidence has 68 decision pages and
  10 standalone pages, with 344/85 semantic assertions and no empty body pages.
  Those counts belong to that pre-final-quality run, not the later font,
  footer, navigation and note-bookmark changes.
- Final-quality regressions cover materialized custom-note bookmarks and links,
  score-independent tree structure, generated German metadata and Word text,
  standalone furniture/defaults, native TOC/navigation and accepted controller
  locale variants. The selected local covering run passed 68 tests. Its refreshed
  LibreOfficeDev 26.8 output has 71 decision pages and 11 standalone pages,
  344/85 semantic assertions, no empty body pages, and paired snapshot
  `02127c46-192d-4ca4-acbd-688bff037708`. Exact source/tree and artifact hashes
  are retained in the final-quality implementation handoff. Full-page visual
  acceptance and final external CI remain release gates; earlier page counts
  are not substituted for these checks. Microsoft Word execution remains separate.

The civilian example still retains the domain/reference-case gaps documented in
[civilian-reference-review.md](civilian-reference-review.md). Complete report
transport does not establish complete architecture subject matter. Dense Visio
connector layout is a separate graphical-quality finding and is not approved by
Word semantic or image checks. The Word completion should not be presented as
unqualified product compatibility or as proof that all #1075 work is complete.

## What remains from #1075 without requiring a Sparx installation

The current delivery implements the common review/identity/checkpoint machinery,
the documented XMI subset and a bounded PCS AM package/element read/pull profile.
It does **not** exhaust the parts of [#1075](https://github.com/carstenartur/Taxonomy/issues/1075)
that can be developed without the proprietary products.

| Remaining slice | Work possible without EA/PCS | Evidence still requiring the product |
|---|---|---|
| AM connectors and tagged values | Parse documented feature representations, preserve direction/identity/properties, fetch within aggregate limits, and cover paginated/partial/stale responses with HTTP contract tests. | Verify actual resource shapes, token handling and supported provider versions. |
| AM attributes and operations | Define the bounded supported extension/loss representation and test feature retrieval and round-trip meaning where a canonical mapping exists. | Verify actual EA/PCS representations and behavior. |
| Push/Synchronize and partial publication | Implement and test the shared item-result journal, retry/recovery state machine and a conditional publication contract, including rejection of providers without the required capabilities. | Demonstrate atomic expected-state checks and safe creation/retry semantics before enabling PCS writes. |
| Package editing / additional native endpoint mappings | Extend native commands and review/remap UI where the domain semantics can be represented faithfully; test them through the application. | Verify return imports and preservation of EA-specific content/layout. |
| Optional SBPI integration | Design the optional protocol and test Taxonomy-side contracts after publication semantics are stable. | Compile/run and accept the actual Sparx-side plugin. |
| Compatibility matrix | Maintain the runnable acceptance scenario and evidence schema. | Execute EA import/edit/export and PCS operations; synthetic tests cannot fill this column. |

The feasibility assessment follows Sparx's documented
[read endpoints for connectors, tags, attributes and operations](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/ret_res_feat.html).
Its [update documentation](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_upd_resources.html)
describes POST updates but does not establish the atomic expected-version and
idempotent-create guarantees required by #1075. A preflight read followed by an
unconditional write does not satisfy those guarantees.

Consequently #1075 stays open. The status in [sparx-compatibility.json](sparx-compatibility.json)
must remain `NOT_EXECUTED` for real-product compatibility until actual evidence exists.
