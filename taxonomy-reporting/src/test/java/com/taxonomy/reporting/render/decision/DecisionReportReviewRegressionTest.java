package com.taxonomy.reporting.render.decision;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.decision.DecisionReportScope;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import com.taxonomy.reporting.render.document.DecisionTreeFigureRenderer;
import com.taxonomy.reporting.render.document.WordDocumentWriter;
import com.taxonomy.reporting.render.document.WordReportLayoutException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static com.taxonomy.reporting.api.decision.DecisionReportOptions.*;
import static com.taxonomy.reporting.api.decision.DecisionRationaleReport.Disposition.*;

class DecisionReportReviewRegressionTest {
    @Test void compactEndsWithTreeAndDoesNotClaimOrPrintFullReasons() throws Exception {
        var base = DecisionCompactReportTest.report(TreeLayout.AUTO, 4);
        String reason = "Saved essential root evidence. ".repeat(30) + "FULL-REASON-END";
        var report = base.withScope(base.scope().withRecordedReasons(Map.of("CP", reason)));
        try (var doc = open(report)) {
            String text = new XWPFWordExtractor(doc).getText();
            assertThat(text).contains("Saved essential root evidence.", "snapshot", "analysis-sha")
                    .doesNotContain("Recorded node reasons", "Full original reason", "FULL-REASON-END");
            assertThat(doc.getDocument().getBody().getSectPr().getPgSz().getOrient().toString()).isEqualTo("landscape");
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
