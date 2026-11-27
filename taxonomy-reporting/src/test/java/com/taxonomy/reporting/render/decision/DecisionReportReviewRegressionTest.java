package com.taxonomy.reporting.render.decision;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.decision.DecisionReportScope;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.dto.DetectedPattern;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.reporting.render.document.ArchitectureReportDocxRenderer;
import com.taxonomy.reporting.render.document.DecisionTreeFigureRenderer;
import com.taxonomy.reporting.render.document.WordDocumentWriter;
import com.taxonomy.reporting.render.document.WordReportLayoutException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static com.taxonomy.reporting.api.decision.DecisionReportOptions.*;
import static com.taxonomy.reporting.api.decision.DecisionRationaleReport.Disposition.*;

class DecisionReportReviewRegressionTest {
    @ParameterizedTest
    @CsvSource({
            "en, Expected decision-tree nodes scored, Completeness, Title",
            "de, Erwartete Entscheidungsknoten mit Bewertung, Vollständigkeit, Titel"
    })
    void distinguishesDecisionTreeScoringFromArchitecturePatternCompleteness(
            String language, String treeLabel, String patternLabel, String titleLabel) throws Exception {
        var base = DecisionRationaleTemplateRendererTest.report();
        var decision = new DecisionRationaleReport(base.title(), language, base.requirement(), base.status(),
                base.metadata(), base.executiveSummary(), base.chapters(), base.leadingLeaves(), base.warnings(),
                base.productCoverageGaps(), base.discrepancies(), base.viewContext(), base.scoreDetails(),
                base.architecture(), base.scope());
        var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(decision);
        assertThat(html).contains("<dt>" + treeLabel + "</dt><dd>100 %</dd>")
                .doesNotContain("<dt>" + patternLabel + "</dt>");
        try (var doc = open(decision)) {
            assertThat(doc.getTables().stream().flatMap(table -> table.getRows().stream())
                    .map(DecisionReportReviewRegressionTest::cellText).toList())
                    .contains(List.of(treeLabel, "100 %")).doesNotContain(List.of(patternLabel, "100 %"));
        }

        var patterns = new PatternDetectionView();
        patterns.setMatchedPatterns(List.of(new DetectedPattern(
                "Full pattern", List.of("Step"), List.of("Step"), List.of(), 150.0)));
        patterns.setIncompletePatterns(List.of(new DetectedPattern(
                "Missing pattern", List.of("Step"), List.of(), List.of("Step"), -10.0)));
        var architecture = new ArchitectureReport();
        architecture.setPatternDetection(patterns);
        byte[] word = new ArchitectureReportDocxRenderer().render(architecture, language, Map.of());
        try (var doc = new XWPFDocument(new ByteArrayInputStream(word))) {
            var table = doc.getTables().stream()
                    .filter(candidate -> candidate.getRows().stream().map(DecisionReportReviewRegressionTest::cellText)
                            .anyMatch(cells -> cells.contains("Full pattern")))
                    .findFirst().orElseThrow();
            assertThat(cellText(table.getRow(0))).startsWith(titleLabel, patternLabel);
            assertThat(table.getRows().stream().skip(1).map(DecisionReportReviewRegressionTest::cellText).toList())
                    .containsExactly(List.of("Full pattern", "100%", ""), List.of("Missing pattern", "0%", "Step"));
        }
    }

    @Test void printedPageNumbersUseThePageMarginCounterScope() {
        var report = DecisionRationaleTemplateRendererTest.report();
        var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(report);
        assertThat(html).contains("@bottom-right{content:", "counter(page)", "counter(pages)")
                .doesNotContain(".page-counter:after");
        assertThat(DecisionRationaleHtmlRenderer.cssString("</style>\n")).isEqualTo("\\3C /style>\\A ");
    }

    @Test void appendixLeafRankingFitsTheReportThroughItsOwnFocusableScrollRegion() {
        var report = DecisionRationaleTemplateRendererTest.report();
        var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(report);
        assertThat(html).contains("<h3 id=\"decision_leaf_ranking\">")
                .contains("</h3><div class=\"decision-table-scroll\" role=\"region\" "
                        + "aria-labelledby=\"decision_leaf_ranking\" tabindex=\"0\"><table>");
    }

