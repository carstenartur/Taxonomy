package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionChapterDiagramRenderer.DiagramPanel;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ChildDecision;
import com.taxonomy.architecture.decision.DecisionRationaleReport.DecisionChapter;
import com.taxonomy.architecture.decision.DecisionRationaleReport.LeafCandidate;
import com.taxonomy.architecture.decision.DecisionRationaleReport.PathStep;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPBdr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STLineSpacingRule;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Professional DOCX renderer for the hierarchical decision rationale report.
 *
 * <p>The generated document uses A4 pages, a title page, an executive summary,
 * deterministic decision diagrams, repeating table headings where possible, and a footer
 * containing generation/account/version evidence plus PAGE/NUMPAGES fields.</p>
 */
@Component
@SuppressWarnings("serial")
public class DecisionRationaleDocxRenderer implements ReportRendererExtension {

    private static final ReportFormatDescriptor DESCRIPTOR = new ReportFormatDescriptor(
            "docx",
            "DOCX",
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            true);

    private static final String FONT = "Aptos";
    private static final String MONO_FONT = "Aptos Mono";
    private static final String NAVY = "0B1F33";
    private static final String TEAL = "007A78";
    private static final String LIGHT_TEAL = "E1F4F3";
    private static final String PALE_BLUE = "E9F0F7";
    private static final String LIGHT_GRAY = "F4F6F8";
    private static final String LINE = "CDD5DC";
    private static final String MUTED = "64707C";
    private static final String WARNING = "FFF4D6";
    private static final String WARNING_BORDER = "B98200";
    private static final int PAGE_WIDTH_TWIPS = 11_906;
    private static final int PAGE_HEIGHT_TWIPS = 16_838;

    private final DecisionChapterDiagramRenderer diagramRenderer;

    public DecisionRationaleDocxRenderer(DecisionChapterDiagramRenderer diagramRenderer) {
        this.diagramRenderer = diagramRenderer;
    }

    @Override
    public String reportTypeId() {
        return DecisionRationaleReportPlugin.REPORT_TYPE_ID;
    }

    @Override
    public Class<?> reportModelType() {
        return DecisionRationaleReport.class;
    }

    @Override
    public ReportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ReportRenderResult render(ReportRenderContext context) {
        return new ReportRenderResult(render(
                context.payloadAs(DecisionRationaleReport.class)));
    }

