# Word completion design inventory

## Binding user-path follow-through

Expose the new frozen architecture DOCX in the actual architecture workbench toolbar and API client. Keep the complete decision DOCX reachable through its existing snapshot route; a workbench shortcut can reuse it. Extend the real civilian browser controls/download evidence for the added button. The existing live `ArchitectureReport` already contains a `RequirementArchitectureView`; its structured DOCX adapter must render that supplied architecture graph as well as tables, without inventing frozen decision evidence. Clearly distinguish ad-hoc live report provenance from the saved snapshot report.

Extend `taxonomy-tooling/.../CivilianDocumentQa` to render and inspect both `decision.docx` and `report.docx` alongside Visio. The previous 65-page decision ceiling covered a smaller section set: retain meaningful density/readability checks and use actual rendered evidence to justify any revised budget. Do not simply raise it to hide blank pages, orphan sections or avoidable pagination.

## Required design

The immutable source for both Word artifacts must be the selected portfolio snapshot, not a second call to `ArchitectureReportService.generateReport`. `DecisionRationaleSnapshotReportService` currently reads frozen decision evidence, while `ArchitectureWorkbenchService.load` reads the persisted `AnalysisResult.architectureView` and produces the canonical `DiagramModel`/`DiagramScene`. The civilian test then breaks this contract by POSTing the snapshot scores to `/api/report/*`, which re-runs the current architecture, gap, pattern, recommendation, preference, and pending-proposal services. That path can change after catalogue, preference, proposal, or branch changes.

Create these architecture-owned, format-neutral records:

- `taxonomy-architecture/src/main/java/com/taxonomy/architecture/report/ArchitectureReportDocument.java`
  - `String title`, `String languageTag`, `String requirement`, `String scope`, `String recommendation`
  - `List<String> unresolvedGaps`
  - `DiagramModel diagram`, `DiagramScene scene`
  - `List<ElementRow> elements`, `List<RelationRow> relations`, `List<LegendEntry> legend`
  - `DecisionTreeOverview decisionTree`
  - `SnapshotEvidence evidence` containing project/requirement/version/snapshot/workspace/branch/commit/provider/model/taxonomy fingerprint and canonical graph SHA-256
  - constructors defensively copy collections; IDs and ordering are deterministic.
- `taxonomy-architecture/src/main/java/com/taxonomy/architecture/report/DecisionTreeOverview.java`
  - ordered `DecisionTreeRow(depth, code, title, Integer score, Disposition disposition, Integer chapterNumber, String bookmark)` entries.
  - `null` score remains *not evaluated* and `0` remains *evaluated and rejected*.
- Extend `DecisionRationaleReport` with optional `ArchitectureReportDocument architecture` plus a source-compatible constructor and `withArchitecture(...)`. Do not replace its renderer model type: the template decorator depends on `DecisionRationaleReport`.

The canonical graph SHA-256 is a **content digest**: saved node labels are frozen evidence and remain hashed alongside node/edge semantic fields. Changing a saved node label changes this digest. Report, diagram and scene presentation titles are excluded, so changing only those titles preserves the digest. This is not a name-insensitive identity digest; the existing `taxonomy-frozen-graph-v1` domain marker and hash algorithm remain unchanged.

Create `taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/report/SnapshotWordReportService.java` with:

```java
public record Source(
        DecisionRationaleReport decision,
        ArchitectureReportDocument architecture) {}

@Transactional(readOnly = true)
public Source load(Long projectId, String snapshotId, String username,
                   WorkspaceContext context, Locale locale);
```

`load` calls the existing `DecisionRationaleSnapshotReportService.generate(...)` and `ArchitectureWorkbenchService.load(...)` for the exact same normalized coordinates, validates that project, requirement, snapshot, requirement version, workspace, branch, and commit agree, derives narrative text only from those two frozen results, builds the complete decision overview from `decision.chapters()`, and returns `decision.withArchitecture(architecture)` and the same `architecture` object. Do not call `ArchitectureReportService`, current taxonomy services, current preferences, proposal repositories, or an LLM. Fail with a conflict on coordinate/provenance disagreement, missing architecture view, incomplete canonical graph, or contradictory decision-tree edges; never silently combine or truncate evidence.

Keep `GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/{formatId}` and all ad-hoc routes unchanged. Modify `DecisionRationaleSnapshotReportController` to use `SnapshotWordReportService` for DOCX and pass its enriched decision model through the existing registry. Select this composition from the resolved, normalized renderer format identity, so accepted case/whitespace request variants follow the same frozen path. Add `SnapshotArchitectureReportController` under the same package with `GET /api/projects/{projectId}/snapshots/{snapshotId}/architecture-report/docx?language=...`; this is the frozen replacement used for `report.docx`. Leave `/api/report/docx` available for legacy/ad-hoc callers, but make its renderer structurally correct as described below. Expose the snapshot ID and canonical graph hash in headers/custom properties so acceptance can prove that both DOCX files came from one source.

## Word renderer composition

Move DOCX mechanics out of `ArchitectureReportService.renderDocx`; retain that public method as a compatibility delegate. Add:

- `taxonomy-architecture/.../report/ArchitectureReportDocxRenderer.java`: renders `ArchitectureReportDocument`; also adapts the existing domain `ArchitectureReport` for `/api/report/docx` without parsing Markdown.
- `taxonomy-architecture/.../report/ArchitectureWordSectionRenderer.java`: writes requirement, scope, recommendation, unresolved gaps, architecture figures, legend, element table, relation table, and provenance. Both DOCX renderers call this class.
- `taxonomy-architecture/.../report/DecisionTreeWordSectionRenderer.java`: writes the complete indented navigation table and internal links.
- `taxonomy-architecture/.../report/WordDocumentWriter.java`: the only OOXML helper for semantic headings, bookmarks/hyperlinks, real tables, repeating header rows, captions, images/alt descriptions, TOC fields, core/custom properties, and page-break/keep rules.
- `taxonomy-architecture/.../report/ArchitectureFigurePlanner.java`: produces one whole-graph overview and bounded detail panels from the same `DiagramModel`/`DiagramScene`.
- `taxonomy-architecture/.../report/ArchitectureFigureRenderer.java`: deterministic Java2D PNG renderer returning `RenderedFigure(id, png, width, height, caption, altDescription, nodeIds, edgeIds)`.

`DocxReportRendererExtension` should inject `ArchitectureReportDocxRenderer`; it must no longer delegate to Markdown conversion. `DecisionRationaleDocxRenderer.writeReportBody(...)` remains the single body entry point used by standalone and template-backed rendering, but its order becomes: contents/navigation, executive summary, requirement/scope/recommendation/gaps, architecture overview/details, decision-tree overview, decision chapters, evidence appendix. This keeps `DecisionRationaleTemplateRendererDecorator` and `DecisionRationaleTemplateRenderer` intact: the current DOTX is still opened, tokens are replaced, `{{taxonomy.report.body}}` is removed, and the enriched body is appended. Template identity properties and response headers remain unchanged.

`WordDocumentWriter` must apply actual `Title`, `Heading1`, `Heading2`, `Heading3`, and `Caption` paragraph styles, creating a fallback style only when the opened document lacks it and never overwriting a custom template style. Insert a `TOC \\o "1-3" \\h \\z \\u` field and set `w:updateFields`, but also emit a deterministic linked navigation table because LibreOffice/headless renderers and protected Word sessions may not refresh a TOC. Chapter headings receive stable bookmarks such as `decision_chapter_12_BP_1000`; the decision overview links every chapter-bearing parent to that bookmark. Bookmarks must use unique numeric IDs and sanitized names.

Captions are `Caption` paragraphs with `SEQ Figure` or `SEQ Table` fields, not italic body text. Every embedded picture must set non-visual drawing `name`, `title`, and `descr`; setting alt text must fail rendering if OOXML cannot be updated rather than swallowing the error as `setLastPictureAltText` currently does. Alt descriptions identify the view purpose, panel number, included node IDs, relation types, and whether boundary nodes repeat. Element and relationship inventories, score/rationale summaries, legend, evidence, and unresolved gaps are actual `XWPFTable` structures. Remove tab-separated pseudo-tables from the legacy renderer.

## Diagram and tree completeness

The overview uses the exact frozen full scene and is allowed to be a compact orientation aid. Detail panels provide reading-scale evidence. Partition deterministically over sorted edges with limits of at most 12 unique semantic nodes and 18 relations per panel, repeating boundary nodes where required; then add bounded panels for isolated nodes. Relayout each semantic subset with `LayeredDiagramLayoutService` while preserving source IDs, labels, relation direction/type/category, scores, anchors, and container membership. The planner must assert that the union of detail panel node IDs and edge IDs equals the full source graph. It may repeat evidence but must not omit it. Add an explicit document policy ceiling and reject an over-limit graph with a clear conflict rather than generate an unbounded DOCX or silently truncate it.

Build the decision overview from all chapter parents and all direct children, not only the leading path. Reconstruct parent/child edges, retain every alternative, detect cycles and incompatible duplicate nodes, order roots and children by the report's existing deterministic order, and append orphan components with an explicit warning. The overview table carries depth, code/title, score, disposition, and chapter link. A missing score says `Not evaluated`/`Nicht bewertet`; zero says `0%` and rejected. The union of overview chapter links must equal the set of rendered chapter bookmarks.

Use one shared bilingual label source (replace/expand `DecisionReportLabels`) for all generated Word headings, captions, legends, statuses, table headers, alt descriptions, TOC/navigation copy, `unknown`, and generated notices. Persisted requirement text, node titles, reasons, policy titles, and administrator-authored template text are source evidence and are not translated. The bundled DOTX currently contains fixed bilingual strings and an English eyebrow; optionally tokenise those strings and update the bundled resource, but do not make new optional tokens mandatory or overwrite an already customised Git template. Language-consistency tests should therefore cover generated content separately from administrator-controlled cover text.

## Exact existing files to modify

