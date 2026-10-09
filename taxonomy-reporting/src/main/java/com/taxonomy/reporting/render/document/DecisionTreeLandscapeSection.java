package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;

import com.taxonomy.reporting.render.decision.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

/** Section properties describe the content BEFORE the break; the final body section follows it. */
public final class DecisionTreeLandscapeSection {
    public void write(XWPFDocument doc, DecisionRationaleReport report, boolean followingContent) throws Exception {
        var labels = new DecisionReportLabels(report.languageTag());
        var writer = new WordDocumentWriter(doc, labels);
        var body = doc.getDocument().getBody();
        var original = body.isSetSectPr() ? (CTSectPr) body.getSectPr().copy() : portrait();
        var panels = new DecisionTreeFigureRenderer().render(report.scope().decisionTree(), report.languageTag(), report.scope().options().treeLayout());
        if (panels.isEmpty()) { writer.heading(labels.treeOverview(), 1, "decision_tree"); writer.paragraph(labels.notEvaluated()); return; }
        for (int i = 0; i < panels.size(); i++) {
            var panel = panels.get(i);
            if (!doc.getBodyElements().isEmpty()) finishSection(doc, body.isSetSectPr() ? body.getSectPr() : original);
            var section = (CTSectPr) original.copy();
            var size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
            size.setW(BigInteger.valueOf(panel.a3() ? 23811 : 16838));
            size.setH(BigInteger.valueOf(panel.a3() ? 16838 : 11906)); size.setOrient(STPageOrientation.LANDSCAPE);
            var margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
            margins.setLeft(BigInteger.valueOf(900)); margins.setRight(BigInteger.valueOf(900));
            margins.setTop(BigInteger.valueOf(900)); margins.setBottom(BigInteger.valueOf(900));
            if (section.isSetType()) section.getType().setVal(STSectionMark.NEXT_PAGE);
            body.setSectPr(section);
            // SHORT contents includes outline level 1; additional roots and continuation
            // panels remain available at level 2 without expanding that short overview.
            writer.heading(labels.treeOverview() + " · " + panel.rootCode() + " · " + (i + 1) + "/" + panels.size(), i == 0 ? 1 : 2, i == 0 ? "decision_tree" : null);
            var p = doc.createParagraph(); p.setSpacingBefore(0); p.setSpacingAfter(0);
            var run = p.createRun();
            run.addPicture(new ByteArrayInputStream(panel.png()), Document.PICTURE_TYPE_PNG, "decision-tree-"+i+".png",
                    Units.toEMU(panel.width()), Units.toEMU(panel.height()));
            var drawing = run.getCTR().getDrawingArray(0).getInlineArray(0).getDocPr();
            drawing.setDescr(panel.description()); drawing.setTitle(labels.treeOverview());
        }
        if (followingContent) { finishSection(doc, body.getSectPr()); body.setSectPr(original); }
    }
    private void finishSection(XWPFDocument doc, CTSectPr section) {
        var p = doc.getParagraphs().isEmpty() ? doc.createParagraph() : doc.getParagraphs().getLast();
        // A table may be the last body element; its trailing paragraph owns the break.
        if (doc.getBodyElements().getLast() != p) p = doc.createParagraph();
        var pr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        var copy = (CTSectPr) section.copy();
        (copy.isSetType() ? copy.getType() : copy.addNewType()).setVal(STSectionMark.NEXT_PAGE);
        pr.setSectPr(copy);
    }
    private CTSectPr portrait() {
        var section = CTSectPr.Factory.newInstance(); var size = section.addNewPgSz();
        size.setW(BigInteger.valueOf(11906)); size.setH(BigInteger.valueOf(16838)); size.setOrient(STPageOrientation.PORTRAIT);
        return section;
    }
}