    public byte[] render(DecisionRationaleReport report) {
        DecisionReportLabels labels = new DecisionReportLabels(report.languageTag());
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configurePage(document);
            addHeaderAndFooter(document, report, labels);
            renderTitlePage(document, report, labels);
            writeReportBody(document, report, labels);
            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not generate decision rationale DOCX", exception);
        }
    }

    /**
     * Writes the report content shared by standalone and template-backed DOCX output.
     *
     * <p>The already opened document owns its cover, headers, footers and template
     * styling. This typed boundary owns the single implementation of metadata,
     * executive-summary, chapter/table/diagram and appendix semantics.</p>
     */
    void writeReportBody(
            XWPFDocument document,
            DecisionRationaleReport report,
            DecisionReportLabels labels) throws Exception {
        configureCoreProperties(document, report);
        renderExecutiveSummary(document, report, labels);
        renderChapters(document, report, labels);
        renderAppendix(document, report, labels);
    }

    private void configurePage(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        CTPageSz pageSize = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(PAGE_WIDTH_TWIPS));
        pageSize.setH(BigInteger.valueOf(PAGE_HEIGHT_TWIPS));
        pageSize.setOrient(STPageOrientation.PORTRAIT);
        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margins.setTop(BigInteger.valueOf(1_020));
        margins.setBottom(BigInteger.valueOf(1_160));
        margins.setLeft(BigInteger.valueOf(1_050));
        margins.setRight(BigInteger.valueOf(1_050));
        margins.setHeader(BigInteger.valueOf(480));
        margins.setFooter(BigInteger.valueOf(500));
        margins.setGutter(BigInteger.ZERO);
    }

    private void configureCoreProperties(XWPFDocument document, DecisionRationaleReport report) {
        var properties = document.getProperties().getCoreProperties();
        properties.setTitle(report.title());
        properties.setSubjectProperty(report.requirement());
        properties.setCreator(report.metadata().generatedBy());
        properties.setDescription("Traceable hierarchical decision rationale generated by Taxonomy");
        properties.setKeywords("Taxonomy, decision rationale, requirements analysis, AI, traceability");
    }

    private void addHeaderAndFooter(
            XWPFDocument document,
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document);
        XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph headerParagraph = header.createParagraph();
        headerParagraph.setAlignment(ParagraphAlignment.RIGHT);
        headerParagraph.setSpacingAfter(0);
        XWPFRun headerRun = headerParagraph.createRun();
        headerRun.setFontFamily(FONT);
        headerRun.setFontSize(8);
        headerRun.setColor(MUTED);
        headerRun.setText(report.title() + " · " + report.metadata().taxonomyDataVersion());
        setBottomBorder(headerParagraph, LINE, 4);

        XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFTable table = footer.createTable(1, 2);
        table.setWidth("100%");
        table.setTableAlignment(TableRowAlign.CENTER);
        removeTableBorders(table);
        XWPFTableRow row = table.getRow(0);
        XWPFTableCell left = row.getCell(0);
        XWPFTableCell right = row.getCell(1);
        left.setWidth("82%");
        right.setWidth("18%");
        clearCell(left);
        clearCell(right);

        XWPFParagraph leftParagraph = left.addParagraph();
        leftParagraph.setSpacingBefore(0);
        leftParagraph.setSpacingAfter(0);
        XWPFRun leftRun = leftParagraph.createRun();
        leftRun.setFontFamily(FONT);
        leftRun.setFontSize(7);
        leftRun.setColor(MUTED);
        leftRun.setText(footerText(report, labels));

        XWPFParagraph rightParagraph = right.addParagraph();
        rightParagraph.setAlignment(ParagraphAlignment.RIGHT);
        rightParagraph.setSpacingBefore(0);
        rightParagraph.setSpacingAfter(0);
        XWPFRun pageLabel = rightParagraph.createRun();
        pageLabel.setFontFamily(FONT);
        pageLabel.setFontSize(8);
        pageLabel.setColor(MUTED);
        pageLabel.setText(labels.page() + " ");
        addSimpleField(rightParagraph, "PAGE", "1", 8, MUTED);
        XWPFRun of = rightParagraph.createRun();
        of.setFontFamily(FONT);
        of.setFontSize(8);
        of.setColor(MUTED);
        of.setText(" " + labels.of() + " ");
        addSimpleField(rightParagraph, "NUMPAGES", "1", 8, MUTED);
        setTopBorder(leftParagraph, LINE, 4);
        setTopBorder(rightParagraph, LINE, 4);
    }

    private void renderTitlePage(
            XWPFDocument document,
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        addAccentBar(document);
        spacer(document, 20);
        XWPFParagraph eyebrow = document.createParagraph();
        eyebrow.setSpacingAfter(120);
        XWPFRun eyebrowRun = eyebrow.createRun();
        eyebrowRun.setText("TAXONOMY · DECISION EVIDENCE");
        eyebrowRun.setFontFamily(FONT);
        eyebrowRun.setFontSize(10);
        eyebrowRun.setBold(true);
        eyebrowRun.setColor(TEAL);
        eyebrowRun.setCharacterSpacing(25);

        XWPFParagraph title = document.createParagraph();
        title.setSpacingBefore(0);
        title.setSpacingAfter(120);
        XWPFRun titleRun = title.createRun();
        titleRun.setText(report.title());
        titleRun.setFontFamily(FONT);
        titleRun.setFontSize(31);
        titleRun.setBold(true);
        titleRun.setColor(NAVY);

        XWPFParagraph subtitle = document.createParagraph();
        subtitle.setSpacingAfter(240);
        XWPFRun subtitleRun = subtitle.createRun();
        subtitleRun.setText(labels.subtitle());
        subtitleRun.setFontFamily(FONT);
        subtitleRun.setFontSize(14);
        subtitleRun.setColor(MUTED);

        XWPFTable statusTable = document.createTable(1, 1);
        statusTable.setWidth("42%");
        removeTableBorders(statusTable);
        XWPFTableCell statusCell = statusTable.getRow(0).getCell(0);
        shade(statusCell, report.status() == DecisionRationaleReport.ReportStatus.DRAFT_INCOMPLETE
                || report.status() == DecisionRationaleReport.ReportStatus.NO_RESULT
                ? WARNING : LIGHT_TEAL);
        setCellMargins(statusCell, 100, 130, 100, 130);
        setCellText(statusCell, statusLabel(report, labels), 10, true,
                report.status() == DecisionRationaleReport.ReportStatus.DRAFT_INCOMPLETE
                        || report.status() == DecisionRationaleReport.ReportStatus.NO_RESULT
                        ? "745400" : TEAL, ParagraphAlignment.CENTER);

        spacer(document, 18);
        addLabel(document, labels.requirement());
        XWPFTable requirement = document.createTable(1, 1);
        requirement.setWidth("100%");
        XWPFTableCell requirementCell = requirement.getRow(0).getCell(0);
        shade(requirementCell, LIGHT_GRAY);
        setCellMargins(requirementCell, 180, 220, 180, 220);
        setLeftCellBorder(requirementCell, TEAL, 22);
        setCellText(requirementCell, report.requirement(), 13, false, NAVY,
                ParagraphAlignment.LEFT);

        spacer(document, 18);
        addLabel(document, labels.taxonomyBasis());
        XWPFTable metadata = document.createTable(1, 2);
        metadata.setWidth("100%");
        metadata.setTableAlignment(TableRowAlign.CENTER);
        setMetadataRow(metadata.getRow(0), labels.dataVersion(),
                report.metadata().taxonomyDataVersion());
        addMetadataRow(metadata, labels.sourceFile(), report.metadata().taxonomyCatalogueFile());
        addMetadataRow(metadata, labels.sourceDigest(), report.metadata().taxonomyCatalogueResourceSha256());
        addMetadataRow(metadata, labels.dataDigest(), report.metadata().taxonomyDataFingerprintSha256());
        addMetadataRow(metadata, labels.hierarchyEvidence(),
                report.metadata().hierarchyFromImmutableSnapshot()
                        ? labels.frozenHierarchy() : labels.liveHierarchy());
        addMetadataRow(metadata, labels.nodes(), report.metadata().taxonomyNodeCount()
                + " / " + report.metadata().taxonomyRootCount());

        spacer(document, 12);
        addLabel(document, labels.provenance());
        XWPFTable provenance = document.createTable(1, 2);
        provenance.setWidth("100%");
        provenance.setTableAlignment(TableRowAlign.CENTER);
        setMetadataRow(provenance.getRow(0), labels.generatedAt(),
                formatInstant(report.metadata().generatedAt(), report.metadata().reportTimeZone()));
        addMetadataRow(provenance, labels.generatedBy(), report.metadata().generatedBy());
        addMetadataRow(provenance, labels.applicationVersion(),
                report.metadata().taxonomyApplicationVersion());
        addMetadataRow(provenance, labels.buildCommit(), report.metadata().taxonomyBuildCommit());
        addMetadataRow(provenance, labels.repository(), report.metadata().repositoryId());
        addMetadataRow(provenance, labels.workspace(), report.metadata().workspaceId());
        addMetadataRow(provenance, labels.branch(), report.metadata().branch());
        addMetadataRow(provenance, labels.basedOnCommit(), report.metadata().basedOnCommit());
        addMetadataRow(provenance, labels.analysisProvider(), report.metadata().analysisProvider());
        addMetadataRow(provenance, labels.analysisModel(), report.metadata().analysisModel());
        addMetadataRow(provenance, labels.analysisSnapshot(), report.metadata().analysisSnapshotId());
        addMetadataRow(provenance, labels.requirementVersion(), requirementVersion(report));
        addMetadataRow(provenance, labels.analysisCreatedAt(),
                formatInstant(report.metadata().analysisCreatedAt(), report.metadata().reportTimeZone()));
        addMetadataRow(provenance, labels.analysisCreatedBy(), report.metadata().analysisCreatedBy());
        addMetadataRow(provenance, labels.analysisDigest(),
                report.metadata().analysisSnapshotFingerprintSha256());
        addMetadataRow(provenance, labels.analysisStatus(), report.metadata().analysisStatus());

        XWPFParagraph note = document.createParagraph();
        note.setSpacingBefore(260);
        note.setSpacingAfter(0);
        XWPFRun noteRun = note.createRun();
        noteRun.setFontFamily(FONT);
        noteRun.setFontSize(8);
        noteRun.setColor(MUTED);
        noteRun.setItalic(true);
        noteRun.setText(report.executiveSummary().methodologyNote());
        addPageBreak(document);
    }

    private void renderExecutiveSummary(
            XWPFDocument document,
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        addSectionHeading(document, "01", labels.executiveSummary());
        addLeadParagraph(document, report.executiveSummary().conciseConclusion());

        LeafCandidate leading = report.executiveSummary().leadingLeaf();
        if (leading != null) {
            XWPFTable banner = document.createTable(1, 2);
            banner.setWidth("100%");
            removeTableBorders(banner);
            XWPFTableCell textCell = banner.getRow(0).getCell(0);
            XWPFTableCell scoreCell = banner.getRow(0).getCell(1);
            shade(textCell, NAVY);
            shade(scoreCell, TEAL);
            setCellMargins(textCell, 180, 220, 180, 220);
            setCellMargins(scoreCell, 180, 150, 180, 150);
            clearCell(textCell);
            XWPFParagraph p = textCell.addParagraph();
            p.setSpacingAfter(40);
            XWPFRun label = p.createRun();
            label.setText(labels.leadingLeaf().toUpperCase(Locale.ROOT));
            label.setFontFamily(FONT);
            label.setFontSize(8);
            label.setBold(true);
            label.setColor("8DE0DD");
            XWPFRun name = p.createRun();
            name.addBreak();
            name.setText(leading.code() + " · " + leading.title());
            name.setFontFamily(FONT);
            name.setFontSize(14);
            name.setBold(true);
            name.setColor("FFFFFF");
            XWPFRun root = p.createRun();
            root.addBreak();
            root.setText(leading.taxonomyRoot());
            root.setFontFamily(FONT);
            root.setFontSize(9);
            root.setColor("DCE7EF");
            setCellText(scoreCell, leading.score() + " %", 24, true, "FFFFFF",
                    ParagraphAlignment.CENTER);
            scoreCell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        }

        addHeading(document, labels.highestPath(), 2, false);
        XWPFTable pathTable = document.createTable(1, 6);
        pathTable.setWidth("100%");
        pathTable.setTableAlignment(TableRowAlign.CENTER);
        String[] pathHeaders = {
                labels.step(), labels.node(), labels.score(), labels.localShare(),
                labels.rationale(), labels.reasonSource()
        };
        setHeaderRow(pathTable.getRow(0), pathHeaders);
        for (PathStep step : report.executiveSummary().path()) {
            XWPFTableRow row = pathTable.createRow();
            setCellText(row.getCell(0), String.valueOf(step.position()), 8, false, NAVY,
                    ParagraphAlignment.CENTER);
            setCodeAndName(row.getCell(1), step.code(), step.title());
            setCellText(row.getCell(2), score(step.absoluteScore()), 8, true, NAVY,
                    ParagraphAlignment.CENTER);
            setCellText(row.getCell(3), percent(step.localSharePercent()), 8, false, NAVY,
                    ParagraphAlignment.CENTER);
            setCellText(row.getCell(4), step.reason(), 8, false, NAVY,
                    ParagraphAlignment.LEFT);
            setCellText(row.getCell(5), reasonSource(step.reasonSource(), labels), 7, false,
                    MUTED, ParagraphAlignment.LEFT);
            styleDataRow(row, step.position() % 2 == 0 ? LIGHT_GRAY : "FFFFFF");
        }

        addHeading(document, labels.methodology(), 2, false);
        addBodyParagraph(document, report.executiveSummary().methodologyNote(), false);
        if (!report.warnings().isEmpty()) {
            addWarningBox(document, labels.warnings(), report.warnings());
        }
        addPageBreak(document);
    }

    private void renderChapters(
            XWPFDocument document,
            DecisionRationaleReport report,
            DecisionReportLabels labels) throws Exception {
        addSectionHeading(document, "02", labels.decisionChapters());
        addBodyParagraph(document, labels.german()
                ? "Jedes folgende Kapitel entspricht genau einem Vaterknoten, dessen direkte Kindknoten mindestens einen von Null verschiedenen Bewertungswert enthalten. Sämtliche direkten Kinder bleiben als geprüfte oder noch ungeprüfte Alternativen sichtbar."
                : "Each following chapter corresponds to one parent whose direct children contain at least one non-zero score. All direct children remain visible as evaluated or unevaluated alternatives.", false);
        addPageBreak(document);

        for (int chapterIndex = 0; chapterIndex < report.chapters().size(); chapterIndex++) {
            DecisionChapter chapter = report.chapters().get(chapterIndex);
            addChapterHeading(document, chapter, labels);
            if (chapter.parentDescription() != null && !chapter.parentDescription().isBlank()) {
                addBodyParagraph(document, chapter.parentDescription(), true);
            }

            List<DiagramPanel> panels = diagramRenderer.render(chapter, report.languageTag());
            for (DiagramPanel panel : panels) {
                XWPFParagraph imageParagraph = document.createParagraph();
                imageParagraph.setAlignment(ParagraphAlignment.CENTER);
                imageParagraph.setSpacingBefore(100);
                imageParagraph.setSpacingAfter(60);
                XWPFRun imageRun = imageParagraph.createRun();
                imageRun.addPicture(
                        new ByteArrayInputStream(panel.png()),
                        Document.PICTURE_TYPE_PNG,
                        "decision-" + chapter.parentCode() + "-" + panel.panelNumber() + ".png",
                        Units.toEMU(6.45 * 72),
                        Units.toEMU(3.87 * 72));
                setLastPictureAltText(imageRun, panel.altText());
                XWPFParagraph caption = document.createParagraph();
                caption.setAlignment(ParagraphAlignment.RIGHT);
                caption.setSpacingAfter(120);
                XWPFRun captionRun = caption.createRun();
                captionRun.setFontFamily(FONT);
                captionRun.setFontSize(7);
                captionRun.setItalic(true);
                captionRun.setColor(MUTED);
                captionRun.setText(labels.parentNode() + " " + chapter.parentCode()
                        + (panel.panelCount() > 1
                        ? " · " + panel.panelNumber() + "/" + panel.panelCount() : ""));
            }

            addRationaleBox(document, labels.decisionResult(), chapter.decisionSummary(), PALE_BLUE);
            addRationaleBox(document, labels.comparison(), chapter.comparativeRationale(), LIGHT_TEAL);
            addHeading(document, labels.alternatives(), 2, false);
            addChildTable(document, chapter, labels);
            if (!chapter.complete()) {
                addWarningBox(document,
                        labels.german() ? "Unvollständige Entscheidung" : "Incomplete decision",
                        List.of((labels.german()
                                ? "Nicht bewertete direkte Kinder: "
                                : "Unevaluated direct children: ")
                                + String.join(", ", chapter.missingChildCodes())));
            }
            if (chapterIndex < report.chapters().size() - 1) {
                addPageBreak(document);
            }
        }
        addPageBreak(document);
    }

    private void renderAppendix(
            XWPFDocument document,
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        addSectionHeading(document, "03", labels.appendix());
        addHeading(document, labels.leadingLeaves(), 2, false);
        XWPFTable leafTable = document.createTable(1, 6);
        leafTable.setWidth("100%");
        setHeaderRow(leafTable.getRow(0), new String[]{
                labels.rank(), labels.node(), labels.score(), labels.taxonomyRoot(),
                labels.hierarchyPath(), labels.rationale()
        });
        int rank = 1;
        for (LeafCandidate leaf : report.leadingLeaves()) {
            XWPFTableRow row = leafTable.createRow();
            setCellText(row.getCell(0), String.valueOf(rank), 8, false, NAVY,
                    ParagraphAlignment.CENTER);
            setCodeAndName(row.getCell(1), leaf.code(), leaf.title());
            setCellText(row.getCell(2), leaf.score() + " %", 8, true, NAVY,
                    ParagraphAlignment.CENTER);
            setCellText(row.getCell(3), leaf.taxonomyRoot(), 8, false, NAVY,
                    ParagraphAlignment.CENTER);
            setCellText(row.getCell(4), leaf.hierarchyPath(), 7, false, NAVY,
                    ParagraphAlignment.LEFT);
            setCellText(row.getCell(5), leaf.reason(), 7, false, NAVY,
                    ParagraphAlignment.LEFT);
            styleDataRow(row, rank % 2 == 0 ? LIGHT_GRAY : "FFFFFF");
            rank++;
        }

        addHeading(document, labels.taxonomyBasis(), 2, false);
        XWPFTable evidence = document.createTable(1, 2);
        evidence.setWidth("100%");
        setMetadataRow(evidence.getRow(0), labels.catalogue(),
                report.metadata().taxonomyDataSource());
        addMetadataRow(evidence, labels.dataVersion(), report.metadata().taxonomyDataVersion());
        addMetadataRow(evidence, labels.sourceFile(), report.metadata().taxonomyCatalogueFile());
        addMetadataRow(evidence, labels.sourceDigest(), report.metadata().taxonomyCatalogueResourceSha256());
        addMetadataRow(evidence, labels.dataDigest(), report.metadata().taxonomyDataFingerprintSha256());
        addMetadataRow(evidence, labels.recordedTaxonomyDigest(),
                report.metadata().recordedTaxonomyFingerprintSha256());
        addMetadataRow(evidence, labels.promptDigest(), report.metadata().promptFingerprintSha256());
        addMetadataRow(evidence, labels.analysisDigest(),
                report.metadata().analysisSnapshotFingerprintSha256());
        addMetadataRow(evidence, labels.analysisSnapshot(), report.metadata().analysisSnapshotId());
        addMetadataRow(evidence, labels.requirementVersion(), requirementVersion(report));
        addMetadataRow(evidence, labels.analysisCreatedAt(),
                formatInstant(report.metadata().analysisCreatedAt(), report.metadata().reportTimeZone()));
        addMetadataRow(evidence, labels.analysisCreatedBy(), report.metadata().analysisCreatedBy());
        addMetadataRow(evidence, labels.repository(), report.metadata().repositoryId());
        addMetadataRow(evidence, labels.workspace(), report.metadata().workspaceId());
        addMetadataRow(evidence, labels.branch(), report.metadata().branch());
        addMetadataRow(evidence, labels.basedOnCommit(), report.metadata().basedOnCommit());
        addMetadataRow(evidence, labels.analysisProvider(), report.metadata().analysisProvider());
        addMetadataRow(evidence, labels.analysisModel(), report.metadata().analysisModel());
        addMetadataRow(evidence, labels.analysisStatus(), report.metadata().analysisStatus());
        addMetadataRow(evidence, labels.completeness(),
                percent(report.metadata().completenessPercent()));

        addHeading(document, labels.methodology(), 2, false);
        addBodyParagraph(document, report.executiveSummary().methodologyNote(), false);
        if (!report.discrepancies().isEmpty()) {
            addHeading(document,
                    labels.german() ? "Dokumentierte Bewertungsabweichungen"
                            : "Documented scoring discrepancies",
                    2,
                    false);
            for (Object discrepancy : report.discrepancies()) {
                addBullet(document, String.valueOf(discrepancy));
            }
        }
        if (!report.warnings().isEmpty()) {
            addWarningBox(document, labels.warnings(), report.warnings());
        }
    }

    private void addChapterHeading(
            XWPFDocument document,
            DecisionChapter chapter,
            DecisionReportLabels labels) {
        XWPFParagraph number = document.createParagraph();
        number.setSpacingAfter(20);
        XWPFRun numberRun = number.createRun();
        numberRun.setText(labels.chapter().toUpperCase(Locale.ROOT) + " " + chapter.number());
        numberRun.setFontFamily(FONT);
        numberRun.setFontSize(9);
        numberRun.setBold(true);
        numberRun.setColor(TEAL);

        XWPFTable heading = document.createTable(1, 2);
        heading.setWidth("100%");
        removeTableBorders(heading);
        XWPFTableCell titleCell = heading.getRow(0).getCell(0);
        XWPFTableCell scoreCell = heading.getRow(0).getCell(1);
        clearCell(titleCell);
        XWPFParagraph title = titleCell.addParagraph();
        title.setSpacingAfter(80);
        XWPFRun code = title.createRun();
        code.setText(chapter.parentCode());
        code.setFontFamily(MONO_FONT);
        code.setFontSize(19);
        code.setBold(true);
        code.setColor(TEAL);
        XWPFRun name = title.createRun();
        name.setText(" · " + chapter.parentTitle());
        name.setFontFamily(FONT);
        name.setFontSize(19);
        name.setBold(true);
        name.setColor(NAVY);
        setBottomBorder(title, NAVY, 16);
        shade(scoreCell, TEAL);
        setCellText(scoreCell, score(chapter.parentScore()), 18, true, "FFFFFF",
                ParagraphAlignment.CENTER);
        scoreCell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
    }

    private void addChildTable(
            XWPFDocument document,
            DecisionChapter chapter,
            DecisionReportLabels labels) {
        XWPFTable table = document.createTable(1, 7);
        table.setWidth("100%");
        setHeaderRow(table.getRow(0), new String[]{
                labels.rank(), labels.node(), labels.score(), labels.localShare(),
                labels.disposition(), labels.rationale(), labels.reasonSource()
        });
        int rowNumber = 0;
        for (ChildDecision child : chapter.children()) {
            XWPFTableRow row = table.createRow();
            setCellText(row.getCell(0), child.rank() == null ? "—" : child.rank().toString(),
                    7, false, NAVY, ParagraphAlignment.CENTER);
            setCodeAndName(row.getCell(1), child.code(), child.title());
            setCellText(row.getCell(2), score(child.absoluteScore()), 7, true, NAVY,
                    ParagraphAlignment.CENTER);
            setCellText(row.getCell(3), percent(child.localSharePercent()), 7, false, NAVY,
                    ParagraphAlignment.CENTER);
            setCellText(row.getCell(4), disposition(child, labels), 7, false, NAVY,
                    ParagraphAlignment.LEFT);
            setCellText(row.getCell(5), child.reason(), 7, false, NAVY,
                    ParagraphAlignment.LEFT);
            setCellText(row.getCell(6), reasonSource(child.reasonSource(), labels), 6, false,
                    MUTED, ParagraphAlignment.LEFT);
            String fill;
            if (child.leadingSibling()) {
                fill = LIGHT_TEAL;
            } else if (child.disposition() == DecisionRationaleReport.Disposition.REJECTED
                    || child.disposition() == DecisionRationaleReport.Disposition.NOT_EVALUATED) {
                fill = LIGHT_GRAY;
            } else {
                fill = rowNumber % 2 == 0 ? "FFFFFF" : "F8FAFB";
            }
            styleDataRow(row, fill);
            rowNumber++;
        }
    }

    private void addAccentBar(XWPFDocument document) {
        XWPFTable bar = document.createTable(1, 2);
        bar.setWidth("100%");
        removeTableBorders(bar);
        shade(bar.getRow(0).getCell(0), NAVY);
        shade(bar.getRow(0).getCell(1), TEAL);
        bar.getRow(0).getCell(0).setWidth("72%");
        bar.getRow(0).getCell(1).setWidth("28%");
        setCellMargins(bar.getRow(0).getCell(0), 40, 0, 40, 0);
        setCellMargins(bar.getRow(0).getCell(1), 40, 0, 40, 0);
        clearCell(bar.getRow(0).getCell(0));
        clearCell(bar.getRow(0).getCell(1));
        // Word table cells must contain at least one paragraph.
        bar.getRow(0).getCell(0).addParagraph().setSpacingAfter(0);
        bar.getRow(0).getCell(1).addParagraph().setSpacingAfter(0);
    }

    private void addSectionHeading(XWPFDocument document, String number, String title) {
        XWPFParagraph kicker = document.createParagraph();
        kicker.setSpacingAfter(20);
        XWPFRun kickerRun = kicker.createRun();
        kickerRun.setText(number);
        kickerRun.setFontFamily(FONT);
        kickerRun.setFontSize(10);
        kickerRun.setBold(true);
        kickerRun.setColor(TEAL);
        XWPFParagraph heading = document.createParagraph();
        heading.setSpacingBefore(0);
        heading.setSpacingAfter(220);
        XWPFRun run = heading.createRun();
        run.setText(title);
        run.setFontFamily(FONT);
        run.setFontSize(23);
        run.setBold(true);
        run.setColor(NAVY);
        setBottomBorder(heading, NAVY, 12);
    }

    private void addHeading(
            XWPFDocument document,
            String text,
            int level,
            boolean pageBreakBefore) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setPageBreak(pageBreakBefore);
        if (paragraph.getCTP().getPPr() == null) {
            paragraph.getCTP().addNewPPr();
        }
        paragraph.setKeepNext(true);
        paragraph.setSpacingBefore(level == 2 ? 240 : 160);
        paragraph.setSpacingAfter(100);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily(FONT);
        run.setBold(true);
        run.setColor(NAVY);
        run.setFontSize(level == 2 ? 15 : 12);
    }

    private void addLabel(XWPFDocument document, String label) {
        XWPFParagraph paragraph = document.createParagraph();
        if (paragraph.getCTP().getPPr() == null) {
            paragraph.getCTP().addNewPPr();
        }
        paragraph.setKeepNext(true);
        paragraph.setSpacingBefore(80);
        paragraph.setSpacingAfter(40);
        XWPFRun run = paragraph.createRun();
        run.setText(label.toUpperCase(Locale.ROOT));
        run.setFontFamily(FONT);
        run.setFontSize(8);
        run.setBold(true);
        run.setColor(TEAL);
        run.setCharacterSpacing(15);
    }

    private void addLeadParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(180);
        setLineSpacing(paragraph, 1.15);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily(FONT);
        run.setFontSize(13);
        run.setColor(NAVY);
    }

    private void addBodyParagraph(XWPFDocument document, String text, boolean muted) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(110);
        setLineSpacing(paragraph, 1.08);
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        run.setFontFamily(FONT);
        run.setFontSize(9);
        run.setColor(muted ? MUTED : "26303A");
    }

    private void addRationaleBox(
            XWPFDocument document,
            String heading,
            String body,
            String fill) {
        XWPFTable table = document.createTable(1, 1);
        table.setWidth("100%");
        removeTableBorders(table);
        XWPFTableCell cell = table.getRow(0).getCell(0);
        shade(cell, fill);
        setTopCellBorder(cell, TEAL, 18);
        setCellMargins(cell, 150, 180, 150, 180);
        clearCell(cell);
        XWPFParagraph headingParagraph = cell.addParagraph();
        headingParagraph.setSpacingAfter(50);
        XWPFRun headingRun = headingParagraph.createRun();
        headingRun.setText(heading);
        headingRun.setFontFamily(FONT);
        headingRun.setFontSize(10);
        headingRun.setBold(true);
        headingRun.setColor(NAVY);
        XWPFParagraph bodyParagraph = cell.addParagraph();
        bodyParagraph.setSpacingAfter(0);
        XWPFRun bodyRun = bodyParagraph.createRun();
        bodyRun.setText(body);
        bodyRun.setFontFamily(FONT);
        bodyRun.setFontSize(9);
        bodyRun.setColor("26303A");
        spacer(document, 6);
    }

    private void addWarningBox(XWPFDocument document, String heading, List<String> warnings) {
        XWPFTable table = document.createTable(1, 1);
        table.setWidth("100%");
        XWPFTableCell cell = table.getRow(0).getCell(0);
        shade(cell, WARNING);
        setLeftCellBorder(cell, WARNING_BORDER, 18);
        setCellMargins(cell, 140, 180, 140, 180);
        clearCell(cell);
        XWPFParagraph title = cell.addParagraph();
        title.setSpacingAfter(50);
        XWPFRun titleRun = title.createRun();
        titleRun.setText(heading);
        titleRun.setFontFamily(FONT);
        titleRun.setFontSize(10);
        titleRun.setBold(true);
        titleRun.setColor("745400");
        for (String warning : warnings) {
            XWPFParagraph bullet = cell.addParagraph();
            bullet.setIndentationLeft(220);
            bullet.setFirstLineIndent(-160);
            bullet.setSpacingAfter(40);
            XWPFRun run = bullet.createRun();
            run.setText("• " + warning);
            run.setFontFamily(FONT);
            run.setFontSize(8);
            run.setColor("4F3C00");
        }
    }

    private void addMetadataRow(XWPFTable table, String label, String value) {
        setMetadataRow(table.createRow(), label, value);
    }

    private void setMetadataRow(XWPFTableRow row, String label, String value) {
        setCellText(row.getCell(0), label, 8, true, NAVY, ParagraphAlignment.LEFT);
        setCellText(row.getCell(1), value, 8, false, "26303A", ParagraphAlignment.LEFT);
        shade(row.getCell(0), LIGHT_GRAY);
        shade(row.getCell(1), "FFFFFF");
        setCellMargins(row.getCell(0), 80, 110, 80, 110);
        setCellMargins(row.getCell(1), 80, 110, 80, 110);
        setCellBorders(row.getCell(0), LINE, 3);
        setCellBorders(row.getCell(1), LINE, 3);
    }

    private void setHeaderRow(XWPFTableRow row, String[] headers) {
        for (int index = 0; index < headers.length; index++) {
            XWPFTableCell cell = row.getCell(index);
            shade(cell, NAVY);
            setCellMargins(cell, 80, 80, 80, 80);
            setCellText(cell, headers[index], 7, true, "FFFFFF", ParagraphAlignment.LEFT);
        }
        row.setRepeatHeader(true);
    }

    private void setCodeAndName(XWPFTableCell cell, String code, String name) {
        clearCell(cell);
        XWPFParagraph paragraph = cell.addParagraph();
        paragraph.setSpacingAfter(0);
        XWPFRun codeRun = paragraph.createRun();
        codeRun.setText(code);
        codeRun.setFontFamily(MONO_FONT);
        codeRun.setFontSize(8);
        codeRun.setBold(true);
        codeRun.setColor(NAVY);
        XWPFRun nameRun = paragraph.createRun();
        nameRun.addBreak();
        nameRun.setText(name);
        nameRun.setFontFamily(FONT);
        nameRun.setFontSize(7);
        nameRun.setColor(MUTED);
    }

    private void setCellText(
            XWPFTableCell cell,
            String value,
            int fontSize,
            boolean bold,
            String color,
            ParagraphAlignment alignment) {
        clearCell(cell);
        XWPFParagraph paragraph = cell.addParagraph();
        paragraph.setAlignment(alignment);
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        XWPFRun run = paragraph.createRun();
        run.setText(value == null || value.isBlank() ? "—" : value);
        run.setFontFamily(FONT);
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setColor(color);
    }

    private void styleDataRow(XWPFTableRow row, String fill) {
        for (XWPFTableCell cell : row.getTableCells()) {
            shade(cell, fill);
            setCellMargins(cell, 70, 70, 70, 70);
            setCellBorders(cell, LINE, 2);
            cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.TOP);
        }
        try {
            row.setCantSplitRow(true);
        } catch (Exception ignored) {
            // Older POI builds may not expose the convenience method.
        }
    }

    private void clearCell(XWPFTableCell cell) {
        while (!cell.getParagraphs().isEmpty()) {
            cell.removeParagraph(0);
        }
    }

    private void shade(XWPFTableCell cell, String color) {
        CTTcPr properties = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTShd shading = properties.isSetShd() ? properties.getShd() : properties.addNewShd();
        shading.setFill(color);
    }

    private void setCellMargins(
            XWPFTableCell cell,
            int top,
            int left,
            int bottom,
            int right) {
        CTTcPr properties = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        var margins = properties.isSetTcMar() ? properties.getTcMar() : properties.addNewTcMar();
        if (!margins.isSetTop()) margins.addNewTop();
        if (!margins.isSetLeft()) margins.addNewLeft();
        if (!margins.isSetBottom()) margins.addNewBottom();
        if (!margins.isSetRight()) margins.addNewRight();
        margins.getTop().setW(BigInteger.valueOf(top));
        margins.getLeft().setW(BigInteger.valueOf(left));
        margins.getBottom().setW(BigInteger.valueOf(bottom));
        margins.getRight().setW(BigInteger.valueOf(right));
    }

    private void setCellBorders(XWPFTableCell cell, String color, int size) {
        CTTcPr properties = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        var borders = properties.isSetTcBorders()
                ? properties.getTcBorders() : properties.addNewTcBorders();
        configureBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop(), color, size);
        configureBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom(), color, size);
        configureBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft(), color, size);
        configureBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight(), color, size);
    }

    private void setLeftCellBorder(XWPFTableCell cell, String color, int size) {
        CTTcPr properties = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        var borders = properties.isSetTcBorders()
                ? properties.getTcBorders() : properties.addNewTcBorders();
        configureBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft(), color, size);
    }

    private void setTopCellBorder(XWPFTableCell cell, String color, int size) {
        CTTcPr properties = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        var borders = properties.isSetTcBorders()
                ? properties.getTcBorders() : properties.addNewTcBorders();
        configureBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop(), color, size);
    }

    private void removeTableBorders(XWPFTable table) {
        CTTbl tableXml = table.getCTTbl();
        CTTblPr properties = tableXml.getTblPr();
        if (properties == null) {
            properties = tableXml.addNewTblPr();
        }
        CTTblBorders borders = properties.isSetTblBorders()
                ? properties.getTblBorders() : properties.addNewTblBorders();
        noBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop());
        noBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom());
        noBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft());
        noBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight());
        noBorder(borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH());
        noBorder(borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV());
    }

    private void noBorder(CTBorder border) {
        border.setVal(STBorder.NIL);
    }

    private void configureBorder(CTBorder border, String color, int size) {
        border.setVal(STBorder.SINGLE);
        border.setColor(color);
        border.setSz(BigInteger.valueOf(size));
    }

    private void setBottomBorder(XWPFParagraph paragraph, String color, int size) {
        CTPPr properties = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTPBdr borders = properties.isSetPBdr() ? properties.getPBdr() : properties.addNewPBdr();
        configureBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom(), color, size);
    }

    private void setTopBorder(XWPFParagraph paragraph, String color, int size) {
        CTPPr properties = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTPBdr borders = properties.isSetPBdr() ? properties.getPBdr() : properties.addNewPBdr();
        configureBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop(), color, size);
    }

    private void addSimpleField(
            XWPFParagraph paragraph,
            String instruction,
            String fallback,
            int fontSize,
            String color) {
        CTSimpleField field = paragraph.getCTP().addNewFldSimple();
        field.setInstr(instruction);
        var runXml = field.addNewR();
        var properties = runXml.addNewRPr();
        properties.addNewColor().setVal(color);
        properties.addNewSz().setVal(BigInteger.valueOf(fontSize * 2L));
        CTText text = runXml.addNewT();
        text.setStringValue(fallback);
    }

    private void setLastPictureAltText(XWPFRun run, String altText) {
        try {
            var pictures = run.getEmbeddedPictures();
            if (!pictures.isEmpty()) {
                var nonVisual = pictures.get(pictures.size() - 1)
                        .getCTPicture().getNvPicPr().getCNvPr();
                nonVisual.setDescr(altText);
                nonVisual.setName("Decision diagram");
            }
        } catch (Exception ignored) {
            // The image remains visible if a particular POI schema build cannot set alt text.
        }
    }

    private void addBullet(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(260);
        paragraph.setFirstLineIndent(-170);
        paragraph.setSpacingAfter(50);
        XWPFRun run = paragraph.createRun();
        run.setText("• " + text);
        run.setFontFamily(FONT);
        run.setFontSize(8);
        run.setColor("26303A");
    }

    private void addPageBreak(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setPageBreak(true);
        paragraph.setSpacingAfter(0);
    }

    private void spacer(XWPFDocument document, int points) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(points * 20);
        paragraph.createRun().setText("");
    }

    private void setLineSpacing(XWPFParagraph paragraph, double multiplier) {
        CTPPr properties = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        var spacing = properties.isSetSpacing() ? properties.getSpacing() : properties.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(Math.round(240 * multiplier)));
        spacing.setLineRule(STLineSpacingRule.AUTO);
    }

    private String statusLabel(DecisionRationaleReport report, DecisionReportLabels labels) {
        return switch (report.status()) {
            case FINAL -> labels.finalStatus();
            case FINAL_WITH_WARNINGS -> labels.finalWarnings();
            case DRAFT_INCOMPLETE -> labels.draft();
            case NO_RESULT -> labels.noResult();
        };
    }

    private String disposition(ChildDecision child, DecisionReportLabels labels) {
        return switch (child.disposition()) {
            case CONTINUED -> labels.continued();
            case LEAF_CANDIDATE -> labels.leafCandidate();
            case REJECTED -> labels.rejected();
            case NOT_EVALUATED -> labels.notEvaluated();
        };
    }

    private String reasonSource(
            DecisionRationaleReport.ReasonSource source,
            DecisionReportLabels labels) {
        if (source == null) {
            return labels.missingReason();
        }
        return switch (source) {
            case AI_SCORING -> labels.aiReason();
            case DETERMINISTIC_TRACE -> labels.deterministicReason();
            case MISSING -> labels.missingReason();
        };
    }

    private String requirementVersion(DecisionRationaleReport report) {
        if (report.metadata().requirementVersionNumber() == null) {
            return "—";
        }
        StringBuilder value = new StringBuilder("v")
                .append(report.metadata().requirementVersionNumber());
        if (report.metadata().requirementId() != null) {
            value.append(" · requirement ").append(report.metadata().requirementId());
        }
        if (report.metadata().projectId() != null) {
            value.append(" · project ").append(report.metadata().projectId());
        }
        return value.toString();
    }

    private String footerText(DecisionRationaleReport report, DecisionReportLabels labels) {
        return labels.generatedNotice() + " · "
                + formatInstant(report.metadata().generatedAt(), report.metadata().reportTimeZone())
                + " · " + report.metadata().generatedBy()
                + " · Taxonomy " + report.metadata().taxonomyApplicationVersion()
                + " · " + report.metadata().taxonomyDataVersion();
    }

    private String formatInstant(Instant instant, String zone) {
        if (instant == null) {
            return "—";
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (Exception ignored) {
            zoneId = ZoneOffset.UTC;
        }
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z", Locale.GERMANY)
                .withZone(zoneId)
                .format(instant);
    }

    private String score(Integer value) {
        return value == null ? "—" : value + " %";
    }

    private String percent(Double value) {
        if (value == null) {
            return "—";
        }
        return Math.abs(value - Math.rint(value)) < 0.05
                ? String.format(Locale.ROOT, "%.0f %%", value)
                : String.format(Locale.ROOT, "%.1f %%", value);
    }
}
