package com.taxonomy.reporting.render.decision;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionRationaleDocxLayoutTest {
    @Test
    void wordSourceNoticeUsesOnlyExplicitlyRecordedReasonLength() throws Exception {
        var base = DecisionRationaleTemplateRendererTest.report();
        var recorded = base.withScope(base.scope().withAnalysisReasonLength(com.taxonomy.dto.AnalysisReasonLength.DETAILED));
        var renderer = new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer());
        for (var report : List.of(base, recorded)) {
            try (var document = new XWPFDocument(new java.io.ByteArrayInputStream(renderer.render(report)))) {
                String text = new XWPFWordExtractor(document).getText();
                String notice = new DecisionReportLabels(report.languageTag()).german()
                        ? "Vorgabe für Begründungen: Ausführlich" : "Requested explanation length: Detailed";
                if (report == recorded) assertThat(text).contains(notice);
                else assertThat(text).doesNotContain(notice, "Requested explanation length:", "Vorgabe für Begründungen:");
            }
        }
    }

    @Test
    void overviewIncludesEveryRootAndLinksToCompleteReasonsEvenWithoutAppendix() throws Exception {
        var base = DecisionCompactReportTest.report(com.taxonomy.reporting.api.decision.DecisionReportOptions.TreeLayout.AUTO, 4);
        var rows = new java.util.ArrayList<>(base.scope().decisionTree().rows());
        rows.add(new com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow(0, "BP", "Business processes", 0d, Disposition.REJECTED, null, null));
        rows.add(new com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow(0, "BR", "Business roles", null, Disposition.NOT_EVALUATED, null, null));
        rows.add(new com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow(0, "IP", "Information products", 80d, Disposition.CONTINUED, null, null));
        rows.add(new com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow(1, "IP-A", "First tied result", 40d, Disposition.LEAF_CANDIDATE, null, null));
        rows.add(new com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow(1, "IP-B", "Second tied result", 40d, Disposition.LEAF_CANDIDATE, null, null));
        String reason = "Saved original evidence. ".repeat(40) + "UNABRIDGED-END";
        var options = new com.taxonomy.reporting.api.decision.DecisionReportOptions(
                com.taxonomy.reporting.api.decision.DecisionReportOptions.Profile.STANDARD, null, com.taxonomy.reporting.api.decision.DecisionReportOptions.Contents.NONE, null, null);
        var scope = new com.taxonomy.reporting.api.decision.DecisionReportScope(null, null, java.util.List.of(),
                new java.util.LinkedHashSet<>(java.util.List.of("CP", "BP", "BR", "IP")), java.util.Set.of(),
                new com.taxonomy.reporting.api.document.DecisionTreeOverview(rows, java.util.List.of()), options, false, false)
                .withRecordedReasons(java.util.Map.of("BP", "No matching business process.", "IP-A", reason, "IP-B", "Equal recorded relevance."));
        byte[] bytes = new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(base.withScope(scope));
        var directory = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/decision-reading-qa"));
        java.nio.file.Files.write(directory.resolve("decision-multi-root.docx"), bytes);
        try (var doc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            String text = new XWPFWordExtractor(doc).getText();
            assertThat(text).contains("Results by taxonomy", "Business processes", "Business roles", "Information products",
                    "0%", "Not evaluated", "2 tied", "UNABRIDGED-END", "not a statistical probability", "Back to root tree");
            var overview = doc.getTables().stream().filter(t -> t.getRow(0).getCell(0).getText().equals("Taxonomy")).findFirst().orElseThrow();
            assertThat(overview.getRows()).hasSize(5);
            assertThat(overview.getText()).contains("Business roles", "IP → IP-A", "2 tied");
            var treeHeading = doc.getParagraphs().stream().filter(p -> p.getText().startsWith(new DecisionReportLabels("en").treeOverview())
                    && "Heading1".equals(p.getStyle())).findFirst().orElseThrow();
            assertThat(doc.getBodyElements().indexOf(overview)).isLessThan(doc.getBodyElements().indexOf(treeHeading));
            var xml = doc.getDocument();
            String namespace = "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'; ";
            var bookmarks = java.util.Arrays.stream(xml.selectPath(namespace + ".//w:bookmarkStart"))
                    .map(n -> n.getDomNode().getAttributes().getNamedItemNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "name").getNodeValue()).toList();
            assertThat(bookmarks).doesNotHaveDuplicates();
            var links = java.util.Arrays.stream(xml.selectPath(namespace + ".//w:hyperlink"))
                    .map(n -> n.getDomNode().getAttributes().getNamedItemNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "anchor").getNodeValue()).toList();
            assertThat(links).isNotEmpty().allSatisfy(anchor -> assertThat(bookmarks).contains(anchor));
        }
    }

    @Test
    void alternativesKeepDistinctScoresAndSourcesButShareFullWidthRationales() throws Exception {
        String reason = "This alternative is outside the scenario subscription boundary. "
                + "Published warnings must retain their source, observation time and geographic area.";
        var chapter = new DecisionChapter(1, "BP", "Business processes", "", 100d, 0,
                true, "Selected data acquisition", "All alternatives were assessed.", List.of(
                new ChildDecision("BP-A", "Acquire published observations", "", 100d, 100.0, 1,
                        true, Disposition.LEAF_CANDIDATE, "Retains official source and time.", ReasonSource.AI_SCORING, true),
                new ChildDecision("BP-B", "Excluded alternative B", "", 0d, 0.0, 2,
                        false, Disposition.REJECTED, reason, ReasonSource.AI_SCORING, true),
                new ChildDecision("BP-C", "Excluded alternative C", "", 0d, 0.0, 2,
                        false, Disposition.REJECTED, reason, ReasonSource.AI_SCORING, true),
                new ChildDecision("BP-D", "Unevaluated alternative D", "", null, null, null,
                        false, Disposition.NOT_EVALUATED, reason, ReasonSource.MISSING, true)), List.of());
        var base = DecisionRationaleTemplateRendererTest.report();
        var report = new DecisionRationaleReport(base.title(), "en", base.requirement(), base.status(),
                base.metadata(), base.executiveSummary(), List.of(chapter), List.of(), List.of(),
                List.of(), List.of(), null);
        try (var document = new XWPFDocument()) {
            new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer())
                    .writeReportBody(document, report, new DecisionReportLabels("en"));
            String text = new XWPFWordExtractor(document).getText();
            assertThat(document.getDocument().xmlText()).contains("Heading1", "TOC ", "decision_chapter_1_BP", "SEQ Figure");
            assertThat(java.util.stream.Stream.concat(document.getParagraphs().stream(),
                    document.getTables().stream().flatMap(t -> t.getRows().stream()).flatMap(r -> r.getTableCells().stream())
                            .flatMap(c -> c.getParagraphs().stream())).flatMap(p -> p.getCTP().getHyperlinkList().stream()).map(h -> h.getAnchor()))
                    .contains("decision_chapter_1_BP");
            assertThat(document.getSettings().getCTSettings().xmlText()).contains("updateFields");
            assertThat(text).contains("BP-A", "BP-B", "BP-C", "BP-D", "100 %", "0 %", "Not evaluated");
            // Equal prose with different provenance remains separate evidence.
            assertThat(text.split(java.util.regex.Pattern.quote(reason), -1)).hasSize(3);
            assertThat(document.getTables().stream().flatMap(t -> t.getRows().stream())
                    .flatMap(r -> r.getTableCells().stream()).map(c -> c.getText()))
                    .noneMatch(t -> t.contains(reason));
            assertThat(document.getParagraphs()).noneMatch(p -> p.getText().isBlank() && p.isPageBreak());
            var rationaleRows = document.getTables().stream().flatMap(t -> t.getRows().stream())
                    .filter(r -> r.getTableCells().size() == 1)
                    .filter(r -> r.getCell(0).getText().startsWith("Decision result")
                            || r.getCell(0).getText().startsWith("Comparative rationale")).toList();
            assertThat(rationaleRows).hasSize(2).allSatisfy(row -> {
                assertThat(row.isCantSplitRow()).as("rationale box stays on one page").isTrue();
                assertThat(row.getCell(0).getParagraphs().getFirst().isKeepNext())
                        .as("rationale label stays with its explanation").isTrue();
            });
        }
    }
}
