package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReport.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionRationaleDocxLayoutTest {
    @Test
    void alternativesKeepDistinctScoresAndSourcesButShareFullWidthRationales() throws Exception {
        String reason = "This alternative is outside the civilian subscription boundary. "
                + "Published warnings must retain their source, observation time and geographic area.";
        var chapter = new DecisionChapter(1, "BP", "Business processes", "", 100, 0,
                true, "Selected data acquisition", "All alternatives were assessed.", List.of(
                new ChildDecision("BP-A", "Acquire published observations", "", 100, 100.0, 1,
                        true, Disposition.LEAF_CANDIDATE, "Retains official source and time.", ReasonSource.AI_SCORING, true),
                new ChildDecision("BP-B", "Excluded alternative B", "", 0, 0.0, 2,
                        false, Disposition.REJECTED, reason, ReasonSource.AI_SCORING, true),
                new ChildDecision("BP-C", "Excluded alternative C", "", 0, 0.0, 2,
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
            assertThat(document.getTables().stream().flatMap(t -> t.getRows().stream()).flatMap(r -> r.getTableCells().stream())
                    .flatMap(c -> c.getParagraphs().stream()).flatMap(p -> p.getCTP().getHyperlinkList().stream()).map(h -> h.getAnchor()))
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
