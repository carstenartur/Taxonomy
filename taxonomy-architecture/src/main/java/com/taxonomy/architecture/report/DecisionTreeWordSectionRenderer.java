package com.taxonomy.architecture.report;

import com.taxonomy.architecture.decision.DecisionReportLabels;

import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.util.List;

public final class DecisionTreeWordSectionRenderer {
    public void write(XWPFDocument document, DecisionTreeOverview tree, String language) {
        var labels = new DecisionReportLabels(language);
        var writer = new WordDocumentWriter(document, labels);
        writer.heading(labels.treeOverview(), 1, "decision_tree");
        writer.caption(labels.treeOverview(), false);
        var table =
                writer.table(
                        List.of(
                                labels.node(),
                                labels.score(),
                                labels.disposition(),
                                labels.chapter()),
                        List.of());
        for (var entry : tree.rows()) {
            var row = table.createRow();
            row.setCantSplitRow(true);
            var p = row.getCell(0).getParagraphs().getFirst();
            p.setIndentationLeft(Math.min(entry.depth(), 12) * 100);
            var run = p.createRun();
            run.setFontSize(9);
            run.setText(
                    entry.code()
                            + " · "
                            + entry.title()
                            + (entry.depth() > 12
                                    ? " (" + labels.depth() + " " + entry.depth() + ")"
                                    : ""));
            writer.cell(
                    row.getCell(1),
                    entry.score() == null ? labels.notEvaluated() : entry.score() + "%");
            writer.cell(
                    row.getCell(2),
                    switch (entry.disposition()) {
                        case CONTINUED -> labels.continued();
                        case LEAF_CANDIDATE -> labels.leafCandidate();
                        case REJECTED -> labels.rejected();
                        case NOT_EVALUATED -> labels.notEvaluated();
                    });
            if (entry.bookmark() != null)
                writer.link(
                        row.getCell(3).getParagraphs().getFirst(),
                        entry.bookmark(),
                        labels.chapter() + " " + entry.chapterNumber());
        }
        writer.columnWidths(table, 48, 16, 24, 12);
        for (String code : tree.warnings()) writer.paragraph(labels.orphanWarning() + ": " + code);
    }
}
