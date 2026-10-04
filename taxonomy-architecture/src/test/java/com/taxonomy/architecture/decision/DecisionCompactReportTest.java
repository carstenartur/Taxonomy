package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.report.*;
import com.taxonomy.architecture.decision.DecisionRationaleReport.Disposition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DecisionCompactReportTest {
    @Test void compactHasSourceAndTreeWithoutCoverChaptersOrAppendix() throws Exception {
        var report = report(DecisionReportOptions.TreeLayout.AUTO, 4);
        byte[] bytes = new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(report);
        saveFixture("compact.docx", bytes);
        saveFixture("a3.docx", new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(report(DecisionReportOptions.TreeLayout.AUTO, 20)));
        saveFixture("paginated.docx", new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(report(DecisionReportOptions.TreeLayout.AUTO, 65)));
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = new XWPFWordExtractor(doc).getText();
            assertThat(text).contains("snapshot", "CP", new DecisionReportLabels("en").finalStatus(), report.requirement())
                    .doesNotContain(new DecisionReportLabels("en").appendix());
            assertThat(doc.getDocument().xmlText()).contains("landscape", "1-1");
            assertThat(doc.getAllPictures()).hasSize(1);
            assertThat(doc.getDocument().getBody().getSectPr().getPgSz().getOrient().toString()).isEqualTo("landscape");
        }
        String html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(report);
        assertThat(html).contains("<svg", "CP-3", "snapshot").doesNotContain("class=\"title-page\"", "class=\"decision-chapter\"");
    }
    @Test void compactIncludesSavedEssentialReasonsAndTheirSource() throws Exception {
        var base = report(DecisionReportOptions.TreeLayout.AUTO, 4);
        var leaf = new DecisionRationaleReport.LeafCandidate("CP-1", "Saved leaf", 90, "CP", 1, "CP → CP-1",
                "Saved reason: preserves the source timestamp.", DecisionRationaleReport.ReasonSource.AI_SCORING);
        var report = new DecisionRationaleReport(base.title(), base.languageTag(), base.requirement(), base.status(),
                base.metadata(), base.executiveSummary(), base.chapters(), List.of(leaf), base.warnings(),
                base.productCoverageGaps(), base.discrepancies(), base.viewContext()).withScope(base.scope());
        try (var doc = new XWPFDocument(new ByteArrayInputStream(new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(report)))) {
            assertThat(new XWPFWordExtractor(doc).getText()).contains(leaf.reason(), new DecisionReportLabels("en").aiReason());
        }
        assertThat(new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(report)).contains(leaf.reason());
    }
    @Test void figuresPreserveEveryNodeAndLongUnicodeLabelsAtReadableSize() {
        var report = report(DecisionReportOptions.TreeLayout.AUTO, 65);
        var panels = new DecisionTreeFigureRenderer().render(report.scope().decisionTree(), "en", DecisionReportOptions.TreeLayout.AUTO);
        assertThat(panels).hasSizeGreaterThan(1);
        assertThat(panels.stream().flatMap(p -> p.nodeCodes().stream()).toList())
                .containsExactlyElementsOf(report.scope().decisionTree().rows().stream().map(DecisionTreeOverview.DecisionTreeRow::code).toList());
        assertThat(panels).allSatisfy(p -> { assertThat(p.fontSize()).isGreaterThanOrEqualTo(8); assertThat(p.png()).isNotEmpty(); });
        assertThat(panels.stream().map(DecisionTreeFigureRenderer.Panel::svg).reduce("", String::concat))
                .contains("Überprüfung", "Ω", "nicht");
    }
    @Test void compactRetainsReasonsForEverySelectedRootEvenOutsideTheLeadingRanking() {
        var base = report(DecisionReportOptions.TreeLayout.AUTO, 4);
        var rows = new ArrayList<>(base.scope().decisionTree().rows());
        rows.add(new DecisionTreeOverview.DecisionTreeRow(0, "BP", "Business processes", 0, Disposition.REJECTED, null, null));
        var scope = new DecisionReportScope(null, null, List.of(), Set.of("CP", "BP"), Set.of("CP", "BP"),
                new DecisionTreeOverview(rows, List.of()), base.scope().options(), true, true)
                .withRecordedReasons(Map.of("CP", "Capability reason.", "BP", "No business-process match."));
        assertThat(String.join("\n", DecisionReportPresentation.compactReasons(base.withScope(scope))))
                .contains("Capability reason.", "No business-process match.", new DecisionReportLabels("en").aiReason());
    }
    @Test void autoChoosesA3BeforeSplittingAMediumTree() {
        var panels = new DecisionTreeFigureRenderer().render(report(DecisionReportOptions.TreeLayout.AUTO, 20)
                .scope().decisionTree(), "en", DecisionReportOptions.TreeLayout.AUTO);
        assertThat(panels).hasSize(1);
        assertThat(panels.getFirst().a3()).isTrue();
        assertThat(panels.getFirst().nodeCodes()).hasSize(20);
    }
    @Test void strictSinglePageExplainsWhenTheWholeTreeCannotFit() {
        assertThatThrownBy(() -> new DecisionTreeFigureRenderer().render(report(DecisionReportOptions.TreeLayout.AUTO, 65)
                .scope().decisionTree(), "en", DecisionReportOptions.TreeLayout.A4_LANDSCAPE))
                .isInstanceOf(WordReportLayoutException.class).hasMessageContaining("A3");
    }
    @Test void returnsToPortraitForFollowingEvidenceAndCanOmitContents() throws Exception {
        var base = report(DecisionReportOptions.TreeLayout.AUTO, 4);
        var s = base.scope();
        var options = new DecisionReportOptions(DecisionReportOptions.Profile.COMPACT, Set.of("CP"), DecisionReportOptions.Contents.NONE,
                DecisionReportOptions.TreeLayout.AUTO, Set.of(DecisionReportOptions.Section.TREE, DecisionReportOptions.Section.EVIDENCE));
        var report = base.withScope(new DecisionReportScope(null, null, s.availableRoots(), s.reportRoots(), s.selectedNodeCodes(),
                s.decisionTree(), options, true, true));
        byte[] bytes = new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(report);
        saveFixture("portrait-return.docx", bytes);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(doc.getDocument().xmlText()).contains("landscape").doesNotContain("TOC ");
            assertThat(doc.getDocument().getBody().getSectPr().getPgSz().getOrient().toString()).isEqualTo("portrait");
        }
    }
    private static void saveFixture(String filename, byte[] bytes) throws Exception {
        String directory = System.getProperty("decision.export.fixtures");
        if (directory != null) { var path = java.nio.file.Path.of(directory); java.nio.file.Files.createDirectories(path); java.nio.file.Files.write(path.resolve(filename), bytes); }
    }
    @Test void compactTemplateKeepsHeaderStylesAndProvenanceWithoutCover() throws Exception {
        var templates = org.mockito.Mockito.mock(com.taxonomy.templates.DocumentTemplateService.class);
        var contract = new com.taxonomy.templates.DecisionRationaleTemplateContract();
        byte[] dotx;
        try (var in = getClass().getResourceAsStream("/" + com.taxonomy.templates.DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) { dotx = in.readAllBytes(); }
        var manifest = new com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest(1,
                com.taxonomy.templates.DecisionRationaleTemplateContract.TEMPLATE_ID, "Default", "default.dotx",
                com.taxonomy.templates.OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE, "2026-10-03T00:00:00Z", "author", dotx.length, 10, "a".repeat(64));
        org.mockito.Mockito.when(templates.downloadCurrentValidated(com.taxonomy.templates.DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenReturn(new com.taxonomy.templates.DocumentTemplateService.TemplateFile(manifest, "b".repeat(40), dotx, java.time.Instant.EPOCH));
        byte[] bytes = new DecisionRationaleTemplateRenderer(templates, contract).render(new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()), report(DecisionReportOptions.TreeLayout.AUTO, 4));
        saveFixture("compact-template.docx", bytes);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(doc.getHeaderList()).isNotEmpty(); assertThat(doc.getStyles().styleExist("Heading1")).isTrue();
            assertThat(doc.getProperties().getCustomProperties().getProperty("Taxonomy.Template.Commit").getLpwstr()).isEqualTo("b".repeat(40));
            assertThat(doc.getDocument().xmlText()).doesNotContain(com.taxonomy.templates.DecisionRationaleTemplateContract.BODY_MARKER);
        }
    }
    static DecisionRationaleReport report(DecisionReportOptions.TreeLayout layout, int size) {
        var rows = new ArrayList<DecisionTreeOverview.DecisionTreeRow>();
        rows.add(new DecisionTreeOverview.DecisionTreeRow(0, "CP", "Capabilities", 100, Disposition.CONTINUED, null, null));
        for (int i = 1; i < size; i++) rows.add(new DecisionTreeOverview.DecisionTreeRow(1, "CP-" + i,
                "Überprüfung Ω – nicht abgeschnittene Beschriftung " + i, i == 2 ? null : 0,
                i == 2 ? Disposition.NOT_EVALUATED : Disposition.REJECTED, null, null));
        var options = new DecisionReportOptions(DecisionReportOptions.Profile.COMPACT, Set.of("CP"), null, layout, null);
        return DecisionRationaleTemplateRendererTest.report().withScope(new DecisionReportScope(null, null,
                List.of(new DecisionReportScope.Root("CP", "Capabilities", true)), Set.of("CP"), Set.of("CP"),
                new DecisionTreeOverview(rows, List.of()), options, true, true));
    }
}
