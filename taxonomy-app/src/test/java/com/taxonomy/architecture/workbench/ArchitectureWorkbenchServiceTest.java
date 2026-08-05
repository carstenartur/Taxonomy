package com.taxonomy.architecture.workbench;

import com.taxonomy.architecture.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchitectureWorkbenchServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long REQUIREMENT_ID = 7L;
    private static final String SNAPSHOT_ID = "snapshot-1";
    private static final WorkspaceContext CONTEXT =
            new WorkspaceContext("alice", "workspace-a", "feature-a");

    private final PortfolioAnalysisPersistenceService persistenceService =
            mock(PortfolioAnalysisPersistenceService.class);
    private final ProjectPortfolioService projectService =
            mock(ProjectPortfolioService.class);
    private final ArchitectureWorkbenchService service =
            new ArchitectureWorkbenchService(
                    persistenceService,
                    projectService,
                    new DiagramProjectionService(),
                    new LayeredDiagramLayoutService(),
                    new SvgDiagramRenderer(),
                    new ArchitecturePdfRenderer());

    @Test
    void replaysPersistedArchitectureForBrowserSvgAndPdfWithoutNewAnalysis() throws Exception {
        when(persistenceService.getSnapshot(PROJECT_ID, SNAPSHOT_ID, "alice", CONTEXT))
                .thenReturn(snapshotWithArchitecture());
        when(projectService.getProject(PROJECT_ID, "alice", CONTEXT))
                .thenReturn(project());
        when(projectService.getRequirement(PROJECT_ID, REQUIREMENT_ID, "alice", CONTEXT))
                .thenReturn(requirement());
        when(projectService.listRequirementVersions(
                PROJECT_ID, REQUIREMENT_ID, "alice", CONTEXT))
                .thenReturn(List.of(version()));

        Projection projection = service.load(PROJECT_ID, SNAPSHOT_ID, "alice", CONTEXT);
        String svg = service.renderSvg(PROJECT_ID, SNAPSHOT_ID, "alice", CONTEXT);
        byte[] pdf = service.renderPdf(PROJECT_ID, SNAPSHOT_ID, "alice", CONTEXT);

        assertThat(projection.scene().nodes()).hasSize(2);
        assertThat(projection.scene().edges()).hasSize(1);
        assertThat(projection.requirementText()).isEqualTo("Provide secure command information.");
        assertThat(svg).contains("CP-1").contains("CR-1").doesNotContain("window.print");
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF-");
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("P-001")
                    .contains("REQ-001")
                    .contains("snapshot-1")
                    .contains("no page screenshot");
        }

        verify(persistenceService).getSnapshot(PROJECT_ID, SNAPSHOT_ID, "alice", CONTEXT);
    }

    @Test
    void failsClearlyWhenSnapshotContainsNoArchitecture() {
        AnalysisResult analysis = new AnalysisResult(Map.of("CP-1", 90), List.of());
        analysis.setStatus("SUCCESS");
        when(persistenceService.getSnapshot(PROJECT_ID, SNAPSHOT_ID, "alice", CONTEXT))
                .thenReturn(new SnapshotDetail(
                        summary(),
                        analysis,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of()));

        assertThatThrownBy(() -> service.load(PROJECT_ID, SNAPSHOT_ID, "alice", CONTEXT))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("no persisted architecture view")
                .hasMessageContaining("Architecture View enabled");
    }

    private static SnapshotDetail snapshotWithArchitecture() {
        RequirementElementView capability = element(
                "CP-1", "Command capability", "CP", 0.92, true, 1);
        RequirementElementView service = element(
                "CR-1", "Secure exchange", "CR", 0.84, false, 3);
        RequirementRelationshipView relation = new RequirementRelationshipView();
        relation.setSourceCode("CP-1");
        relation.setTargetCode("CR-1");
        relation.setRelationType("REALIZED_BY");
        relation.setPropagatedRelevance(0.82);
        relation.setRelationCategory(RequirementRelationshipView.CATEGORY_IMPACT);

        RequirementArchitectureView view = new RequirementArchitectureView();
        view.setViewTitle("Secure command architecture");
        view.setIncludedElements(List.of(capability, service));
        view.setIncludedRelationships(List.of(relation));
        view.setNotes(List.of("One provisional relation is shown."));

        AnalysisResult analysis = new AnalysisResult(Map.of("CP-1", 92, "CR-1", 84), List.of());
        analysis.setStatus("SUCCESS");
        analysis.setArchitectureView(view);
        analysis.setWarnings(List.of("Review provisional relations."));

        return new SnapshotDetail(
                summary(),
                analysis,
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    private static RequirementElementView element(
            String code, String title, String root, double relevance, boolean anchor, int depth) {
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode(code);
        element.setTitle(title);
        element.setTaxonomySheet(root);
        element.setRelevance(relevance);
        element.setAnchor(anchor);
        element.setTaxonomyDepth(depth);
        element.setSelectedForImpact(true);
        return element;
    }

    private static SnapshotSummary summary() {
        return new SnapshotSummary(
                SNAPSHOT_ID,
                PROJECT_ID,
                REQUIREMENT_ID,
                "REQ-001",
                99L,
                3,
                "job-1",
                AnalysisStatus.SUCCESS,
                "GEMINI",
                "gemini-model",
                "taxonomy-fingerprint",
                "prompt-fingerprint",
                "workspace-a",
                "feature-a",
                "abcdef1234567890",
                Instant.parse("2026-08-05T12:00:00Z"),
                1234,
                1,
                null);
    }

    private static ProjectView project() {
        Instant now = Instant.parse("2026-08-05T12:00:00Z");
        return new ProjectView(
                PROJECT_ID,
                "P-001",
                "Secure command",
                "Project",
                ProjectStatus.ACTIVE,
                "alice",
                "workspace-a",
                null,
                null,
                BigDecimal.ZERO,
                "EUR",
                now,
                now,
                1,
                0,
                0);
    }

    private static RequirementView requirement() {
        Instant now = Instant.parse("2026-08-05T12:00:00Z");
        return new RequirementView(
                REQUIREMENT_ID,
                PROJECT_ID,
                "REQ-001",
                "Secure command information",
                RequirementStatus.APPROVED,
                90,
                Criticality.MISSION_CRITICAL,
                RequirementType.SECURITY,
                ReviewStatus.CONFIRMED,
                "alice",
                99L,
                SNAPSHOT_ID,
                now,
                now,
                version());
    }

    private static RequirementVersionView version() {
        return new RequirementVersionView(
                99L,
                3,
                "Provide secure command information.",
                "hash",
                "Reviewed",
                "alice",
                Instant.parse("2026-08-05T11:00:00Z"),
                null);
    }
}
