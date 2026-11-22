package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;

import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;

import static org.assertj.core.api.Assertions.*;

import com.taxonomy.diagram.*;
import com.taxonomy.export.LayeredDiagramLayoutService;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.util.*;

class ArchitectureReportDocxRendererTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "de"})
    void frozenDocumentHasSemanticTablesAccessibleFiguresAndSourceIdentity(String language)
            throws Exception {
        var graph = ArchitectureFigurePlannerTest.graph(15, true);
        var evidence =
                new ArchitectureReportDocument.SnapshotEvidence(
                        1L,
                        2L,
                        3L,
                        4,
                        "snapshot-1",
                        "repo",
                        "ws",
                        "main",
                        "abc",
                        "MOCK",
                        "frozen-model",
                        "taxonomy-sha",
                        ArchitectureReportDocument.graphSha256(graph));
        var model =
                ArchitectureReportDocuments.from(
                        "Saved requirement",
                        language,
                        "Clause 1: Saved requirement\r\n\r\nClause 2: A separate obligation",
                        "Saved scope",
                        "Saved conclusion",
                        List.of("Saved unresolved gap"),
                        graph,
                        new LayeredDiagramLayoutService().layout(graph),
                        DecisionTreeOverview.from(List.of()),
                        evidence);
        byte[] bytes = new ArchitectureReportDocxRenderer().render(model);
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String xml = document.getDocument().xmlText();
            String text = new XWPFWordExtractor(document).getText();
            assertThat(document.getParagraphs()).extracting(XWPFParagraph::getText)
                    .contains("Clause 1: Saved requirement", "Clause 2: A separate obligation");
            assertThat(document.getHeaderList()).hasSize(1);
            assertThat(document.getHeaderList().getFirst().getText())
                    .contains(language.equals("de") ? "Architekturbericht" : "Architecture report", "snapshot");
            assertThat(document.getFooterList()).hasSize(1);
            assertThat(document.getFooterList().getFirst()._getHdrFtr().xmlText())
                    .contains("PAGE", "NUMPAGES", language.equals("de") ? "Seite" : "Page");
            assertThat(document.getStyles().getCtStyles().getDocDefaults().xmlText()).contains("Aptos");
            assertThat(xml).doesNotContain("TOC ");
            assertThat(document.getTables().getFirst().getNumberOfRows()).isGreaterThan(1);
            assertThat(document.getTables().getFirst().getRow(0).getTableCells())
                    .allSatisfy(c -> assertThat(c.getParagraphs().getFirst().isKeepNext()).isTrue());
            var qa=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/final-word-review"));
            java.nio.file.Files.write(qa.resolve("standalone-" + language + ".docx"), bytes);
            assertThat(document.getTables().size()).isGreaterThanOrEqualTo(4);
            assertThat(xml)
                    .contains(
                            "Heading1",
                            "Caption",
                            "SEQ Figure",
                            "tblHeader",
                            "cantSplit",
                            "descr=",
                            "title=");
            assertThat(document.getSettings().getCTSettings().isSetUpdateFields()).isFalse();
            assertThat(document.getAllPictures().size()).isGreaterThan(2);
            assertThat(text)
                    .contains(
                            "Saved requirement",
                            "Saved scope",
                            "Saved conclusion",
                            "Saved unresolved gap",
                            "N14",
                            "E12",
                            "snapshot-1",
                            evidence.graphSha256());
            assertThat(
                            document.getProperties()
                                    .getCustomProperties()
                                    .getProperty("taxonomy.graph.sha256")
                                    .getLpwstr())
                    .isEqualTo(evidence.graphSha256());
            assertThat(document.getParagraphs()).noneMatch(p -> p.getText().contains("\t"));
            assertThat(text)
                    .contains(
                            language.equals("de")
                                    ? "Architekturübersicht"
                                    : "Architecture overview");
        }
    }

    @org.junit.jupiter.api.Test
    void frozenDocumentPreservesUnavailableAndRecordedZeroMetrics() throws Exception {
        var view = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {"includedElements":[
                  {"nodeCode":"source-evidence","title":"Evidence-only source","taxonomySheet":"CI","origin":"REQUIREMENT_EVIDENCE"},
                  {"nodeCode":"unscored-endpoint","title":"Unscored endpoint","taxonomySheet":"BP","origin":"RELATION_EVIDENCE",
                   "relevance":0,"taxonomyDepth":0},
                  {"nodeCode":"scored-root","title":"Scored root","taxonomySheet":"CP","origin":"DIRECT_SCORED",
                   "relevance":0,"taxonomyDepth":0,"hierarchyPath":"scored-root",
                   "scoreDetail":{"nodeCode":"scored-root","kind":"HIERARCHICAL_RELEVANCE","rawScore":0,"effectiveRelevance":0}},
                  {"nodeCode":"typed-child","title":"Typed child","taxonomySheet":"CI","origin":"RELATION_EVIDENCE",
                   "hierarchyPath":"CI > typed-child", "scoreDetail":{"nodeCode":"typed-child","kind":"HIERARCHICAL_RELEVANCE","rawScore":60,"effectiveRelevance":60}}]}
                """, com.taxonomy.dto.RequirementArchitectureView.class);
        var graph = com.taxonomy.export.PersistedDiagramProjection.project(
                new com.taxonomy.export.DiagramProjectionService(), view, "Saved architecture");
        var evidence = new ArchitectureReportDocument.SnapshotEvidence(1L, 2L, 3L, 4, "snapshot-1",
                "repo", "ws", "main", "abc", "MOCK", "model", "sha", ArchitectureReportDocument.graphSha256(graph));
        var report = ArchitectureReportDocuments.from("Saved architecture", "en", "Saved text", "Saved scope",
                "Saved recommendation", List.of(), graph, new LayeredDiagramLayoutService().layout(graph),
                DecisionTreeOverview.from(List.of()), evidence);

        try (var document = new XWPFDocument(new ByteArrayInputStream(new ArchitectureReportDocxRenderer().render(report)))) {
            assertThat(inventoryRow(document, "source-evidence").subList(3, 5)).containsExactly("Not evaluated", "Unknown");
            assertThat(inventoryRow(document, "unscored-endpoint").subList(3, 5)).containsExactly("Not evaluated", "Unknown");
            assertThat(inventoryRow(document, "scored-root").subList(3, 5)).containsExactly("0%", "0");
            assertThat(inventoryRow(document, "typed-child").subList(3, 5)).containsExactly("60%", "1");
        }
    }

    private static List<String> inventoryRow(XWPFDocument document, String node) {
        return document.getTables().stream().flatMap(table -> table.getRows().stream())
                .map(row -> row.getTableCells().stream().map(XWPFTableCell::getText).toList())
                .filter(cells -> cells.size() == 6 && node.equals(cells.getFirst()))
                .findFirst().orElseThrow();
    }

    @org.junit.jupiter.api.Test
    void liveReportRationaleCellUsesEditableWordBreaks() throws Exception {
        var view = new com.taxonomy.dto.RequirementArchitectureView();
        var node = new com.taxonomy.dto.RequirementElementView();
        node.setNodeCode("retained-source");
        node.setTitle("Retained source");
        node.setTaxonomySheet("CP");
        node.setIncludedBecause("First evidence line\r\n\r\nSecond evidence line → preserved");
        view.setIncludedElements(List.of(node));
        var report = new com.taxonomy.dto.ArchitectureReport();
        report.setBusinessText("A saved requirement");
        report.setArchitectureView(view);

        try (var document = new XWPFDocument(new ByteArrayInputStream(new ArchitectureReportDocxRenderer().render(report)))) {
            var rationale = document.getTables().stream().flatMap(table -> table.getRows().stream())
                    .flatMap(row -> row.getTableCells().stream())
                    .filter(cell -> cell.getText().contains("First evidence line")).findFirst().orElseThrow();
            var run = rationale.getParagraphs().getFirst().getRuns().getFirst();
            assertThat(run.getCTR().sizeOfBrArray()).isEqualTo(2);
            assertThat(run.getCTR().getTList()).extracting(value -> value.getStringValue())
                    .containsExactly("First evidence line", "", "Second evidence line → preserved");
        }
    }

    @org.junit.jupiter.api.Test
    void legacyReportUsesRealInventoryTables() throws Exception {
        var model = new com.taxonomy.dto.ArchitectureReport();
        model.setBusinessText("Legacy requirement");
        model.setScores(Map.of("BP", 100d, "CP", 0d));
        try (var doc =
                new XWPFDocument(
                        new ByteArrayInputStream(
                                new ArchitectureReportDocxRenderer().render(model)))) {
            assertThat(doc.getTables()).isNotEmpty();
            assertThat(new XWPFWordExtractor(doc).getText())
                    .contains("BP", "CP", "Legacy requirement");
            assertThat(doc.getParagraphs()).noneMatch(p -> p.getText().contains("\t"));
        }
    }

    @org.junit.jupiter.api.Test
    void customTemplateStylesAndHeaderBookmarkIdsArePreserved() throws Exception {
        try (var doc = new XWPFDocument()) {
            var styles = doc.createStyles();
            var style =
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory
                            .newInstance();
            style.setStyleId("Heading1");
            style.addNewName().setVal("Custom heading");
            style.addNewRPr().addNewColor().setVal("AA22BB");
            styles.addStyle(new XWPFStyle(style));
            var header = doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            var p = header.createParagraph();
            p.createRun().setText("Custom header");
            var start = p.getCTP().addNewBookmarkStart();
            start.setId(java.math.BigInteger.valueOf(999));
            start.setName("custom_header");
            p.getCTP().addNewBookmarkEnd().setId(java.math.BigInteger.valueOf(999));
            var writer =
                    new WordDocumentWriter(
                            doc, new com.taxonomy.reporting.render.decision.DecisionReportLabels("en"));
            var heading = writer.heading("Generated heading", 1, "new_heading");
            assertThat(heading.getCTP().getBookmarkStartArray(0).getId())
                    .isGreaterThan(java.math.BigInteger.valueOf(999));
            assertThat(doc.getStyles().getStyle("Heading1").getCTStyle().xmlText())
                    .contains("AA22BB");
            assertThat(header.getText()).contains("Custom header");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 24})
    void tallFigureAndCaptionFitActiveLandscapeTemplateBody(int captionFont) throws Exception {
        try (var doc = new XWPFDocument()) {
            var styles = doc.createStyles();
            var base =
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory
                            .newInstance();
            base.setStyleId("CustomCaptionBase");
            base.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);
            base.addNewName().setVal("Custom Caption Base");
            base.addNewRPr().addNewSz().setVal(java.math.BigInteger.valueOf(captionFont * 2));
            styles.addStyle(new XWPFStyle(base));
            var caption =
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory
                            .newInstance();
            caption.setStyleId("Caption");
            caption.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);
            caption.addNewName().setVal("Caption");
            caption.addNewBasedOn().setVal("CustomCaptionBase");
            styles.addStyle(new XWPFStyle(caption));
            var previous = doc.createParagraph().getCTP().addNewPPr().addNewSectPr();
            previous.addNewPgSz().setH(java.math.BigInteger.valueOf(16838));
            var section = doc.getDocument().getBody().addNewSectPr();
            section.addNewPgSz().setW(java.math.BigInteger.valueOf(15840));
            section.getPgSz().setH(java.math.BigInteger.valueOf(12240));
            var margin = section.addNewPgMar();
            margin.setTop(java.math.BigInteger.valueOf(1440));
            margin.setBottom(java.math.BigInteger.valueOf(1440));
            margin.setLeft(java.math.BigInteger.valueOf(1440));
            margin.setRight(java.math.BigInteger.valueOf(1440));
            var writer =
                    new WordDocumentWriter(
                            doc, new com.taxonomy.reporting.render.decision.DecisionReportLabels("en"));
            writer.picture(
                    tallPng(), "tall", "Architecture detail 1", "Saved detail", 1000, 1300, true);
            writer.caption("Architecture detail 1", true);
            var drawing =
                    doc.getParagraphs()
                            .get(1)
                            .getRuns()
                            .getFirst()
                            .getCTR()
                            .getDrawingArray(0)
                            .getInlineArray(0);
            double height = drawing.getExtent().getCy() / 12700.0;
            assertThat(height + Math.max(32, captionFont * 1.5 + 12))
                    .as("figure plus kept caption and paragraph spacing")
                    .isLessThanOrEqualTo(468);
            assertThat(drawing.getExtent().getCx() / 12700.0 * 30 / 1000).isGreaterThanOrEqualTo(8);
            var artifacts =
                    java.nio.file.Files.createDirectories(
                            java.nio.file.Path.of("target/frozen-word-review"));
            try (var output =
                    java.nio.file.Files.newOutputStream(
                            artifacts.resolve("landscape-caption-" + captionFont + ".docx"))) {
                doc.write(output);
            }
        }
    }

    @org.junit.jupiter.api.Test
    void tinyTemplateRejectsUnreadableDetailInsteadOfOverflow() throws Exception {
        try (var doc = new XWPFDocument()) {
            var section = doc.getDocument().getBody().addNewSectPr();
            section.addNewPgSz().setW(java.math.BigInteger.valueOf(15840));
            section.getPgSz().setH(java.math.BigInteger.valueOf(4320));
            var margin = section.addNewPgMar();
            margin.setTop(java.math.BigInteger.valueOf(1440));
            margin.setBottom(java.math.BigInteger.valueOf(1440));
            margin.setLeft(java.math.BigInteger.valueOf(1440));
            margin.setRight(java.math.BigInteger.valueOf(1440));
            var writer =
                    new WordDocumentWriter(
                            doc, new com.taxonomy.reporting.render.decision.DecisionReportLabels("en"));
            assertThatThrownBy(
                            () ->
                                    writer.picture(
                                            tallPng(),
                                            "tall",
                                            "Architecture detail 1",
                                            "Saved detail",
                                            1000,
                                            1300,
                                            true))
                    .hasMessageContaining("template")
                    .hasMessageContaining("8pt");
        }
    }

    private static byte[] tallPng() throws Exception {
        var image =
                new java.awt.image.BufferedImage(
                        1000, 1300, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (var out = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }
}
