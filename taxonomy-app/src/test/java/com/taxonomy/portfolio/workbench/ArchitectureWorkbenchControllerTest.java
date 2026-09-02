package com.taxonomy.portfolio.workbench;

import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.Artifact;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.Format;
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

    private final ArchitectureWorkbenchService service =
            mock(ArchitectureWorkbenchService.class);
    private final ArchitectureSnapshotExportService snapshotExportService =
            mock(ArchitectureSnapshotExportService.class);
    private final ProjectPortfolioService projectService =
            mock(ProjectPortfolioService.class);
    private final WorkspaceResolver workspaceResolver =
            mock(WorkspaceResolver.class);
    private final ArchitectureWorkbenchController controller =
            new ArchitectureWorkbenchController(
                    service,
                    snapshotExportService,
                    projectService,
                    workspaceResolver);

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
        WorkspaceContext context =
                new WorkspaceContext("alice", "workspace-a", "feature-a");
        when(workspaceResolver.resolveCurrentUsername())
                .thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext())
                .thenReturn(context);
        when(projectService.getRequirement(42L, 7L, "alice", context))
                .thenReturn(requirement("snapshot-1"));

        String target =
                controller.currentRequirementArchitecture(42L, 7L);

        assertThat(target)
                .isEqualTo(
                        "redirect:/architecture/workbench"
                                + "?projectId=42&snapshotId=snapshot-1");
    }

    @Test
    void currentRequirementRouteRefusesMissingArchitectureSnapshot() {
        WorkspaceContext context =
                new WorkspaceContext("alice", "workspace-a", "feature-a");
        when(workspaceResolver.resolveCurrentUsername())
                .thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext())
                .thenReturn(context);
        when(projectService.getRequirement(42L, 7L, "alice", context))
                .thenReturn(requirement(null));

        assertThatThrownBy(
                () -> controller.currentRequirementArchitecture(42L, 7L))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining(
                        "no current architecture snapshot");
    }

    @Test
    void svgAndPdfUseTheScopedServerRenderer() {
        WorkspaceContext context =
                new WorkspaceContext("alice", "workspace-a", "feature-a");
        when(workspaceResolver.resolveCurrentUsername())
                .thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext())
                .thenReturn(context);
        when(service.renderSvg(42L, "snapshot-1", "alice", context))
                .thenReturn(
                        "<svg><title>Architecture</title></svg>");
        when(service.renderPdf(42L, "snapshot-1", "alice", context))
                .thenReturn(
                        "%PDF-test".getBytes(
                                StandardCharsets.ISO_8859_1));

        var svg = controller.svg(42L, "snapshot-1");
        var pdf = controller.pdf(42L, "snapshot-1");

        assertThat(svg.getHeaders().getContentType().toString())
                .isEqualTo("image/svg+xml");
        assertThat(svg.getBody()).contains("<svg");
        assertThat(pdf.getHeaders().getContentType().toString())
                .isEqualTo("application/pdf");
        assertThat(new String(
                pdf.getBody(),
                StandardCharsets.ISO_8859_1))
                .startsWith("%PDF-");
        assertThat(pdf.getHeaders().getFirst(
                "Content-Disposition"))
                .contains("architecture.pdf");
        verify(service)
                .renderSvg(
                        42L,
                        "snapshot-1",
                        "alice",
                        context);
        verify(service)
                .renderPdf(
                        42L,
                        "snapshot-1",
                        "alice",
                        context);
    }

    @Test
    void snapshotFormatExportsExposeExactAuthorityAndFingerprints() {
        WorkspaceContext context =
                new WorkspaceContext("alice", "workspace-a", "feature-a");
        when(workspaceResolver.resolveCurrentUsername())
                .thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext())
                .thenReturn(context);
        when(snapshotExportService.exportArchiMate(
                42L,
                "snapshot-1",
                "alice",
                context))
                .thenReturn(artifact(
                        Format.ARCHIMATE_XML,
                        "application/xml",
                        "architecture-snapshot-1-abcdef123456"
                                + ".archimate.xml",
                        "archimate-exchange-3.1-supported-subset-v1",
                        "xml-hash",
                        "<model/>".getBytes(StandardCharsets.UTF_8)));
        when(snapshotExportService.exportVisio(
                42L,
                "snapshot-1",
                "alice",
                context))
                .thenReturn(artifact(
                        Format.VISIO_VSDX,
                        "application/vnd.ms-visio.drawing",
                        "architecture-snapshot-1-abcdef123456.vsdx",
                        "visio-2012-opc-supported-subset-v1",
                        "vsdx-hash",
                        "PK-test".getBytes(
                                StandardCharsets.ISO_8859_1)));

        var archiMate =
                controller.archiMate(42L, "snapshot-1");
        var visio =
                controller.visio(42L, "snapshot-1");

        assertThat(archiMate.getHeaders()
                .getContentType().toString())
                .isEqualTo("application/xml");
        assertThat(visio.getHeaders()
                .getContentType().toString())
                .isEqualTo("application/vnd.ms-visio.drawing");
        assertThat(archiMate.getHeaders().getCacheControl())
                .isEqualTo("no-store");
        assertThat(visio.getHeaders().getCacheControl())
                .isEqualTo("no-store");
        assertThat(archiMate.getHeaders().getFirst(
                "X-Taxonomy-Snapshot-Id"))
                .isEqualTo("snapshot-1");
        assertThat(archiMate.getHeaders().getFirst(
                "X-Taxonomy-Workspace-Id"))
                .isEqualTo("workspace-a");
        assertThat(archiMate.getHeaders().getFirst(
                "X-Taxonomy-Branch"))
                .isEqualTo("feature-a");
        assertThat(archiMate.getHeaders().getFirst(
                "X-Taxonomy-Commit-Sha"))
                .isEqualTo("abcdef1234567890");
        assertThat(archiMate.getHeaders().getFirst(
                "X-Taxonomy-Canonical-Graph-Sha256"))
                .isEqualTo("graph-hash");
        assertThat(visio.getHeaders().getFirst(
                "X-Taxonomy-Canonical-Graph-Sha256"))
                .isEqualTo("graph-hash");
        assertThat(archiMate.getHeaders().getFirst(
                "X-Taxonomy-Artifact-Sha256"))
                .isEqualTo("xml-hash");
        assertThat(visio.getHeaders().getFirst(
                "X-Taxonomy-Artifact-Sha256"))
                .isEqualTo("vsdx-hash");
        assertThat(archiMate.getHeaders().getFirst(
                "Content-Disposition"))
                .contains("archimate.xml");
        assertThat(visio.getHeaders().getFirst(
                "Content-Disposition"))
                .contains(".vsdx");
        assertThat(new String(
                archiMate.getBody(),
                StandardCharsets.UTF_8))
                .isEqualTo("<model/>");

        verify(snapshotExportService)
                .exportArchiMate(
                        42L,
                        "snapshot-1",
                        "alice",
                        context);
        verify(snapshotExportService)
                .exportVisio(
                        42L,
                        "snapshot-1",
                        "alice",
                        context);
    }

    @Test
    void projectionUsesResolvedWorkspaceScope() {
        WorkspaceContext context =
                new WorkspaceContext("alice", "workspace-a", "feature-a");
        Projection projection = mock(Projection.class);
        when(workspaceResolver.resolveCurrentUsername())
                .thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext())
                .thenReturn(context);
        when(service.load(42L, "snapshot-1", "alice", context))
                .thenReturn(projection);

        assertThat(controller.projection(42L, "snapshot-1"))
                .isSameAs(projection);
        verify(service)
                .load(
                        42L,
                        "snapshot-1",
                        "alice",
                        context);
    }

    private static Artifact artifact(
            Format format,
            String mediaType,
            String fileName,
            String profile,
            String artifactHash,
            byte[] content) {
        return new Artifact(
                format,
                fileName,
                mediaType,
                profile,
                42L,
                7L,
                "snapshot-1",
                AnalysisStatus.SUCCESS,
                "workspace-a",
                "feature-a",
                "abcdef1234567890",
                "GEMINI",
                "gemini-model",
                "graph-hash",
                artifactHash,
                content);
    }

    private static RequirementView requirement(String snapshotId) {
        Instant now = Instant.parse("2026-08-05T12:00:00Z");
        return new RequirementView(
                7L,
                42L,
                "REQ-001",
                "Secure command information",
                RequirementStatus.APPROVED,
                90,
                Criticality.MISSION_CRITICAL,
                RequirementType.SECURITY,
                ReviewStatus.CONFIRMED,
                "alice",
                99L,
                snapshotId,
                now,
                now,
                null);
    }
}
