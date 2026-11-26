package com.taxonomy.reporting.render.decision;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.reporting.render.document.ArchitectureReportDocxRenderer;
import com.taxonomy.reporting.render.document.ArchitectureReportDocuments;
import com.taxonomy.reporting.templates.DecisionRationaleTemplateContract;
import com.taxonomy.templates.api.DocumentTemplates;
import com.taxonomy.templates.api.OoxmlTemplateMediaTypes;
import com.taxonomy.templates.api.TemplateFile;
import com.taxonomy.templates.api.TemplateManifest;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportPresentationRegressionTest {
    private static final String ROOT_DIAGNOSTIC = "ROOT_SOURCE_UNRESOLVED BR: identify a concrete required contribution below this catalogue container.";
    private static final String FAMILY_DIAGNOSTIC = "The product family is relevant, but none of its 8 catalogued products reached the suitability threshold of 50.";
    private static final String INSTRUCTION = "Diese Titelseite, Kopf- und Fußzeilen sowie Organisationsangaben können in Microsoft Word angepasst werden.";

    @Test void onlyTypedDeterministicReasonsAreLocalized() {
        String original = "No separate AI reason was available for this positive score. Inclusion in the report follows only from the stored value and taxonomy hierarchy.";
        var labels = new DecisionReportLabels("de");
        assertThat(DecisionReportPresentation.reason(original, DecisionRationaleReport.ReasonSource.AI_SCORING, labels)).isEqualTo(original);
        assertThat(DecisionReportPresentation.reason(original, null, labels)).isEqualTo(original);
        assertThat(DecisionReportPresentation.reason(original, DecisionRationaleReport.ReasonSource.DETERMINISTIC_TRACE, labels))
                .contains("keine separate KI-Einzelbegründung", "gespeicherten Wert");
        assertThat(DecisionReportPresentation.reason("Original custom explanation.", DecisionRationaleReport.ReasonSource.DETERMINISTIC_TRACE, labels))
                .isEqualTo("Original custom explanation.");
    }

    @Test void bootstrapCoverAndFooterAreLocalizedWithoutChangingSavedOrAdministratorTemplates() throws Exception {
        var contract = new DecisionRationaleTemplateContract();
        byte[] original;
        try (var input = getClass().getResourceAsStream("/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            original = input.readAllBytes();
        }
        for (String language : List.of("de", "en")) for (String owner : List.of("taxonomy-bootstrap", "administrator")) {
            var manifest = new TemplateManifest(1, contract.templateId(), "Review template", "review.dotx",
                    OoxmlTemplateMediaTypes.DOTX_MEDIA_TYPE, Instant.EPOCH.toString(), owner, original.length, 10, "a".repeat(64));
            var source = new TemplateFile(manifest, "b".repeat(40), original, Instant.EPOCH);
            byte[] generated = new DecisionRationaleTemplateRenderer(mock(DocumentTemplates.class), contract)
                    .renderArtifact(new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()), report(language), source).bytes();
            try (var document = new XWPFDocument(new ByteArrayInputStream(generated)); var extractor = new XWPFWordExtractor(document)) {
                String text = extractor.getText();
                var footer = document.getFooterList().getFirst().getTables().getFirst();
                if (owner.equals("taxonomy-bootstrap")) {
                    assertThat(text).doesNotContain("Organisation · Organisationseinheit");
                    assertThat(footer.getCTTbl().getTblGrid().getGridColArray(0).getW().toString()).isEqualTo("7200");
                    assertThat(footer.getCTTbl().getTblGrid().getGridColArray(1).getW().toString()).isEqualTo("1800");
                    if (language.equals("en")) assertThat(text).contains("can be edited in Microsoft Word").doesNotContain(INSTRUCTION);
                    else assertThat(text).contains(INSTRUCTION);
                    var target = Files.createDirectories(Path.of("target/qa-report-display"));
                    Files.write(target.resolve("decision-" + language + ".docx"), generated);
                } else {
                    assertThat(text).contains("Organisation · Organisationseinheit", INSTRUCTION);
                    assertThat(footer.getCTTbl().getTblGrid().getGridColArray(0).getW().toString()).isEqualTo("7600");
                    assertThat(footer.getCTTbl().getTblGrid().getGridColArray(1).getW().toString()).isEqualTo("1400");
                }
            }
            assertThat(source.content()).isEqualTo(original);
        }
    }

    @Test void germanReportsTranslateSystemDiagnosticsAndKeepOriginalReasons() throws Exception {
        var report = report("de");
        String notice = String.join("\n", DecisionReportPresentation.sourceNotice(report));
        assertThat(notice).contains("konkreten erforderlichen Beitrag", "8 katalogisierten Produkte", "Eignungsschwelle von 50");
        try (var doc = new XWPFDocument(new ByteArrayInputStream(new ArchitectureReportDocxRenderer().render(report.architecture())));
             var extractor = new XWPFWordExtractor(doc)) {
            assertThat(extractor.getText()).contains("konkreten erforderlichen Beitrag", ROOT_DIAGNOSTIC);
        }
        assertThat(report.warnings()).containsExactly(ROOT_DIAGNOSTIC, FAMILY_DIAGNOSTIC);
        assertThat(report.architecture().nodeReasons()).containsEntry("CI-1151", ROOT_DIAGNOSTIC);
    }

    @Test void germanParentAndIdentifierHeadersHaveRoomForCompleteWordsAndDistinctProvenance() throws Exception {
        for (String language : List.of("de", "en")) {
            byte[] generated = new ArchitectureReportDocxRenderer().render(report(language).architecture());
            try (var document = new XWPFDocument(new ByteArrayInputStream(generated))) {
                var labels = new DecisionReportLabels(language);
                var elements = document.getTables().stream().filter(table -> table.getRow(0).getTableCells().stream()
                        .anyMatch(cell -> cell.getText().equals(labels.parentNode()))).findFirst().orElseThrow();
                assertThat(elements.getRow(0).getCell(5).getWidthDecimal()).isGreaterThanOrEqualTo(17);
                var relations = document.getTables().stream().filter(table -> table.getRow(0).getTableCells().size() == 6
                        && table.getRow(0).getCell(1).getText().equals(labels.source())).findFirst().orElseThrow();
                assertThat(relations.getRow(0).getCell(0).getWidthDecimal()).isGreaterThanOrEqualTo(13);
            }
            Files.write(Files.createDirectories(Path.of("target/qa-report-display")).resolve("architecture-" + language + ".docx"), generated);
        }
        byte[] decision = new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(report("de"));
        try (var doc = new XWPFDocument(new ByteArrayInputStream(decision))) {
            assertThat(doc.getParagraphs().stream().filter(p -> "Heading1".equals(p.getStyle())).map(p -> p.getText()).toList())
                    .contains("Architekturquelle und Versionsnachweis", "Berichtsquelle und Versionsnachweis");
            var tree = doc.getTables().stream().filter(table -> table.getRow(0).getTableCells().size() == 4
                    && table.getRow(0).getCell(3).getText().equals("Entscheidung")).findFirst().orElseThrow();
            assertThat(tree.getRow(0).getCell(3).getWidthDecimal()).isGreaterThanOrEqualTo(20);
        }
    }

    static DecisionRationaleReport report(String language) {
        var base = DecisionRationaleTemplateRendererTest.report();
        var at = Instant.parse("2026-11-26T00:00:00Z");
        var metadata = new DecisionRationaleReport.ReportMetadata(at, "renderer-fixture", "1.5.4-SNAPSHOT",
                "uncommitted-renderer-fixture", "fixture.xlsx", "fixture", "catalogue-fixture", "data-fixture", "analysis-fixture",
                "Authored renderer fixture", 5, 1, "fixture-repository", "fixture-workspace", "fixture-branch", "a".repeat(40),
                at, false, false, "MOCK", "PARTIAL", "fixture-model", "fixture-snapshot", 1L, 2L, 3L, 4, at,
                "fixture-author", "fixture-taxonomy", "fixture-prompt", true, "UTC", 3, 4, 3, 80.0);
        var children = List.of(
                new DecisionRationaleReport.ChildDecision("CP-1", "Selected alternative", "", 70d, 70d, 1, true,
                        DecisionRationaleReport.Disposition.CONTINUED, "Original analysis evidence retained verbatim.",
                        DecisionRationaleReport.ReasonSource.AI_SCORING, false),
                new DecisionRationaleReport.ChildDecision("CP-2", "Evaluated alternative", "", 0d, 0d, 3, false,
                        DecisionRationaleReport.Disposition.REJECTED, "The node was evaluated, scored 0%, and was therefore not pursued in this path.",
                        DecisionRationaleReport.ReasonSource.DETERMINISTIC_TRACE, true),
                new DecisionRationaleReport.ChildDecision("CP-3", "Pending alternative", "", null, null, null, false,
                        DecisionRationaleReport.Disposition.NOT_EVALUATED, null, DecisionRationaleReport.ReasonSource.MISSING, true),
                new DecisionRationaleReport.ChildDecision("CP-4", "Leaf candidate", "", 30d, 30d, 2, false,
                        DecisionRationaleReport.Disposition.LEAF_CANDIDATE, "Original candidate explanation.",
                        DecisionRationaleReport.ReasonSource.AI_SCORING, true));
        var chapters = List.of(new DecisionRationaleReport.DecisionChapter(1, "CP", "Capabilities", "", 100d, 0,
                false, "Recorded decision remains incomplete.", "Review the missing assessment before deciding.", children, List.of("CP-3")));
        var options = new com.taxonomy.reporting.api.decision.DecisionReportOptions(
                com.taxonomy.reporting.api.decision.DecisionReportOptions.Profile.FULL, null,
                com.taxonomy.reporting.api.decision.DecisionReportOptions.Contents.FULL,
                com.taxonomy.reporting.api.decision.DecisionReportOptions.TreeLayout.TABLE, null);
        var scope = new com.taxonomy.reporting.api.decision.DecisionReportScope(null, null,
                List.of(new com.taxonomy.reporting.api.decision.DecisionReportScope.Root("CP", "Capabilities", true)),
                java.util.Set.of("CP"), java.util.Set.of("CP", "CP-1", "CP-2", "CP-3", "CP-4"),
                com.taxonomy.reporting.api.document.DecisionTreeOverview.from(chapters), options, false, false);
        var graph = new DiagramModel("Captured architecture", List.of(
                new DiagramNode("CI-1151", "Education and Training Services", "COI Services", .85, true, 3),
                new DiagramNode("CP-1122", "IT Services Capabilities", "Capabilities", .7, false, 2)),
                List.of(new DiagramEdge("R1", "CI-1151", "CP-1122", "SUPPORTS", .75, "impact")), new DiagramLayout("LR", true));
        var evidence = new ArchitectureReportDocument.SnapshotEvidence(1L, 2L, 3L, 4, "fixture-snapshot", "fixture-repository",
                "fixture-workspace", "fixture-branch", "a".repeat(40), "MOCK", "fixture-model", "fixture-taxonomy",
                ArchitectureReportDocument.graphSha256(graph));
        var architecture = ArchitectureReportDocuments.from("Captured architecture", language, base.requirement(),
                "Frozen snapshot · PARTIAL", "Review the recorded unfinished work.", List.of(ROOT_DIAGNOSTIC, FAMILY_DIAGNOSTIC),
                graph, new LayeredDiagramLayoutService().layout(graph), null, evidence).withNodeReasons(Map.of("CI-1151", ROOT_DIAGNOSTIC));
        return new DecisionRationaleReport(base.title(), language, base.requirement(), DecisionRationaleReport.ReportStatus.DRAFT_INCOMPLETE,
                metadata, new DecisionRationaleReport.ExecutiveSummary(null, List.of(),
                        "Recorded decision remains incomplete.", "Authored rendering fixture; original evidence is retained."),
                chapters, base.leadingLeaves(), List.of(ROOT_DIAGNOSTIC, FAMILY_DIAGNOSTIC),
                base.productCoverageGaps(), base.discrepancies(), base.viewContext(), base.scoreDetails(), architecture, scope);
    }
}
