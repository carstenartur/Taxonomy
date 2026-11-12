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
    void legacyPageRedirectKeepsPrivateSourceAndPresentationCoordinates() throws Exception {
        var coordinates = new org.springframework.util.LinkedMultiValueMap<String,String>();
        coordinates.add("workspaceId", "private +/& Ü"); coordinates.add("repositoryId", "repo"); coordinates.add("branch", "feature/a+b & 東京");
        coordinates.add("projectId", "42"); coordinates.add("snapshotId", "snapshot +/&");
        for (String pin : java.util.List.of("sourceRepositoryId","sourceWorkspaceScopeKey","sourceWorkspaceId","sourceBranch","sourceProjectId","sourceRequirementId","sourceRequirementVersionId","sourceSnapshotId","element","revision","commit","viewId","layout","layoutRevision","adoption","lang"))
            coordinates.add(pin,"value +/& Ü");
        coordinates.add("architectureSource","wrong");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver()).build();
        String target = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/deployed/architecture/workbench")
                        .contextPath("/deployed").params(coordinates))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        var uri = java.net.URI.create(target);
        assertThat(uri.getHost()).isNull(); assertThat(uri.getPath()).isEqualTo("/deployed/"); assertThat(uri.getFragment()).isEqualTo("architecture");
        coordinates.set("architectureSource","snapshot"); assertThat(decodedQuery(uri)).isEqualTo(coordinates);
        org.mockito.Mockito.verifyNoInteractions(service,projectService,workspaceResolver,snapshotExportService);
    }

    private static org.springframework.util.MultiValueMap<String,String> decodedQuery(java.net.URI uri) {
        var decoded = new org.springframework.util.LinkedMultiValueMap<String,String>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=",2);
            decoded.add(java.net.URLDecoder.decode(parts[0],StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(parts.length == 2 ? parts[1] : "",StandardCharsets.UTF_8));
        }
        return decoded;
    }

    @Test
    void pageCarriesExplicitSnapshotCoordinatesWithoutRenderingAnArbitraryPage() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.workbenchPage(42L, "snapshot-1", model);

        assertThat(view).isEqualTo("redirect:/?architectureSource=snapshot&projectId=42&snapshotId=snapshot-1#architecture");
        assertThat(model).isEmpty();
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

        var uri = java.net.URI.create(target.substring("redirect:".length()));
        assertThat(uri.getPath()).isEqualTo("/"); assertThat(uri.getFragment()).isEqualTo("architecture");
        assertThat(decodedQuery(uri)).containsEntry("architectureSource",java.util.List.of("snapshot"))
                .containsEntry("projectId",java.util.List.of("42")).containsEntry("requirementId",java.util.List.of("7"))
                .containsEntry("snapshotId",java.util.List.of("snapshot-1"))
                .containsEntry("workspaceId",java.util.List.of("workspace-a"))
                .containsEntry("repositoryId",java.util.List.of("legacy-primary"))
                .containsEntry("branch",java.util.List.of("feature-a"));
    }

    @Test
    void currentRequirementRedirectPinsResolvedAuthorityAndClearsUnknownVersionWhileRetainingPresentation() throws Exception {
        for (String workspace : java.util.Arrays.asList("private +/& Ü",null)) {
            WorkspaceContext context = new WorkspaceContext("alice",workspace,"feature/a+b & 東京","repo +/& Ü");
            when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice"); when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
            when(projectService.getRequirement(42L,7L,"alice",context)).thenReturn(requirement("snapshot +/& Ü"));
            var coordinates = new org.springframework.util.LinkedMultiValueMap<String,String>();
            for (String key : java.util.List.of("projectId","requirementId","snapshotId","workspaceId","repositoryId","branch","workspaceScopeKey","sourceRepositoryId","sourceWorkspaceId","sourceWorkspaceScopeKey","sourceBranch","sourceProjectId","sourceRequirementId","sourceSnapshotId","requirementVersionId","sourceRequirementVersionId")) coordinates.add(key,"stale");
            coordinates.add("viewId","view +/&"); coordinates.add("layout","reduce-crossings"); coordinates.add("layoutRevision","12"); coordinates.add("adoption","preview"); coordinates.add("lang","de");
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                    .setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver()).build();
            String target = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/deployed/projects/42/requirements/7/architecture")
                            .contextPath("/deployed").params(coordinates))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl();
            var uri = java.net.URI.create(target); assertThat(uri.getPath()).isEqualTo("/deployed/"); assertThat(uri.getFragment()).isEqualTo("architecture");
            var actual = decodedQuery(uri);
            String workspaceId = workspace == null ? "" : workspace, scopeKey = workspace == null ? "__shared__" : workspace;
            coordinates.remove("requirementVersionId"); coordinates.remove("sourceRequirementVersionId"); coordinates.set("architectureSource","snapshot");
            coordinates.set("projectId","42"); coordinates.set("sourceProjectId","42"); coordinates.set("requirementId","7"); coordinates.set("sourceRequirementId","7");
            coordinates.set("snapshotId","snapshot +/& Ü"); coordinates.set("sourceSnapshotId","snapshot +/& Ü");
            coordinates.set("workspaceId",workspaceId); coordinates.set("sourceWorkspaceId",workspaceId); coordinates.set("workspaceScopeKey",scopeKey); coordinates.set("sourceWorkspaceScopeKey",scopeKey);
            coordinates.set("repositoryId",context.repositoryId()); coordinates.set("sourceRepositoryId",context.repositoryId()); coordinates.set("branch",context.currentBranch()); coordinates.set("sourceBranch",context.currentBranch());
            assertThat(actual).isEqualTo(coordinates);
        }
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
        var projection = mock(Projection.class);
        when(service.load(42L, "snapshot-1", "alice", context)).thenReturn(projection);
        when(projection.exportLabel()).thenReturn(com.taxonomy.portfolio.service.SnapshotExportLabel.STANDARD);
        when(service.renderSvg(projection))
                .thenReturn(
                        "<svg><title>Architecture</title></svg>");
        when(service.renderPdf(projection))
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
        verify(service).renderSvg(projection);
        verify(service).renderPdf(projection);
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
                        "taxonomy-archimate-3.1-v2",
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
                        "visio-2012-opc-supported-subset-v3",
                        "vsdx-hash",
                        "PK-test".getBytes(
                                StandardCharsets.ISO_8859_1)));
        when(snapshotExportService.exportVisioBundle(42L, "snapshot-1", "alice", context))
                .thenReturn(artifact(Format.VISIO_BUNDLE, "application/zip",
                        "architecture-snapshot-1-abcdef123456.visio.zip",
                        "visio-2012-opc-supported-subset-v3", "bundle-hash",
                        "PK-bundle".getBytes(StandardCharsets.ISO_8859_1)));

        var archiMate =
                controller.archiMate(42L, "snapshot-1");
        var visio =
                controller.visio(42L, "snapshot-1");
        var bundle = controller.visioBundle(42L, "snapshot-1");
        assertThat(bundle.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(bundle.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(bundle.getHeaders().getFirst("X-Taxonomy-Artifact-Sha256")).isEqualTo("bundle-hash");
        assertThat(bundle.getHeaders().getFirst("X-Taxonomy-Canonical-Graph-Sha256")).isEqualTo("graph-hash");
        assertThat(bundle.getHeaders().getFirst("X-Taxonomy-Snapshot-Id")).isEqualTo("snapshot-1");
        assertThat(bundle.getHeaders().getFirst("Content-Disposition")).contains(".visio.zip");
        verify(snapshotExportService).exportVisioBundle(42L, "snapshot-1", "alice", context);

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
