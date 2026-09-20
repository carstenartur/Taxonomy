package com.taxonomy.architecture.report;

import com.taxonomy.architecture.decision.DecisionReportLabels;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.*;
import java.math.BigInteger;
import java.util.*;

/** OOXML semantics shared by standalone and template-backed documents. */
public final class WordDocumentWriter {
    private final XWPFDocument document;
    private final DecisionReportLabels labels;

    public WordDocumentWriter(XWPFDocument document, DecisionReportLabels labels) {
        this.document = document;
        this.labels = labels;
        ensureStyles(document);
    }

    public static void ensureStyles(XWPFDocument document) {
        XWPFStyles styles =
                document.getStyles() == null ? document.createStyles() : document.getStyles();
        for (String id : List.of("Title", "Heading1", "Heading2", "Heading3", "Caption")) {
            if (styles.styleExist(id)) continue;
            CTStyle style = CTStyle.Factory.newInstance();
            style.setStyleId(id);
            style.setType(STStyleType.PARAGRAPH);
            style.addNewName().setVal(id);
            var p = style.addNewPPr();
            p.addNewKeepNext();
            if (id.startsWith("Heading"))
                p.addNewOutlineLvl()
                        .setVal(BigInteger.valueOf(Integer.parseInt(id.substring(7)) - 1));
            var run = style.addNewRPr();
            run.addNewRFonts().setAscii("Aptos");
            run.getRFontsArray(0).setHAnsi("Aptos");
            run.addNewSz()
                    .setVal(
                            BigInteger.valueOf(
                                    id.equals("Title")
                                            ? 48
                                            : id.equals("Heading1")
                                                    ? 34
                                                    : id.equals("Caption") ? 18 : 26));
            if (!id.equals("Caption")) run.addNewB();
            styles.addStyle(new XWPFStyle(style));
        }
    }

    public XWPFParagraph heading(String text, int level, String bookmark) {
        XWPFParagraph p = document.createParagraph();
        p.setStyle(level == 0 ? "Title" : "Heading" + level);
        p.setKeepNext(true);
        p.setSpacingBefore(180);
        p.setSpacingAfter(100);
        p.createRun().setText(value(text));
        if (bookmark != null) bookmark(p, bookmark);
        return p;
    }

    public XWPFParagraph paragraph(String text) {
        var p = document.createParagraph();
        p.setSpacingAfter(100);
        var r = p.createRun();
        r.setFontSize(10);
        r.setText(value(text));
        return p;
    }

    public void contents(Map<String, String> sections) {
        heading(labels.contents(), 1, null);
        var p = document.createParagraph();
        p.getCTP().addNewPPr();
        var field = p.getCTP().addNewFldSimple();
        field.setInstr("TOC \\o \"1-3\" \\h \\z \\u");
        p.setKeepNext(true);
        field.addNewR().addNewT().setStringValue("");
        var settings = document.getSettings().getCTSettings();
        (settings.isSetUpdateFields() ? settings.getUpdateFields() : settings.addNewUpdateFields())
                .setVal(true);
        var table = table(List.of(labels.titleLabel(), labels.openChapter()), List.of());
        table.getRow(0).getTableCells().forEach(c ->
                c.getParagraphs().forEach(paragraph -> paragraph.setKeepNext(true)));
        sections.forEach(
                (anchor, title) -> {
                    var row = table.createRow();
                    cell(row.getCell(0), title);
                    link(row.getCell(1).getParagraphs().getFirst(), anchor, title);
                    row.setCantSplitRow(true);
                });
    }

