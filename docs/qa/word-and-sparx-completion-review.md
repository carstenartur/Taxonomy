# Word export and #1075 completion review

Assessment: 2026-09-20, after the Copilot corrections for #1084–#1087.
Green tests establish the documented contract. They do not establish that every
requested report feature is present or that Sparx compatibility has been proven.

## What the Word files actually contain

The inspected artifacts are the genuine civilian acceptance outputs from
[run 35487419613](https://github.com/carstenartur/Taxonomy/actions/runs/35487419613),
with the hashes and renderer evidence in [civilian-quality-followup.json](civilian-quality-followup.json).
The review corrections do not change either Word renderer.

| Output / feature | Observed implementation and evidence |
|---|---|
| Decision report, `decision.docx` | 30 decision chapters; 43 embedded decision-diagram panels, with rationale, score, alternatives and provenance. |
| Decision tree | Parent/child decisions are illustrated within chapters. There is no consolidated navigable whole-tree overview. |
| Architecture graph in the decision report | Absent. The report model/renderer does not embed the persisted architecture scene. The architecture SVG/PDF/Visio downloads are separate files. |
| Legacy analysis report, `report.docx` | No embedded image or diagram. `ArchitectureReportService.renderDocx` converts Markdown into paragraphs; table rows become tab-separated text. It is not equivalent to the decision report. |
| Pagination of the decision report | 52 pages with LibreOffice 24, 55 with LibreOffice 26, compared with the previous 87. No empty body pages or isolated rationale-box headings; 261 text assertions pass. |
| Actual Microsoft Word acceptance | Not established by LibreOffice rendering. Existing Word/template acceptance work remains separate. |
| Overall publication quality | Improved and suitable for technical review, but not approved as a complete, polished architecture deliverable. Dense overall graph layouts and the legacy Word path remain open. |

The output should therefore **not be described as state of the art**. The next
report slice should produce one coherent document from the same frozen snapshot:

1. A concise requirement, scope, recommendation and explicitly unresolved gaps.
2. An architecture overview plus readable detail views, with consistent legend,
   captions, element identifiers and references to the relevant decisions.
3. A navigable decision-tree overview linked to the complete chapter evidence.
4. Real Word tables, headings, contents/navigation, consistent language and
   accessible image descriptions across the supported Word export paths.
5. Semantic completeness checks and visual acceptance at normal reading scale,
   including a representative Microsoft Word execution when available.

This remains implementation work, not a reason to withhold the independently
useful and verified review fixes. The civilian example also retains the domain
coverage gaps documented in [civilian-reference-review.md](civilian-reference-review.md).

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
