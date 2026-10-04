package com.taxonomy.architecture.report;

import com.taxonomy.architecture.decision.DecisionRationaleReport;
import com.taxonomy.architecture.decision.DecisionReportOptions;
import com.taxonomy.architecture.decision.DecisionReportScope;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DecisionTreeLandscapeContentsTest {
    @Test
    void additionalRootsOnlyAddSubheadingsToTheTreeSection() throws Exception {
        assertPanelHeadings(List.of(row(0, "CP"), row(1, "CP-1"), row(0, "BP"), row(1, "BP-1")));
    }

    @Test
    void continuationPagesDoNotExpandTheShortContents() throws Exception {
        var rows = new ArrayList<DecisionTreeOverview.DecisionTreeRow>();
        rows.add(row(0, "CP"));
        for (int i = 0; i < 200; i++) rows.add(row(1, "CP-" + i));
        assertPanelHeadings(rows);
    }

    @Test
    void emptyTreeKeepsItsMainSectionHeading() throws Exception {
        try (var document = new XWPFDocument()) {
            new DecisionTreeLandscapeSection().write(document, report(List.of()), false);
            assertEquals(1L, document.getParagraphs().stream().filter(p -> "Heading1".equals(p.getStyle())).count());
        }
    }

    private static void assertPanelHeadings(List<DecisionTreeOverview.DecisionTreeRow> rows) throws Exception {
        try (var document = new XWPFDocument()) {
            new DecisionTreeLandscapeSection().write(document, report(rows), false);
            var headings = document.getParagraphs().stream()
                    .filter(p -> p.getStyle() != null && p.getStyle().startsWith("Heading")).toList();
            assertTrue(headings.size() > 1);
            assertEquals("Heading1", headings.getFirst().getStyle());
            assertEquals(1L, headings.stream().filter(p -> "Heading1".equals(p.getStyle())).count());
            for (var paragraph : headings.subList(1, headings.size())) assertEquals("Heading2", paragraph.getStyle());
            assertEquals(headings.size(), document.getAllPictures().size());
        }
    }

    private static DecisionTreeOverview.DecisionTreeRow row(int depth, String code) {
        return new DecisionTreeOverview.DecisionTreeRow(depth, code, code, 60,
                DecisionRationaleReport.Disposition.LEAF_CANDIDATE, null, null);
    }

    private static DecisionRationaleReport report(List<DecisionTreeOverview.DecisionTreeRow> rows) {
        var tree = new DecisionTreeOverview(rows, List.of());
        var options = new DecisionReportOptions(DecisionReportOptions.Profile.COMPACT, null,
                DecisionReportOptions.Contents.SHORT, DecisionReportOptions.TreeLayout.AUTO, null);
        var scope = new DecisionReportScope(null, null, List.of(), Set.of(), Set.of(), tree, options, false, false);
        return new DecisionRationaleReport("Report", "en", "Requirement",
                DecisionRationaleReport.ReportStatus.DRAFT_INCOMPLETE, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), null, java.util.Map.of(), null, scope);
    }
}