    public XWPFTable table(List<String> headers, List<List<String>> values) {
        XWPFTable table = document.createTable(1, headers.size());
        table.setWidth("100%");
        var header = table.getRow(0);
        header.setRepeatHeader(true);
        header.setCantSplitRow(true);
        for (int i = 0; i < headers.size(); i++) {
            cell(header.getCell(i), headers.get(i));
            header.getCell(i).setColor("E9F0F7");
            header.getCell(i).getParagraphs().getFirst().getRuns().forEach(r -> r.setBold(true));
        }
        for (var valuesRow : values) {
            var row = table.createRow();
            row.setCantSplitRow(
                    valuesRow.stream().mapToInt(v -> v == null ? 0 : v.length()).sum() < 1800);
            for (int i = 0; i < headers.size(); i++) cell(row.getCell(i), valuesRow.get(i));
        }
        return table;
    }

    public void columnWidths(XWPFTable table, int... percentages) {
        if (Arrays.stream(percentages).sum() != 100)
            throw new IllegalArgumentException("Table widths must total 100 percent");
        var grid = table.getCTTbl().getTblGrid();
        if (grid == null) grid = table.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int percent : percentages)
            grid.addNewGridCol()
                    .setW(BigInteger.valueOf(Math.round(printableWidth() * 20 * percent / 100)));
        for (var row : table.getRows())
            for (int i = 0; i < percentages.length; i++)
                row.getCell(i).setWidth(percentages[i] + "%");
    }

    public void caption(String text, boolean figure) {
        var p = document.createParagraph();
        p.setStyle("Caption");
        p.setKeepNext(!figure);
        p.setSpacingAfter(100);
        p.createRun().setText((figure ? labels.figure() : labels.table()) + " ");
        var field = p.getCTP().addNewFldSimple();
        String kind = figure ? "Figure" : "Table";
        field.setInstr("SEQ " + kind + " \\* ARABIC");
        int count = 0;
        for (var existing :
                document.getDocument()
                        .selectPath(
                                "declare namespace"
                                    + " w='http://schemas.openxmlformats.org/wordprocessingml/2006/main';"
                                    + " .//w:fldSimple")) {
            var instruction =
                    existing.getDomNode()
                            .getAttributes()
                            .getNamedItemNS(
                                    "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                                    "instr");
            if (instruction != null && instruction.getNodeValue().startsWith("SEQ " + kind + " "))
                count++;
        }
        field.addNewR().addNewT().setStringValue(String.valueOf(count));
        p.createRun().setText(" · " + text);
    }

    public void picture(
            byte[] png,
            String id,
            String title,
            String description,
            int width,
            int height,
            boolean readingScale)
            throws Exception {
        if (png == null
                || width <= 0
                || height <= 0
                || description == null
                || description.isBlank())
            throw new IllegalArgumentException("Invalid accessible report figure");
        double targetWidth = Math.min(printableWidth(), 470);
        if (targetWidth <= 0)
            throw new WordReportLayoutException(
                    "Word template has no printable width for readable evidence at 8pt");
        double targetHeight = targetWidth * height / width;
        double availableHeight =
                Math.min(470, printableHeight() - captionAllowance(title, printableWidth()));
        if (availableHeight <= 0)
            throw new WordReportLayoutException(
                    "Word template has no printable height for a figure and its caption at 8pt"
                            + " reading scale");
        if (targetHeight > availableHeight) {
            targetWidth *= availableHeight / targetHeight;
            targetHeight = availableHeight;
        }
        // Architecture renderer uses 20px node text at 1.5 raster scale.
        if (readingScale && targetWidth * 30 / width < 8)
            throw new WordReportLayoutException(
                    "Word detail figure cannot meet minimum 8pt reading scale within this template"
                            + " printable width and height, including its caption");
        var p = document.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setKeepNext(true);
        p.setSpacingAfter(40);
        p.setSpacingBetween(1.0, LineSpacingRule.AUTO);
        var run = p.createRun();
        run.addPicture(
                new ByteArrayInputStream(png),
                Document.PICTURE_TYPE_PNG,
                id + ".png",
                Units.toEMU(targetWidth),
                Units.toEMU(targetHeight));
        setPictureMetadata(run, id, title, description);
    }

    private double printableWidth() {
        return sectionValue("width", 612) - sectionValue("left", 81) - sectionValue("right", 81);
    }

    private double printableHeight() {
        return sectionValue("height", 792) - sectionValue("top", 72) - sectionValue("bottom", 72);
    }

    // Generated body is appended in the final section. Omitted properties inherit from the
    // preceding section, never from an unrelated first section or a hardcoded portrait frame.
    private double sectionValue(String field, double fallback) {
        var sections = new ArrayList<CTSectPr>();
        for (var paragraph : document.getParagraphs()) {
            var properties = paragraph.getCTP().getPPr();
            if (properties != null && properties.isSetSectPr())
                sections.add(properties.getSectPr());
        }
        if (document.getDocument().getBody().isSetSectPr())
            sections.add(document.getDocument().getBody().getSectPr());
        for (int i = sections.size() - 1; i >= 0; i--) {
            var section = sections.get(i);
            Object value = null;
            if (section.isSetPgSz())
                value =
                        switch (field) {
                            case "width" -> section.getPgSz().getW();
                            case "height" -> section.getPgSz().getH();
                            default -> null;
                        };
            if (section.isSetPgMar())
                value =
                        switch (field) {
                            case "left" -> section.getPgMar().getLeft();
                            case "right" -> section.getPgMar().getRight();
                            case "top" -> section.getPgMar().getTop();
                            case "bottom" -> section.getPgMar().getBottom();
                            default -> value;
                        };
            if (value != null) return number(value) / 20.0;
        }
        return fallback;
    }

    private double captionAllowance(String title, double width) {
        var defaults = document.getStyles().getDefaultRunStyle();
        Double defaultSize = defaults == null ? null : defaults.getFontSizeAsDouble();
        double font = defaultSize == null ? 11 : defaultSize, before = 0, line = 0, lineFactor = 1;
        boolean fontFound = false, beforeFound = false, lineFound = false;
        var visited = new HashSet<String>();
        var style = document.getStyles().getStyle("Caption");
        while (style != null && visited.add(style.getStyleId())) {
            var definition = style.getCTStyle();
            if (!fontFound && definition.isSetRPr() && definition.getRPr().sizeOfSzArray() > 0) {
                font = number(definition.getRPr().getSzArray(0).getVal()) / 2;
                fontFound = true;
            }
            if (definition.isSetPPr() && definition.getPPr().isSetSpacing()) {
                var spacing = definition.getPPr().getSpacing();
                if (!beforeFound && spacing.isSetBefore()) {
                    before = number(spacing.getBefore()) / 20;
                    beforeFound = true;
                }
                if (!lineFound && spacing.isSetLine()) {
                    if (spacing.getLineRule() == null
                            || spacing.getLineRule() == STLineSpacingRule.AUTO) {
                        lineFactor = number(spacing.getLine()) / 240;
                    } else {
                        line = number(spacing.getLine()) / 20;
                    }
                    lineFound = true;
                }
            }
            style =
                    definition.isSetBasedOn()
                            ? document.getStyles().getStyle(definition.getBasedOn().getVal())
                            : null;
        }
        // Include figure/caption paragraph spacing and a conservative text-line allowance.
        double lines =
                Math.max(1, Math.ceil((title.length() + 20) * font * .65 / Math.max(1, width)));
        return Math.max(32, before + lines * Math.max(font * 1.5 * lineFactor, line) + 12);
    }

    private double number(Object value) {
        return Double.parseDouble(value.toString());
    }

    public static void setPictureMetadata(
            XWPFRun run, String name, String title, String description) {
        if (run.getEmbeddedPictures().isEmpty())
            throw new IllegalStateException("Picture metadata cannot be applied: no drawing");
        var picture = run.getEmbeddedPictures().getLast().getCTPicture().getNvPicPr().getCNvPr();
        picture.setName(name);
        picture.setTitle(title);
        picture.setDescr(description);
        var drawing = run.getCTR().getDrawingArray(run.getCTR().sizeOfDrawingArray() - 1);
        if (drawing.sizeOfInlineArray() == 0)
            throw new IllegalStateException(
                    "Picture metadata cannot be applied: no inline drawing");
        var properties = drawing.getInlineArray(drawing.sizeOfInlineArray() - 1).getDocPr();
        properties.setName(name);
        properties.setTitle(title);
        properties.setDescr(description);
        if (!description.equals(properties.getDescr()))
            throw new IllegalStateException("Picture description was not retained");
    }

    public void bookmark(XWPFParagraph paragraph, String name) {
        BigInteger id = BigInteger.ZERO;
        var stories = new ArrayList<org.apache.xmlbeans.XmlObject>();
        stories.add(document.getDocument());
        document.getHeaderList().forEach(header -> stories.add(header._getHdrFtr()));
        document.getFooterList().forEach(footer -> stories.add(footer._getHdrFtr()));
        document.getFootnotes().forEach(note -> stories.add(note.getCTFtnEdn()));
        document.getEndnotes().forEach(note -> stories.add(note.getCTFtnEdn()));
        var policy = document.getHeaderFooterPolicy();
        if (policy != null) {
            for (var story :
                    java.util.Arrays.asList(
                            policy.getDefaultHeader(),
                            policy.getFirstPageHeader(),
                            policy.getEvenPageHeader(),
                            policy.getDefaultFooter(),
                            policy.getFirstPageFooter(),
                            policy.getEvenPageFooter()))
                if (story != null) stories.add(story._getHdrFtr());
        }
        for (var story : stories)
            for (var node :
                    story.selectPath(
                            "declare namespace"
                                + " w='http://schemas.openxmlformats.org/wordprocessingml/2006/main';"
                                + " .//w:bookmarkStart")) {
                var attributes = node.getDomNode().getAttributes();
                var existing =
                        attributes.getNamedItemNS(
                                "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                                "id");
                if (existing != null)
                    id = id.max(new BigInteger(existing.getNodeValue()).add(BigInteger.ONE));
                var existingName =
                        attributes.getNamedItemNS(
                                "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                                "name");
                if (existingName != null && name.equals(existingName.getNodeValue()))
                    throw new IllegalArgumentException("Duplicate Word bookmark: " + name);
            }
        var start = paragraph.getCTP().addNewBookmarkStart();
        start.setId(id);
        start.setName(name);
        paragraph.getCTP().addNewBookmarkEnd().setId(id);
    }

    public void link(XWPFParagraph paragraph, String anchor, String text) {
        var hyperlink = paragraph.getCTP().addNewHyperlink();
        hyperlink.setAnchor(anchor);
        var run = hyperlink.addNewR();
        run.addNewRPr().addNewColor().setVal("007A78");
        run.getRPr().addNewSz().setVal(BigInteger.valueOf(18));
        run.addNewT().setStringValue(value(text));
    }

    public void properties(ArchitectureReportDocument report) {
        property("taxonomy.snapshot.id", report.evidence().snapshotId());
        property("taxonomy.graph.sha256", report.evidence().graphSha256());
        property(
                "taxonomy.requirement.version.id",
                String.valueOf(report.evidence().requirementVersionId()));
        property("taxonomy.source.commit", report.evidence().commit());
    }

    private void property(String key, String value) {
        var properties = document.getProperties().getCustomProperties();
        if (properties.contains(key)) properties.getProperty(key).setLpwstr(value(value));
        else properties.addProperty(key, value(value));
    }

    public void cell(XWPFTableCell cell, String value) {
        var p = cell.getParagraphs().getFirst();
        p.setSpacingAfter(60);
        var run = p.createRun();
        run.setFontSize(9);
        run.setText(value(value));
    }

    private String value(String value) {
        return value == null ? labels.unknown() : value;
    }
}