- `DecisionRationaleReport.java`: optional architecture document and compatibility constructor.
- `DecisionRationaleDocxRenderer.java`: compose shared semantic sections, real heading styles/TOC/captions/bookmarks, strict alt metadata; remove duplicate `basedOnCommit` appendix row.
- `DecisionReportLabels.java`: make generated copy complete and consistently English/German, or delegate to a new shared `ReportLabels`.
- `DecisionChapterDiagramRenderer.java`: keep chapter panels, but let captions/bookmarks/alt metadata be owned by the Word writer.
- `ArchitectureReportService.java`: keep analysis and Markdown/HTML behavior; replace `renderDocx` implementation with the new renderer delegate.
- `DocxReportRendererExtension.java`: inject/use the new structured renderer.
- `DecisionRationaleSnapshotReportController.java` and new snapshot controller/service: compose exact frozen report source and preserve headers/cache behavior.
- `CivilianArchitectureAcceptanceTest.java`: download `report.docx` from the frozen snapshot architecture-report route; do not POST frozen scores into the live legacy generator for Word acceptance.
- `CivilianExportQa.java`: inspect structure and source parity, not only extracted text.
- `DecisionRationaleTemplateRenderer.java` and `DecisionRationaleTemplateRendererDecorator.java`: no route/model bypass; only adapt to the enriched body and retain custom properties.
- `DecisionRationaleTemplateContract.java` and `document-templates/decision-rationale-report.dotx`: only add optional localized cover tokens/styles if chosen; preserve the marker and all existing custom-template compatibility.
- Update English/German report/template/API documentation so snapshot Word is the reproducible route and `/api/report/docx` is described as ad-hoc/live.

## Regression and visual acceptance

1. `SnapshotWordReportServiceTest`: mutate live catalogue/preferences/proposals after saving a fixture snapshot and prove both returned models retain identical snapshot/version/commit/graph hash, element IDs, relation IDs, scores, and requirement text; verify no live report service is invoked.
2. `ArchitectureFigurePlannerTest`: dense star, long chain, disconnected components, isolated nodes, more than 12 nodes, more than 18 edges, and an over-policy graph. Assert deterministic output, panel bounds, and exact node/edge union.
3. `DecisionTreeOverviewTest`: multiple roots, shared prose, zero versus missing, orphan chapter, deep tree, and cycle/contradictory edge rejection. Assert overview links and chapter bookmarks are a bijection.
4. `ArchitectureReportDocxRendererTest` and expanded `DecisionRationaleDocxLayoutTest`: unzip DOCX and assert real `w:tbl`, heading styles, TOC instruction/update setting, bookmark/hyperlink pairs, `Caption` plus `SEQ` fields, repeating table headers, non-splitting bounded rows, drawing `descr`/title, all graph IDs, and absence of tab-separated Markdown table lines.
5. Expand `DecisionRationaleTemplateRendererTest`: custom header/footer/logo/styles survive byte materialization; the enriched architecture/tree body is present; template Git commit/SHA/schema properties and HTTP headers remain exact. A data-free template preview may omit architecture but must remain valid.
6. Parameterize EN/DE Word tests. Assert all generated labels belong to the requested language while persisted evidence is byte-for-byte unchanged. Do not fail on administrator-authored static cover wording.
7. Expand `CivilianExportQa`: both DOCX files contain the same snapshot ID and graph SHA; decision DOCX contains every decision chapter and linked overview row; both contain overview plus multiple detail figures, legend, captions, alt descriptions, element/relation tables, requirement/recommendation/gaps, and every canonical semantic node/relation ID.
8. Render both artifacts with LibreOffice to PDF and all pages to images. Assert no blank body pages, clipped figures, isolated headings/captions, split bounded rows, or detail panel below the declared minimum reading scale. Retain page images/contact sheet for human review at 100% zoom. Run the same fixture in a representative installed Microsoft Word build when available and record application/version/output hash/screenshots as evidence rather than treating LibreOffice as Word certification.

## Scope and semantic traps

- The current civilian `report.docx` is not snapshot-safe; passing frozen scores to a live aggregation service does not freeze taxonomy, preferences, relations, recommendations, or pending proposals.
- `DiagramProjectionService.project(...)` reapplies current selection policy. Snapshot rendering must use `Projection.diagram()/scene()` (or `PersistedDiagramProjection.project`) and must never curate with the current policy.
- A single scaled whole graph cannot be both complete and readable. Treat it as overview and prove complete bounded detail coverage.
- Word TOC fields are client-updated. The linked static overview is the deterministic navigation contract; the TOC field is an additional native Word facility.
- Wide figures must fit the printable area; image plus caption must stay together, while large tables must repeat headers and allow page breaks between rows. Avoid landscape section changes unless header/footer inheritance is explicitly tested against custom DOTX files.
- PNG dimensions and heap usage need hard ceilings. Estimate decoded pixels before allocation and fail cleanly; do not let many large panels exhaust the JVM.
- Chapter bookmark names must be sanitized and collision-safe even for long/non-ASCII codes. OOXML bookmark IDs are document-global.
- Existing customised templates are intentionally never overwritten. Generated-body language can be guaranteed; static custom cover language cannot be changed safely by report code.
- Do not claim Microsoft Word acceptance from Apache POI parsing or LibreOffice rendering.
