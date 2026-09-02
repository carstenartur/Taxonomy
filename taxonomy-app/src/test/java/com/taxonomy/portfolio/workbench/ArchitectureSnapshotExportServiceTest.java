package com.taxonomy.portfolio.workbench;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.service.CanonicalDiagramExportService;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.Artifact;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.Format;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArchitectureSnapshotExportServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final String SNAPSHOT_ID = "snapshot-1";
    private static final WorkspaceContext CONTEXT =
            new WorkspaceContext("alice", "workspace-a", "feature-a");

    private final ArchitectureWorkbenchService workbenchService =
            mock(ArchitectureWorkbenchService.class);
    private final CanonicalDiagramExportService diagramExportService =
            mock(CanonicalDiagramExportService.class);
    private final ArchitectureSnapshotExportService service =
            new ArchitectureSnapshotExportService(
                    workbenchService,
                    diagramExportService);

    @Test
    void exportsBothFormatsFromTheSameAuthorizedCanonicalGraph() {
        Projection projection = projection(
                PROJECT_ID,
                SNAPSHOT_ID,
                "abcdef1234567890");
        byte[] archiMate = "<model identifier=\"id-model-1\"/>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] visio = "PK-test-vsdx".getBytes(
                StandardCharsets.ISO_8859_1);
        when(workbenchService.load(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .thenReturn(projection);
        when(diagramExportService.exportAsArchiMate(projection.diagram()))
                .thenReturn(archiMate);
        when(diagramExportService.exportAsVisio(projection.diagram()))
                .thenReturn(visio);

        Artifact archiMateArtifact = service.exportArchiMate(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT);
        Artifact visioArtifact = service.exportVisio(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT);

        assertThat(archiMateArtifact.format())
                .isEqualTo(Format.ARCHIMATE_XML);
        assertThat(visioArtifact.format())
                .isEqualTo(Format.VISIO_VSDX);
        assertThat(archiMateArtifact.canonicalGraphSha256())
                .hasSize(64)
                .isEqualTo(visioArtifact.canonicalGraphSha256());
        assertThat(archiMateArtifact.artifactSha256())
                .hasSize(64)
                .isNotEqualTo(visioArtifact.artifactSha256());
        assertThat(archiMateArtifact.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(archiMateArtifact.workspaceId()).isEqualTo("workspace-a");
        assertThat(archiMateArtifact.branchName()).isEqualTo("feature-a");
        assertThat(archiMateArtifact.commitSha())
                .isEqualTo("abcdef1234567890");
        assertThat(archiMateArtifact.fileName())
                .isEqualTo(
                        "architecture-snapshot-1-abcdef123456.archimate.xml");
        assertThat(visioArtifact.fileName())
                .isEqualTo("architecture-snapshot-1-abcdef123456.vsdx");
        assertThat(archiMateArtifact.exporterProfile())
                .isEqualTo(
                        "archimate-exchange-3.1-supported-subset-v1");
        assertThat(visioArtifact.exporterProfile())
                .isEqualTo("visio-2012-opc-supported-subset-v1");

        verify(workbenchService, times(2)).load(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT);
        verify(diagramExportService)
                .exportAsArchiMate(projection.diagram());
        verify(diagramExportService)
                .exportAsVisio(projection.diagram());
    }

    @Test
    void authorizationFailureStopsBeforeAnySerializerIsInvoked() {
        when(workbenchService.load(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .thenThrow(PortfolioException.notFound("Snapshot not found"));

        assertThatThrownBy(() -> service.exportArchiMate(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Snapshot not found");

        verifyNoInteractions(diagramExportService);
    }

    @Test
    void refusesSnapshotSubstitutionAndMissingCommitAuthority() {
        when(workbenchService.load(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .thenReturn(projection(
                        PROJECT_ID,
                        "different-snapshot",
                        "abcdef1234567890"));

        assertThatThrownBy(() -> service.exportArchiMate(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("does not match");

        when(workbenchService.load(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .thenReturn(projection(PROJECT_ID, SNAPSHOT_ID, " "));

        assertThatThrownBy(() -> service.exportVisio(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("commitSha authority");

        verifyNoInteractions(diagramExportService);
    }

    @Test
    void artifactContentIsDefensivelyCopied() {
        Projection projection = projection(
                PROJECT_ID,
                SNAPSHOT_ID,
                "abcdef1234567890");
        byte[] serialized = "content".getBytes(StandardCharsets.UTF_8);
        when(workbenchService.load(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .thenReturn(projection);
        when(diagramExportService.exportAsArchiMate(projection.diagram()))
                .thenReturn(serialized);

        Artifact artifact = service.exportArchiMate(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT);
        serialized[0] = 'X';
        byte[] firstRead = artifact.content();
        firstRead[0] = 'Y';

        assertThat(new String(
                artifact.content(),
                StandardCharsets.UTF_8))
                .isEqualTo("content");
    }

    @Test
    void serviceDependencyBoundaryContainsNoAnalysisOrPreferenceService() {
        List<String> fieldTypes = Arrays.stream(
                        ArchitectureSnapshotExportService.class
                                .getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();
        List<String> serializerFieldTypes = Arrays.stream(
                        CanonicalDiagramExportService.class
                                .getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();

        assertThat(fieldTypes)
                .noneMatch(name -> name.contains("LlmService"))
                .noneMatch(name -> name.contains("PreferencesService"))
                .noneMatch(name -> name.contains(
                        "RequirementArchitectureViewService"));
        assertThat(serializerFieldTypes)
                .noneMatch(name -> name.contains("LlmService"))
                .noneMatch(name -> name.contains("PreferencesService"))
                .noneMatch(name -> name.contains(
                        "RequirementArchitectureViewService"))
                .noneMatch(name -> name.contains(
                        "DiagramProjectionService"));
    }

    private static Projection projection(
            Long projectId,
            String snapshotId,
            String commitSha) {
        DiagramModel diagram = new DiagramModel(
                "Snapshot architecture",
                List.of(
                        new DiagramNode(
                                "CP-1",
                                "Command capability",
                                "Capabilities",
                                0.92,
                                true,
                                1),
                        new DiagramNode(
                                "CR-1",
                                "Secure exchange",
                                "Core Services",
                                0.84,
                                false,
                                3)),
                List.of(new DiagramEdge(
                        "rel-1",
                        "CP-1",
                        "CR-1",
                        "REALIZED_BY",
                        0.82,
                        "impact")),
                new DiagramLayout("LR", true));
        return new Projection(
                projectId,
                "P-001",
                "Secure command",
                7L,
                "REQ-001",
                "Secure command information",
                "Provide secure command information.",
                snapshotId,
                AnalysisStatus.SUCCESS,
                Instant.parse("2026-08-05T12:00:00Z"),
                "GEMINI",
                "gemini-model",
                "workspace-a",
                "feature-a",
                commitSha,
                diagram,
                null,
                Map.of(),
                Map.of(),
                List.of());
    }
}
