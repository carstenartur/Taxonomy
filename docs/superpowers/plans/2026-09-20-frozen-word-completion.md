# Frozen Word architecture reports implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce complete, navigable architecture and decision Word reports from one frozen saved analysis, with readable graph evidence and verifiable semantic completeness.

**Architecture:** Architecture-owned immutable report records carry the frozen graph, decision overview and provenance. A portfolio service composes the saved decision and workbench projections. Both DOCX routes use shared structured OOXML sections, retaining the existing template decorator and legacy API compatibility.

**Tech Stack:** Java 21 target (local verification JDK 25), Spring, Apache POI, Java2D, JUnit, existing Maven reactor and LibreOffice visual acceptance.

**Spec:** `docs/superpowers/specs/2026-09-20-frozen-word-completion.md`

## Global Constraints

- Preserve the repository's Java 21 language/API target; local JDK 25 is only verification infrastructure.
- Both snapshot Word artifacts must derive from the exact same immutable saved analysis and frozen canonical graph; do not regenerate evidence from live taxonomy, preferences, proposals, selections or LLM calls.
- Preserve the existing `DecisionRationaleReport` renderer model type and custom DOTX body-marker/decorator path.
- Detail panels contain at most 12 unique semantic nodes and 18 relations; their union covers every source graph node and edge.
- A missing decision score remains not evaluated; zero remains evaluated and rejected.
- Generated content supports English and German; preserve source evidence and administrator-authored template content unchanged.
- Use genuine Word headings, tables, navigation, captions and image descriptions. Never silently truncate diagrams or swallow inaccessible image metadata failures.
- Keep architecture ownership and module dependency direction; no new Python file in the repository.
- Only one Maven reactor may run at a time. Use `/workspace/scratch/dae1667028d4/verify-repo.py` in this worktree for local Maven commands.
- LibreOffice/POI tests are not Microsoft Word compatibility certification. Real product evidence stays explicitly unexecuted where unavailable.

## Review Focus

- Frozen snapshots remain reproducible after the current catalogue, branch or selection policy changes; assert graph identity and absence of calls to live report generation.
- Wide, disconnected and dense graphs remain complete and readable; assert exact coverage, image bounds and deterministic rejection at the document ceiling.
- Existing custom templates keep their styles, headers, footers and identity while receiving all new report sections.
- Deep/disconnected decision trees retain every chapter link, detect contradictory edges and keep zero distinct from missing scores.
- OOXML navigation, image accessibility and pagination work without automatic TOC refresh and do not depend on English-only generated labels.

### Task 1: Complete both frozen Word report paths

**Files:**
- Create: `taxonomy-architecture/src/main/java/com/taxonomy/architecture/report/ArchitectureReportDocument.java`, `DecisionTreeOverview.java`, `ArchitectureFigurePlanner.java`, `ArchitectureFigureRenderer.java`, `WordDocumentWriter.java`, `ArchitectureWordSectionRenderer.java`, `DecisionTreeWordSectionRenderer.java`, `ArchitectureReportDocxRenderer.java`.
- Modify: `taxonomy-architecture/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleReport.java`, `DecisionRationaleDocxRenderer.java`, `DecisionReportLabels.java`; the actual existing report extension and `service/ArchitectureReportService.java`.
- Create: `taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/report/SnapshotWordReportService.java` and snapshot architecture report controller beside the existing snapshot decision report controller.
- Modify: existing snapshot decision controller, template renderer/decorator only where required for enriched body/provenance, civilian acceptance and export QA classes, report API/template documentation in EN/DE.
- Modify: workbench toolbar/template and actual export/API JavaScript clients, EN/DE download labels and `CivilianBrowserWalkthrough` for the user-reachable frozen Word action; `taxonomy-tooling/src/main/java/com/taxonomy/tooling/CivilianDocumentQa.java` and its tests to render both Word files.
- Test: architecture `ArchitectureFigurePlannerTest`, `DecisionTreeOverviewTest`, `ArchitectureReportDocxRendererTest`, existing `DecisionRationaleDocxLayoutTest` and template tests; portfolio `SnapshotWordReportServiceTest`; app `CivilianArchitectureAcceptanceTest` and `CivilianExportQa`.

**Interfaces:**
- Consumes: `DecisionRationaleSnapshotReportService.generate(Long,String,String,WorkspaceContext,Locale)` and `ArchitectureWorkbenchService.load(Long,String,String,WorkspaceContext)` at identical normalized coordinates; existing persisted diagram projection and template registry.
- Produces: `SnapshotWordReportService.Source(DecisionRationaleReport decision, ArchitectureReportDocument architecture)` and `Source load(Long projectId, String snapshotId, String username, WorkspaceContext context, Locale locale)`; `DecisionRationaleReport.withArchitecture(ArchitectureReportDocument)` with source-compatible constructors.
- Produces: `GET /api/projects/{projectId}/snapshots/{snapshotId}/architecture-report/docx?language=en` (also `de`); existing snapshot decision DOCX uses the enriched decision model through the same extension registry. Legacy `/api/report/docx` remains structurally correct through a structured renderer adapter.
- Produces: deterministic frozen graph SHA-256 and snapshot identity exposed in both artifact properties and response headers; semantic coverage/structure checks run by the existing civilian pipeline.
- Produces: accessible frozen architecture Word download from the workbench, real browser download/control evidence, and graph rendering from the supplied architecture view in the legacy live DOCX adapter (without claiming frozen decision evidence there).

