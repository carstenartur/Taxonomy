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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void canonicalGraphFingerprintIgnoresTitleLayoutAndCollectionOrder() {
        DiagramModel firstDiagram = semanticDiagram(
                "First display title",
                "LR",
                true,
                false,
                "Secure exchange");
        DiagramModel reorderedDiagram = semanticDiagram(
                "Different display title",
                "TB",
                false,
                true,
                "Secure exchange");
        Projection first = projection(
                PROJECT_ID,
                "snapshot-a",
                "abcdef1234567890",
                firstDiagram);
        Projection reordered = projection(
                PROJECT_ID,
                "snapshot-b",
                "abcdef1234567890",
                reorderedDiagram);
        when(workbenchService.load(
                PROJECT_ID,
                "snapshot-a",
                "alice",
                CONTEXT))
                .thenReturn(first);
        when(workbenchService.load(
                PROJECT_ID,
                "snapshot-b",
                "alice",
                CONTEXT))
                .thenReturn(reordered);
        when(diagramExportService.exportAsArchiMate(firstDiagram))
                .thenReturn("first-artifact".getBytes(StandardCharsets.UTF_8));
        when(diagramExportService.exportAsVisio(reorderedDiagram))
                .thenReturn("second-artifact".getBytes(StandardCharsets.UTF_8));

        Artifact firstArtifact = service.exportArchiMate(
                PROJECT_ID,
                "snapshot-a",
                "alice",
                CONTEXT);
        Artifact reorderedArtifact = service.exportVisio(
                PROJECT_ID,
                "snapshot-b",
                "alice",
                CONTEXT);

        assertThat(firstArtifact.canonicalGraphSha256())
                .isEqualTo(reorderedArtifact.canonicalGraphSha256());
        assertThat(firstArtifact.artifactSha256())
                .isNotEqualTo(reorderedArtifact.artifactSha256());
    }

    @Test
    void canonicalGraphFingerprintChangesWithSemanticNodeContent() {
        DiagramModel firstDiagram = semanticDiagram(
                "Display title",
                "LR",
                true,
                false,
                "Secure exchange");
        DiagramModel renamedDiagram = semanticDiagram(
                "Display title",
                "LR",
                true,
                false,
                "Renamed secure exchange");
        Projection first = projection(
                PROJECT_ID,
                "snapshot-a",
                "abcdef1234567890",
                firstDiagram);
        Projection renamed = projection(
                PROJECT_ID,
                "snapshot-b",
                "abcdef1234567890",
                renamedDiagram);
        when(workbenchService.load(
                PROJECT_ID,
                "snapshot-a",
                "alice",
                CONTEXT))
                .thenReturn(first);
        when(workbenchService.load(
                PROJECT_ID,
                "snapshot-b",
                "alice",
                CONTEXT))
                .thenReturn(renamed);
        when(diagramExportService.exportAsArchiMate(firstDiagram))
                .thenReturn("same-bytes".getBytes(StandardCharsets.UTF_8));
        when(diagramExportService.exportAsArchiMate(renamedDiagram))
                .thenReturn("same-bytes".getBytes(StandardCharsets.UTF_8));

        Artifact firstArtifact = service.exportArchiMate(
                PROJECT_ID,
                "snapshot-a",
                "alice",
                CONTEXT);
        Artifact renamedArtifact = service.exportArchiMate(
                PROJECT_ID,
                "snapshot-b",
                "alice",
                CONTEXT);

        assertThat(firstArtifact.canonicalGraphSha256())
                .isNotEqualTo(renamedArtifact.canonicalGraphSha256());
        assertThat(firstArtifact.artifactSha256())
                .isEqualTo(renamedArtifact.artifactSha256());
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
    void emptySerializerContentFailsAsTypedConflict() {
        Projection projection = projection(
                PROJECT_ID,
                SNAPSHOT_ID,
                "abcdef1234567890");
        when(workbenchService.load(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .thenReturn(projection);
        when(diagramExportService.exportAsArchiMate(projection.diagram()))
                .thenReturn(new byte[0]);

        PortfolioException exception = assertThrows(
                PortfolioException.class,
                () -> service.exportArchiMate(
                        PROJECT_ID,
                        SNAPSHOT_ID,
                        "alice",
                        CONTEXT));

        assertThat(exception.getKind())
                .isEqualTo(PortfolioException.Kind.CONFLICT);
        assertThat(exception)
                .hasMessageContaining(
                        "canonical diagram exporter returned no content");
    }

    @Test
    void invalidCanonicalGraphFailsAsTypedNonReflectiveConflict() {
        Projection projection = projection(
                PROJECT_ID,
                SNAPSHOT_ID,
                "abcdef1234567890");
        when(workbenchService.load(
                PROJECT_ID,
                SNAPSHOT_ID,
                "alice",
                CONTEXT))
                .thenReturn(projection);
        when(diagramExportService.exportAsArchiMate(projection.diagram()))
                .thenThrow(new IllegalArgumentException(
                        "serializer-internal detail must not be reflected"));

        PortfolioException exception = assertThrows(
                PortfolioException.class,
                () -> service.exportArchiMate(
                        PROJECT_ID,
                        SNAPSHOT_ID,
                        "alice",
                        CONTEXT));

        assertThat(exception.getKind())
                .isEqualTo(PortfolioException.Kind.CONFLICT);
        assertThat(exception.getMessage())
                .isEqualTo(
                        "The selected snapshot violates the canonical export contract")
                .doesNotContain("serializer-internal");
        assertThat(exception.getCause())
                .isInstanceOf(IllegalArgumentException.class);
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
        return projection(
                projectId,
                snapshotId,
                commitSha,
                semanticDiagram(
                        "Snapshot architecture",
                        "LR",
                        true,
                        false,
                        "Secure exchange"));
    }

    private static Projection projection(
            Long projectId,
            String snapshotId,
            String commitSha,
            DiagramModel diagram) {
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

    private static DiagramModel semanticDiagram(
            String title,
            String direction,
            boolean groupByLayer,
            boolean reverseOrder,
            String serviceLabel) {
        DiagramNode capability = new DiagramNode(
                "CP-1",
                "Command capability",
                "Capabilities",
                0.92,
                true,
                1);
        DiagramNode service = new DiagramNode(
                "CR-1",
                serviceLabel,
                "Core Services",
                0.84,
                false,
                3);
        DiagramNode information = new DiagramNode(
                "IP-1",
                "Command information",
                "Information Products",
                0.73,
                false,
                4);
        DiagramEdge realizes = new DiagramEdge(
                "rel-1",
                "CP-1",
                "CR-1",
                "REALIZED_BY",
                0.82,
                "impact");
        DiagramEdge produces = new DiagramEdge(
                "rel-2",
                "CR-1",
                "IP-1",
                "PRODUCES",
                0.73,
                "impact");
        return new DiagramModel(
                title,
                reverseOrder
                        ? List.of(information, service, capability)
                        : List.of(capability, service, information),
                reverseOrder
                        ? List.of(produces, realizes)
                        : List.of(realizes, produces),
                new DiagramLayout(direction, groupByLayer));
    }
}
