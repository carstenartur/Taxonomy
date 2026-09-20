# Word export and #1075 completion review

Assessment updated 2026-09-20 for the accepted frozen Word/native foundations,
Visio graphical completion and implemented conditional publication. Current exact
source/CI and remaining release gates are recorded in [completion evidence](1075-completion-evidence.md). Structural tests and LibreOffice output do not establish Microsoft
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
  are retained in the final-quality implementation handoff. That Word slice was
  subsequently accepted and merged in [PR 1088](https://github.com/carstenartur/Taxonomy/pull/1088)
  at `085f620a5141de8fc26140137010b59414ddead1`, tree
  `b17e05137e761bc379e8d3c96856ab5b29d764b5`. All 116 local pages, all 79 final
  CI pages and five browser images were visually inspected. Final
  [CI 35507510981](https://github.com/carstenartur/Taxonomy/actions/runs/35507510981)
  has 68 decision + 11 standalone pages and 344/85 checks with no empty body pages.
  Microsoft Word execution remains separate.

The civilian example still retains the domain/reference-case gaps documented in
[civilian-reference-review.md](civilian-reference-review.md). Complete report
transport does not establish complete architecture subject matter. The Visio graphical finding has its own accepted overview/detail implementation:
all 11 actual HTTP/Draw pages and all 44 relation direction/type captions were
visually inspected. Source `9e0fb1b8a5d7a6612a16fa9bf5e88ed46b403c0a` plus the
accepted byte-budget fix `d3a1a272cc6fe87c52eee9e206a3ac64cfef9c26` retains the
same 38 nodes/44 relations. Dense overview omissions are explicit and covered by
detail pages. LibreOffice 24.2 pages from CI 35524367930 were also reviewed; the 35525893725
page hashes were checked with unchanged-dependency visual evidence reused. This is not
Microsoft Visio certification; see [Visio evidence](visio-graphical-quality.json).

## Feasible #1075 slices now implemented

The merged native foundation [PR 1089](https://github.com/carstenartur/Taxonomy/pull/1089)
at `b45711981d8e1bdaf38642b4c3469ae28bd76a12` passed its required final CI.
Conditional publication and the optional client contract are implemented in
[PR 1094](https://github.com/carstenartur/Taxonomy/pull/1094); its final combined
review/CI remain release gates. All fourteen corrected actual EN/DE native/publication
images from civilian CI 35525893725 were visually accepted and are retained in the
[image gallery](conditional-publication-browser.md).

| Slice | Implemented without proprietary execution | Product acceptance still unexecuted |
|---|---|---|
| AM connectors/tags/attributes/operations | Shared bounded v2 feature semantics, explicit losses, complete scoped traversal and HTTP contract tests. | Actual versioned EA/PCS representations, token handling and round trips. |
| Native packages and mappings | Typed package/placement operations, explicit normalized endpoints and requirement mappings, real application/Git and cross-JVM replay. | EA-specific layout/content preservation on return import. |
| Push/Synchronize | Directed review, atomic local staging, durable item receipts, independent provider/client restart matrix, frozen retry and explicit linked reconciliation. API/UI fail closed for unverified adapters. | Live PCS CAS, atomic creation, durable key/receipt recovery before enabling writes. |
| Optional SBPI | Authenticated action/API/deep-link client design using the shared protocol and UI. Taxonomy works without a plugin. | Compile and execute the proprietary host/SDK plugin if desired. |
| Compatibility matrix | Runnable contract acceptance and versioned evidence with honest test-only provider identity. | Actual EA/PCS/MS Word/MS Visio execution. |

See [completion evidence and decisions](1075-completion-evidence.md) for exact
source trees, test counts, real images, failed attempts and pending release checks.

The feasibility assessment follows Sparx's documented
[read endpoints for connectors, tags, attributes and operations](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/ret_res_feat.html).
Its [update documentation](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_upd_resources.html)
describes POST updates but does not establish the atomic expected-version and
idempotent-create guarantees required by #1075. A preflight read followed by an
unconditional write does not satisfy those guarantees.

Actual product-compatibility claims remain open; implemented contract coverage does not fill them. The status in [sparx-compatibility.json](sparx-compatibility.json)
must remain `NOT_EXECUTED` for real-product compatibility until actual evidence exists.