- [ ] **Step 1: Establish failing behavior tests at the owning boundary.** Build small immutable `DiagramModel`/`DiagramScene` fixtures using existing constructors and test planner completeness, dense/disconnected graphs and the explicit policy ceiling. Example assertions after fixture setup:

```java
var panels = planner.plan(diagram, scene).details();
assertThat(panels).allSatisfy(panel -> {
    assertThat(panel.nodeIds()).hasSizeLessThanOrEqualTo(12);
    assertThat(panel.edgeIds()).hasSizeLessThanOrEqualTo(18);
});
assertThat(panels.stream().flatMap(p -> p.nodeIds().stream()).collect(toSet()))
    .containsExactlyInAnyOrderElementsOf(sourceNodeIds);
assertThat(panels.stream().flatMap(p -> p.edgeIds().stream()).collect(toSet()))
    .containsExactlyInAnyOrderElementsOf(sourceEdgeIds);
```

Add service fixtures proving saved requirement/version/commit/graph parity, a tree with zero and missing scores and stable chapter bookmark coverage, and DOCX tests inspecting actual tables, headings, fields, captions, image descriptions and template preservation. Tests must assert user-visible semantics, not implementation call counts alone.

- [ ] **Step 2: Run focused tests and record RED.** Run the owning module reactor with `python /workspace/scratch/dae1667028d4/verify-repo.py -pl taxonomy-portfolio -am test -Dtest=ArchitectureFigurePlannerTest,DecisionTreeOverviewTest,ArchitectureReportDocxRendererTest,SnapshotWordReportServiceTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups=real-llm`. Compilation failure for missing introduced types is acceptable only for the first interface baseline; then obtain meaningful assertion failures for the prior behavior.

- [ ] **Step 3: Implement frozen composition and structured rendering.** Build the immutable format-neutral document and validate saved identity/graph consistency before rendering. Preserve the existing report model/decorator, carrying the optional architecture through all adapters and copy constructors. The composition has this contract:

```java
@Transactional(readOnly = true)
public Source load(Long projectId, String snapshotId, String username,
                   WorkspaceContext context, Locale locale) {
    DecisionRationaleReport decision = decisions.generate(projectId, snapshotId, username, context, locale);
    var projection = workbench.load(projectId, snapshotId, username, context);
    ArchitectureReportDocument architecture = frozenDocument(decision, projection, locale);
    return new Source(decision.withArchitecture(architecture), architecture);
}
```

Use exact existing signatures after reading their declarations, not an invented duplicate workbench API. `frozenDocument` validates shared source coordinates, builds deterministic element/relation inventories and a canonical graph digest, then derives narrative only from saved evidence. Render overview plus bounded detail panels with verified complete coverage. Build all tree rows and bookmark links, detecting cycles and contradictory parentage. Define and test explicit ceilings before allocating Java2D canvases. Implement semantic Word styles without overwriting template definitions, TOC plus linked static navigation, repeating table headers, non-splitting bounded rows, numbered captions and strict picture title/description metadata. The legacy renderer adapts its DTO structurally; it does not parse Markdown. Add snapshot routes, language labels and response identity headers.

- [ ] **Step 4: Prove GREEN and connect acceptance.** Run all affected architecture, portfolio and template tests with targeted reactor selection and record exact command/counts. Change civilian `report.docx` download to the frozen snapshot route. Extend `CivilianExportQa` to assert both snapshot IDs/graph hashes match, every source node/relation ID is represented, architecture figures and complete chapter navigation are present, and no pseudo-tables remain. Run the focused app acceptance command using existing cached model settings and runtime wrapper when feasible; document any specific environment gate for controller follow-up.

- [ ] **Step 5: Render and review the real artifacts.** Use the repository's existing LibreOffice/PDF acceptance tools, inspect all pages at reading scale (contact sheets plus suspect-page detail), and correct orphan captions/headings, blank body pages, clipped panels and illegible text. This final artifact QA may be performed by the controller after the task returns; do not claim it completed without actual output. Preserve output source hash/version evidence in the repository's QA convention.

- [ ] **Step 6: Self-review and commit.** Inspect `git diff --check`, affected tests, schema/API compatibility and custom templates. Commit the complete working slice with a concrete message. Write a report with files, design decisions, test commands/results, commits, remaining visual gate and genuine environmental limitations. Do not mark absent proprietary-product execution as passed.

## Preflight resolution

The first task owns one independently reviewable deliverable: both complete snapshot Word reports. Shared report/template source and output interfaces are kept in this single gate to prevent shipping a renderer that the actual snapshot routes bypass. End-to-end visual QA and final whole-branch verification will run again only when the combined implementation is stable. User authorization to finish and merge the previously described work is already present; execution continues without another plan approval pause.
