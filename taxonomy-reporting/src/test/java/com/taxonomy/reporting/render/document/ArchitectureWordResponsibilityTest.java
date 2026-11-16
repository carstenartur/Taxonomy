package com.taxonomy.reporting.render.document;

import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureWordResponsibilityTest {
    private static final Assignment ASSIGNMENT = new Assignment("unit-42", "Küste & Betrieb", "CP-1000");

    @Test
    void currentArchitectureWordReportShowsCapturedResponsibilityAsSeparateEvidence() throws Exception {
        var report = new ArchitectureReport();
        report.setBusinessText("Original requirement");
        report.setArchitectureView(view(ASSIGNMENT));

        assertResponsibility(new ArchitectureReportDocxRenderer().render(report, "de"),
                "Organisatorische Zuständigkeiten", "Quelle der Zuweisung");
    }

    @Test
    void frozenArchitectureWordReportShowsCapturedResponsibilityAsSeparateEvidence() throws Exception {
        var report = frozen(ASSIGNMENT);
        assertResponsibility(new ArchitectureReportDocxRenderer().render(report),
                "Organizational responsibilities", "Assignment source");
    }

    @Test
    void legacyFrozenReportHasNoEvidenceTableForAbsentResponsibilities() throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(new ArchitectureReportDocxRenderer().render(frozen(null))));
             var extractor = new XWPFWordExtractor(document)) {
            assertThat(extractor.getText()).doesNotContain("Organizational responsibilities", "Assignment source");
        }
    }

    private static void assertResponsibility(byte[] bytes, String heading, String sourceLabel) throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes));
             var extractor = new XWPFWordExtractor(document)) {
            assertThat(extractor.getText()).contains(heading, sourceLabel, "Capability A");
            assertThat(document.getTables()).anySatisfy(table -> {
                assertThat(table.getRow(0).getTableCells()).hasSize(4);
                assertThat(table.getRow(0).getCell(3).getText()).isEqualTo(sourceLabel);
                var row = table.getRow(1);
                assertThat(row.getCell(0).getText()).isEqualTo("CP-1023");
                assertThat(row.getCell(1).getText()).endsWith(ASSIGNMENT.name());
                assertThat(row.getCell(1).getParagraphs().getFirst().getRuns()).anySatisfy(run ->
                        assertThat(run.getColor()).isEqualTo(ASSIGNMENT.color().substring(1)));
                assertThat(row.getCell(2).getText()).isEqualTo(ASSIGNMENT.unitId());
                assertThat(row.getCell(3).getText()).isEqualTo(ASSIGNMENT.sourceId());
            });
        }
    }

    private static ArchitectureReportDocument frozen(Assignment assignment) {
        var graph = new DiagramProjectionService().projectRaw(view(assignment), "Captured architecture");
        var evidence = new ArchitectureReportDocument.SnapshotEvidence(1L, 2L, 3L, 1,
                "snapshot", "repository", "workspace", "branch", "commit", "provider", "model",
                "taxonomy-digest", ArchitectureReportDocument.graphSha256(graph));
        return ArchitectureReportDocuments.from("Report", "en", "Original requirement", "Scope",
                "Recommendation", List.of(), graph, new LayeredDiagramLayoutService().layout(graph),
                new DecisionTreeOverview(List.of(), List.of()), evidence);
    }

    private static RequirementArchitectureView view(Assignment assignment) {
        var element = new RequirementElementView();
        element.setNodeCode("CP-1023");
        element.setTitle("Capability A");
        element.setTaxonomySheet("CP");
        element.setRelevance(.8);
        element.setResponsibility(assignment);
        var view = new RequirementArchitectureView();
        view.setIncludedElements(List.of(element));
        return view;
    }
}
