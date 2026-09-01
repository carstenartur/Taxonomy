package com.taxonomy.portfolio.workbench;

import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.ExportFormat;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.SnapshotArtifact;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchitectureWorkbenchControllerTest {

    private static final String GRAPH_SHA = "a".repeat(64);
    private static final String CONTENT_SHA = "b".repeat(64);

    private final ArchitectureWorkbenchService service =
            mock(ArchitectureWorkbenchService.class);
    private final ArchitectureSnapshotExportService exportService =
            mock(ArchitectureSnapshotExportService.class);
    private final ProjectPortfolioService projectService =
            mock(ProjectPortfolioService.class);
    private final WorkspaceResolver workspaceResolver =
            mock(WorkspaceResolver.class);
    private final ArchitectureWorkbenchController controller =
            new ArchitectureWorkbenchController(
                    service, exportService, projectService, workspaceResolver);

    @Test
    void pageCarriesExplicitSnapshotCoordinatesWithoutRenderingAnArbitraryPage() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.workbenchPage(42L, "snapshot-1", model);

        assertThat(view).isEqualTo("architecture-workbench");
        assertThat(model.get("projectId")).isEqualTo(42L);
        assertThat(model.get("snapshotId")).isEqualTo("snapshot-1");
    }

    @Test
    void currentRequirementRouteRedirectsToItsPersistedSnapshot() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        when(projectService.getRequirement(42L, 7L, "alice", context))
                .thenReturn(requirement("snapshot-1"));

        String target = controller.currentRequirementArchitecture(42L, 7L);

        assertThat(target)
                .isEqualTo("redirect:/architecture/workbench?projectId=42&snapshotId=snapshot-1");
    }

    @Test
    void currentRequirementRouteRefusesMissingArchitectureSnapshot() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        when(projectService.getRequirement(42L, 7L, "alice", context))
                .thenReturn(requirement(null));

        assertThatThrownBy(() -> controller.currentRequirementArchitecture(42L, 7L))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("no current architecture snapshot");
    }

    @Test
    void legacySvgAndPdfUrlsUseTheUnifiedSnapshotExportContract() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        when(exportService.export(42L, "snapshot-1", "alice", context, "svg"))
                .thenReturn(artifact(ExportFormat.SVG, "<svg/>".getBytes(StandardCharsets.UTF_8)));
        when(exportService.export(42L, "snapshot-1", "alice", context, "pdf"))
                .thenReturn(artifact(
                        ExportFormat.PDF,
                        "%PDF-test".getBytes(StandardCharsets.ISO_8859_1)));

        var svg = controller.svg(42L, "snapshot-1");
        var pdf = controller.pdf(42L, "snapshot-1");

        assertThat(svg.getHeaders().getContentType().toString()).isEqualTo("image/svg+xml");
        assertThat(new String(svg.getBody(), StandardCharsets.UTF_8)).contains("<svg");
        assertThat(pdf.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getBody(), StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(pdf.getHeaders().getFirst("Content-Disposition")).contains("architecture.pdf");
        assertProvenanceHeaders(svg);
        assertProvenanceHeaders(pdf);
        assertThat(svg.getHeaders().getFirst(ArchitectureWorkbenchController.ROLE_HEADER))
                .isEqualTo("stable-human-view");
        assertThat(pdf.getHeaders().getFirst(ArchitectureWorkbenchController.ROLE_HEADER))
                .isEqualTo("stable-human-view");
        verify(exportService).export(42L, "snapshot-1", "alice", context, "svg");
        verify(exportService).export(42L, "snapshot-1", "alice", context, "pdf");
    }

    @Test
    void semanticExportCarriesContentTypeFilenameEtagAndExactProvenance() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        when(exportService.export(42L, "snapshot-1", "alice", context, "archimate"))
                .thenReturn(artifact(
                        ExportFormat.ARCHIMATE,
                        "<model/>".getBytes(StandardCharsets.UTF_8)));

        var response = controller.export(42L, "snapshot-1", "archimate");

        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/xml");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("architecture.xml");
        assertThat(response.getHeaders().getETag())
                .isEqualTo("\"sha256-" + CONTENT_SHA + "\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(response.getHeaders().getContentLength())
                .isEqualTo("<model/>".getBytes(StandardCharsets.UTF_8).length);
        assertProvenanceHeaders(response);
        assertThat(response.getHeaders().getFirst(
                ArchitectureWorkbenchController.PROFILE_HEADER))
                .isEqualTo(ExportFormat.ARCHIMATE.profileId());
        assertThat(response.getHeaders().getFirst(
                ArchitectureWorkbenchController.ROLE_HEADER))
                .isEqualTo(ExportFormat.ARCHIMATE.handoffRole());
        verify(exportService).export(42L, "snapshot-1", "alice", context, "archimate");
    }

    @Test
    void projectionUsesResolvedWorkspaceScope() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");
        Projection projection = mock(Projection.class);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        when(service.load(42L, "snapshot-1", "alice", context)).thenReturn(projection);

        assertThat(controller.projection(42L, "snapshot-1")).isSameAs(projection);
        verify(service).load(42L, "snapshot-1", "alice", context);
    }

    private static void assertProvenanceHeaders(
            org.springframework.http.ResponseEntity<byte[]> response) {
        assertThat(response.getHeaders().getFirst(
                ArchitectureWorkbenchController.SNAPSHOT_HEADER))
                .isEqualTo("snapshot-1");
        assertThat(response.getHeaders().getFirst(
                ArchitectureWorkbenchController.COMMIT_HEADER))
                .isEqualTo("0123456789abcdef");
        assertThat(response.getHeaders().getFirst(
                ArchitectureWorkbenchController.GRAPH_SHA_HEADER))
                .isEqualTo(GRAPH_SHA);
        assertThat(response.getHeaders().getFirst(
                ArchitectureWorkbenchController.CONTENT_SHA_HEADER))
                .isEqualTo(CONTENT_SHA);
    }

    private static SnapshotArtifact artifact(ExportFormat format, byte[] content) {
        return new SnapshotArtifact(
                format,
                content,
                "snapshot-1",
                "0123456789abcdef",
                GRAPH_SHA,
                CONTENT_SHA);
    }

    private static RequirementView requirement(String snapshotId) {
        Instant now = Instant.parse("2026-08-05T12:00:00Z");
        return new RequirementView(
                7L, 42L, "REQ-001", "Secure command information",
                RequirementStatus.APPROVED, 90, Criticality.MISSION_CRITICAL,
                RequirementType.SECURITY, ReviewStatus.CONFIRMED, "alice",
                99L, snapshotId, now, now, null);
    }
}