    @Test void htmlTreeNavigatesToOnlyTheReasonsThatWereActuallyRecorded() {
        var renderer = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer());
        for (var layout : List.of(TreeLayout.TABLE, TreeLayout.AUTO)) {
            var base = DecisionCompactReportTest.report(layout, 4);
            var report = base.withScope(base.scope().withRecordedReasons(Map.of("CP-1", "Recorded zero-score reason.")));
            var html = renderer.render(report);
            var original = DecisionNodeEvidence.reasonBookmark("CP-1");
            var missing = DecisionNodeEvidence.reasonBookmark("CP-2");
            assertThat(html).contains("href=\"#" + original + "\"", "id=\"" + original + "\"",
                    "Recorded zero-score reason.", "Not evaluated", "0%")
                    .doesNotContain("href=\"#" + missing + "\"");
        }
    }

    @Test void htmlSnapshotArchitectureKeepsGapsAndProvenanceFromTheSameFrozenGraph() {
        var base = DecisionRationaleTemplateRendererTest.report();
        var graph = new com.taxonomy.diagram.DiagramModel("Saved graph",
                List.of(new com.taxonomy.diagram.DiagramNode("BP", "Business processes", "Process", .75, true, 0)),
                List.of(), new com.taxonomy.diagram.DiagramLayout("LR", true));
        var source = new com.taxonomy.diagram.DiagramModel(graph.title(), List.of(graph.nodes().getFirst(),
                new com.taxonomy.diagram.DiagramNode("CP", "Capabilities", "Capability", .5, false, 0)),
                graph.edges(), graph.layout());
        var sourceDigest = com.taxonomy.reporting.api.document.ArchitectureReportDocument.graphSha256(source);
        var digest = com.taxonomy.reporting.api.document.ArchitectureReportDocument.graphSha256(graph);
        assertThat(digest).isNotEqualTo(sourceDigest);
        var evidence = new com.taxonomy.reporting.api.document.ArchitectureReportDocument.SnapshotEvidence(
                1L, 2L, 3L, 4, "saved-graph-snapshot", "saved-graph-repository", "saved-graph-workspace",
                "saved-graph-branch", "saved-graph-commit", "MOCK", "fixture", "saved-graph-taxonomy", digest);
        var architecture = com.taxonomy.reporting.render.document.ArchitectureReportDocuments.from(
                "Saved graph", "en", base.requirement(), "Report selection: BP · Source graph SHA-256: " + sourceDigest, "Saved recommendation",
                List.of("SAVED-GAP-TERMINAL"), graph, new com.taxonomy.export.LayeredDiagramLayoutService().layout(graph),
                null, evidence);
        var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(base.withArchitecture(architecture));
        assertThat(html).contains("SAVED-GAP-TERMINAL", "Saved recommendation", "saved-graph-snapshot",
                "saved-graph-branch", "saved-graph-commit", "Source graph SHA-256: " + sourceDigest,
                "<dt>Architecture graph SHA-256</dt><dd>" + digest + "</dd>");
    }

    @Test void htmlPrintsRepeatedRelationshipProvenanceOnlyOnceAndPreservesItsLabels() {
        var base = DecisionRationaleTemplateRendererTest.report();
        var report = new DecisionRationaleReport(base.title(), "de", base.requirement(), base.status(),
                base.metadata(), base.executiveSummary(), base.chapters(), base.leadingLeaves(), base.warnings(),
                base.productCoverageGaps(), base.discrepancies(), base.viewContext(), base.scoreDetails(),
                base.architecture(), base.scope());
        String rationale = "Gespeicherte Begründung mit <wortgetreuem> Nachweis.";
        var metadata = new com.taxonomy.diagram.DiagramRelationMetadata(42L,
                com.taxonomy.dto.RelationOrigin.LLM_SUPPORTED, null, rationale, rationale, rationale, null, List.of());
        var graph = new com.taxonomy.diagram.DiagramModel("Saved graph", List.of(
                new com.taxonomy.diagram.DiagramNode("CI-1151", "Ausbildungsdienst", "Service", .6, true, 0),
                new com.taxonomy.diagram.DiagramNode("BP-2125", "Ausbildung", "Process", .5, false, 1)),
                List.of(new com.taxonomy.diagram.DiagramEdge("e1", "CI-1151", "BP-2125", "SUPPORTS", .6, "impact", metadata)),
                new com.taxonomy.diagram.DiagramLayout("LR", true));
        var digest = com.taxonomy.reporting.api.document.ArchitectureReportDocument.graphSha256(graph);
        var evidence = new com.taxonomy.reporting.api.document.ArchitectureReportDocument.SnapshotEvidence(
                1L, 2L, 3L, 4, "snapshot", "repo", "workspace", "branch", "commit", "provider", "model", "taxonomy", digest);
        var architecture = com.taxonomy.reporting.render.document.ArchitectureReportDocuments.from(
                graph.title(), "de", base.requirement(), "Scope", "", List.of(), graph,
                new com.taxonomy.export.LayeredDiagramLayoutService().layout(graph), null, evidence)
                .withNodeReasons(Map.of("CI-1151", "Authored node observation.\n\n"
                        + "Directly assessed relevance; this alone does not establish a relationship or required implementation.\n"
                        + "Requirement-scoped contribution proposals: Preserved authored contribution."))
                .withRelationReasons(Map.of("e1", Map.of("presenceReason", rationale, "decisionComment", "Ergänzender Prüfkommentar.")));
        var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(report.withArchitecture(architecture));

        assertThat(html).contains("Herkunft: Durch KI gestützt",
                "Aufgenommen weil, Herleitungsbegründung, Begründung der Aufnahme, Gespeicherte Auswahlbegründung: ",
                "Gespeicherter Prüfkommentar: Ergänzender Prüfkommentar.", "Authored node observation.",
                "Diese Bewertung betrifft ausschließlich die Relevanz.",
                "Beitragsvorschläge zur Anforderung: Preserved authored contribution.")
                .doesNotContain("Herkunft: LLM_SUPPORTED", "Directly assessed relevance;");
        String statement = "Gespeicherte Begründung mit &lt;wortgetreuem&gt; Nachweis.";
        assertThat(html.indexOf(statement)).isPositive().isEqualTo(html.lastIndexOf(statement));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TreeLayout.class, names = {"AUTO", "TABLE"})
    void htmlResponsibilityOptionControlsPreviouslyAssignedGraphAndTreeNodes(TreeLayout layout) {
        var base = DecisionCompactReportTest.report(layout, 2);
        var graphOwner = new com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment("graph-unit", "Archive owner", "graph-source");
        var treeOwner = new com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment("tree-unit", "Tree owner", "tree-source");
        var node = new com.taxonomy.diagram.DiagramNode("other", "Saved element", "Capability", .8, true,
                0, 1, false, null, false, graphOwner);
        var graph = new com.taxonomy.diagram.DiagramModel("Saved graph", List.of(node), List.of(),
                new com.taxonomy.diagram.DiagramLayout("LR", false));
        var tree = base.scope().decisionTree().withResponsibilities(Map.of("CP", treeOwner));
        var digest = com.taxonomy.reporting.api.document.ArchitectureReportDocument.graphSha256(graph);
        var evidence = new com.taxonomy.reporting.api.document.ArchitectureReportDocument.SnapshotEvidence(
                1L, 2L, 3L, 4, "snapshot", "repo", "workspace", "branch", "commit", "provider", "model", "taxonomy", digest);
        var architecture = com.taxonomy.reporting.render.document.ArchitectureReportDocuments.from("Graph", "en",
                base.requirement(), "Scope", "", List.of(), graph, new com.taxonomy.export.LayeredDiagramLayoutService().layout(graph), tree, evidence);
        for (boolean show : List.of(true, false)) {
            var options = new DecisionReportOptions(Profile.FULL, null, Contents.NONE, layout, null, show);
            var scope = new DecisionReportScope(null, null, base.scope().availableRoots(), base.scope().reportRoots(),
                    base.scope().selectedNodeCodes(), tree, options, true, true);
            var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer())
                    .render(base.withScope(scope).withArchitecture(architecture));
            assertThat(html).contains("Saved element");
            String[] owners = {"Organizational responsibilities", "Archive owner", "graph-unit", "graph-source", "Tree owner"};
            if (show) assertThat(html).contains(owners);
            else assertThat(html).doesNotContain(owners);
            assertThat(graph.nodes().getFirst().responsibility()).isEqualTo(graphOwner);
            assertThat(tree.responsibilitiesByCode()).containsEntry("CP", treeOwner);
        }
    }

    @Test void compactSnapshotDownloadShowsTheModelAssignmentAndActualCatalogueGeneration() throws Exception {
        var base = DecisionCompactReportTest.report(TreeLayout.AUTO, 4);
        var binding = new com.taxonomy.dto.AnalysisModelBinding(com.taxonomy.dto.AnalysisModelBinding.State.CATALOGUE_UNRESOLVED,
                "repo-a", "workspace-a", "draft", null, 9L, "WORKSPACE_REVISION", "c".repeat(64), null,
                "definition-3", Set.of("CP"), com.taxonomy.dto.AnalysisScope.full(), "brief-3", Map.of(),
                List.of(), Set.of(), null, "used-generation-3");
        var report = base.withScope(base.scope().withModelBinding(binding));
        var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(report);
        var json = new tools.jackson.databind.ObjectMapper().valueToTree(report);
        assertThat(json.path("scope").path("modelBinding").path("executionCatalogueRevision").asString())
                .isEqualTo("used-generation-3");
        try (var doc = open(report); var extractor = new XWPFWordExtractor(doc)) {
            for (var text : List.of(html, extractor.getText())) {
                assertThat(text).contains("Catalogue assigned to the project: Unknown", "Catalogue used for analysis: used-generation-3",
                        "Architecture brief version: brief-3", "c".repeat(64), "Requirement version");
            }
        }
    }

    @Test void compactRetainsFullReasonsAfterTheLandscapeTree() throws Exception {
        var base = DecisionCompactReportTest.report(TreeLayout.AUTO, 4);
        String reason = "Saved essential root evidence. ".repeat(30) + "FULL-REASON-END";
        var report = base.withScope(base.scope().withRecordedReasons(Map.of("CP", reason)));
        try (var doc = open(report)) {
            String text = new XWPFWordExtractor(doc).getText();
            assertThat(text).contains(reason, "snapshot", "analysis-sha", "Recorded node reasons");
            assertThat(doc.getDocument().xmlText()).contains("landscape");
            assertThat(doc.getDocument().getBody().getSectPr().getPgSz().getOrient().toString()).isEqualTo("portrait");
            assertResolved(doc);
        }
    }

    @Test void standardWithoutAppendixAndCompactWithExplicitEvidenceKeepFullReasons() throws Exception {
        var base = DecisionCompactReportTest.report(TreeLayout.AUTO, 4);
        String reason = "Original unabridged evidence. ".repeat(30) + "FULL-REASON-END";
        for (var profile : List.of(Profile.STANDARD, Profile.COMPACT)) {
            var sections = profile == Profile.COMPACT ? Set.of(Section.SUMMARY, Section.TREE, Section.EVIDENCE)
                    : Set.of(Section.SUMMARY, Section.TREE);
            var s = base.scope();
            var options = new DecisionReportOptions(profile, null, Contents.NONE, TreeLayout.AUTO, sections);
            var report = base.withScope(new DecisionReportScope(null, null, s.availableRoots(), s.reportRoots(),
                    s.selectedNodeCodes(), s.decisionTree(), options, true, true).withRecordedReasons(Map.of("CP", reason)));
            try (var doc = open(report)) {
                assertThat(new XWPFWordExtractor(doc).getText()).contains("Recorded node reasons", "FULL-REASON-END");
                assertResolved(doc);
            }
        }
    }

    @Test void overviewUsesOneComparisonRowPerRootAndNeverOffersMissingOriginal() throws Exception {
        var base = DecisionCompactReportTest.report(TreeLayout.AUTO, 4);
        try (var doc = open(base)) {
            var overview = doc.getTables().stream().filter(t -> "Taxonomy".equals(t.getRow(0).getCell(0).getText())).findFirst();
            assertThat(overview).isPresent();
            assertThat(overview.orElseThrow().getRows()).hasSize(2);
            assertThat(overview.orElseThrow().getText()).contains("CP", "Capabilities", "100%");
            var links = overview.orElseThrow().getRows().stream().flatMap(r -> r.getTableCells().stream())
                    .flatMap(c -> c.getParagraphs().stream()).flatMap(p -> p.getCTP().getHyperlinkList().stream()).toList();
            assertThat(links).isNotEmpty().allSatisfy(link -> assertThat(link.getRList()).allSatisfy(run ->
                    assertThat(run.getRPr().getSzList()).isNotEmpty().allSatisfy(size ->
                            assertThat(size.getVal()).isEqualTo(java.math.BigInteger.valueOf(20)))));
            assertThat(new XWPFWordExtractor(doc).getText()).doesNotContain("Full original reason", "No reason available: No reason available");
        }
    }

    @Test void consecutiveNativeActionsHaveVisibleSeparatorsAndPreservedWhitespace() throws Exception {
        try (var doc = new XWPFDocument()) {
            var writer = new WordDocumentWriter(doc, new DecisionReportLabels("en"));
            var p = writer.paragraph("");
            writer.link(p, "a", "CP "); writer.link(p, "b", "CP-1"); writer.link(p, "c", "Decision 1");
            assertThat(p.getCTP().xmlText()).contains("xml:space=\"preserve\"", " · ");
            assertThat(p.getCTP().getRList().stream().flatMap(r -> r.getTList().stream()).map(t -> t.getStringValue()))
                    .contains(" · ");
        }
    }

    @Test void graphicalAndTableKeysSeparateNodeAndChapterActions() throws Exception {
        var base = DecisionRationaleTemplateRendererTest.report();
        var child = new DecisionRationaleReport.ChildDecision("CP-1", "Saved child", "", 100d, 100d, 1,
                true, LEAF_CANDIDATE, "Child original reason.", DecisionRationaleReport.ReasonSource.AI_SCORING, true);
        var chapter = new DecisionRationaleReport.DecisionChapter(1, "CP", "Capabilities", "", 100d, 0, true,
                "Saved decision", "Saved comparison", List.of(child), List.of());
        var report = new DecisionRationaleReport(base.title(), "en", base.requirement(), base.status(), base.metadata(),
                base.executiveSummary(), List.of(chapter), List.of(), List.of(), List.of(), List.of(), null);
        for (var layout : List.of(TreeLayout.AUTO, TreeLayout.TABLE)) {
            var s = report.scope();
            var options = new DecisionReportOptions(Profile.STANDARD, null, Contents.NONE, layout, null);
            var configured = report.withScope(new DecisionReportScope(null, null, List.of(), Set.of("CP"), s.selectedNodeCodes(),
                    s.decisionTree(), options, true, true).withRecordedReasons(Map.of("CP", "Root original reason.")));
            try (var doc = open(configured)) {
                assertResolved(doc);
                var paragraphs = java.util.stream.Stream.concat(doc.getParagraphs().stream(), doc.getTables().stream()
                        .flatMap(t -> t.getRows().stream()).flatMap(r -> r.getTableCells().stream()).flatMap(c -> c.getParagraphs().stream()));
                var keys = paragraphs.filter(p -> p.getCTP().getHyperlinkList().size() > 1).toList();
                assertThat(keys).isNotEmpty();
                assertThat(keys).allSatisfy(p -> assertThat(p.getCTP().getRList().stream().flatMap(r -> r.getTList().stream())
                        .map(t -> t.getStringValue())).contains(" · "));
                var path = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/decision-reading-qa"))
                        .resolve("decision-linked-key-" + layout.name().toLowerCase(java.util.Locale.ROOT) + ".docx");
                try (var out = java.nio.file.Files.newOutputStream(path)) { doc.write(out); }
            }
        }
    }

    @Test void relevanceBarsUseOneAbsoluteScaleAcrossDepthsAndPageWidths() {
        var tree = new DecisionTreeOverview(List.of(row(0, "R", 30d), row(1, "A", 30d), row(5, "B", 30d)), List.of());
        var renderer = new DecisionTreeFigureRenderer();
        var widths = new ArrayList<Integer>();
        for (var layout : List.of(TreeLayout.A4_LANDSCAPE, TreeLayout.A3_LANDSCAPE))
            for (var panel : renderer.render(tree, "en", layout)) {
                var matcher = java.util.regex.Pattern.compile("class=\"relevance-bar\"[^>]*width=\"(\\d+)\"").matcher(panel.svg());
                while (matcher.find()) widths.add(Integer.parseInt(matcher.group(1)));
            }
        assertThat(widths).hasSize(6).allMatch(w -> w.equals(widths.getFirst()));
    }

    @Test void figureBudgetAppliesPerRootWithoutDroppingLargeMultiRootEvidence() {
        var rows = new ArrayList<DecisionTreeOverview.DecisionTreeRow>();
        for (String root : List.of("A", "B")) {
            rows.add(row(0, root, 100d));
            for (int i = 1; i <= 1000; i++) rows.add(row(1, root + i, 1d));
        }
        var renderer = new DecisionTreeFigureRenderer();
        var panels = renderer.render(new DecisionTreeOverview(rows, List.of()), "en", TreeLayout.AUTO);
        assertThat(panels.stream().flatMap(p -> p.nodeCodes().stream()).toList())
                .containsExactlyElementsOf(rows.stream().map(DecisionTreeOverview.DecisionTreeRow::code).toList());
        var oversized = new ArrayList<DecisionTreeOverview.DecisionTreeRow>();
        oversized.add(row(0, "X", 100d));
        for (int i = 1; i <= 2000; i++) oversized.add(row(1, "X" + i, 1d));
        assertThatThrownBy(() -> renderer.render(new DecisionTreeOverview(oversized, List.of()), "en", TreeLayout.AUTO))
                .isInstanceOf(WordReportLayoutException.class).hasMessageContaining("X");
    }

    @Test void emptyGlobalPathDoesNotCreateTableAndMethodologyAppearsOnce() throws Exception {
        var report = DecisionRationaleTemplateRendererTest.report();
        try (var doc = open(report)) {
            String text = new XWPFWordExtractor(doc).getText();
            assertThat(text.split(java.util.regex.Pattern.quote(report.executiveSummary().methodologyNote()), -1)).hasSize(2);
            assertThat(doc.getTables()).noneMatch(t -> t.getRow(0).getCell(0).getText().equals(new DecisionReportLabels("en").step()));
        }
    }

    private static DecisionTreeOverview.DecisionTreeRow row(int depth, String code, Double score) {
        return new DecisionTreeOverview.DecisionTreeRow(depth, code, code, score, CONTINUED, null, null);
    }
    private static XWPFDocument open(DecisionRationaleReport report) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(report)));
    }
    private static List<String> cellText(XWPFTableRow row) {
        return row.getTableCells().stream().map(XWPFTableCell::getText).toList();
    }
    private static void assertResolved(XWPFDocument doc) {
        String ns = "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'; ";
        String uri = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        var names = Arrays.stream(doc.getDocument().selectPath(ns + ".//w:bookmarkStart"))
                .map(n -> n.getDomNode().getAttributes().getNamedItemNS(uri, "name").getNodeValue()).toList();
        assertThat(names).doesNotHaveDuplicates();
        for (var link : doc.getDocument().selectPath(ns + ".//w:hyperlink"))
            assertThat(names).contains(link.getDomNode().getAttributes().getNamedItemNS(uri, "anchor").getNodeValue());
    }
}
