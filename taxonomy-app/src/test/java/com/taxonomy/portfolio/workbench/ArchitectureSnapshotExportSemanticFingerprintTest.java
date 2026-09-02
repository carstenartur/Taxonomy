package com.taxonomy.portfolio.workbench;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.service.CanonicalDiagramExportService;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.Artifact;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchitectureSnapshotExportSemanticFingerprintTest {

    private static final Long PROJECT_ID = 42L;
    private static final WorkspaceContext CONTEXT =
            new WorkspaceContext("alice", "workspace-a", "feature-a");
    private static final String PLAIN_SEMANTIC_GRAPH_SHA256 =
            "8baa1032271dcfc63b398e30c48d1da8518c8add825f7036267df9fdb331de2d";

    private final ArchitectureWorkbenchService workbenchService =
            mock(ArchitectureWorkbenchService.class);
    private final CanonicalDiagramExportService diagramExportService =
            mock(CanonicalDiagramExportService.class);
    private final ArchitectureSnapshotExportService service =
            new ArchitectureSnapshotExportService(
                    workbenchService,
                    diagramExportService);

    @Test
    void visualContainersAndTheirEdgesDoNotChangeTheSemanticGraphFingerprint() {
        DiagramModel plain = plainDiagram();
        DiagramModel grouped = groupedDiagram();
        when(workbenchService.load(
                PROJECT_ID,
                "snapshot-plain",
                "alice",
                CONTEXT))
                .thenReturn(projection("snapshot-plain", plain));
        when(workbenchService.load(
                PROJECT_ID,
                "snapshot-grouped",
                "alice",
                CONTEXT))
                .thenReturn(projection("snapshot-grouped", grouped));
        when(diagramExportService.exportAsArchiMate(plain))
                .thenReturn("plain-archimate".getBytes(StandardCharsets.UTF_8));
        when(diagramExportService.exportAsVisio(grouped))
                .thenReturn("grouped-visio".getBytes(StandardCharsets.UTF_8));

        Artifact plainArtifact = service.exportArchiMate(
                PROJECT_ID,
                "snapshot-plain",
                "alice",
                CONTEXT);
        Artifact groupedArtifact = service.exportVisio(
                PROJECT_ID,
                "snapshot-grouped",
                "alice",
                CONTEXT);

        assertThat(plainArtifact.canonicalGraphSha256())
                .isEqualTo(PLAIN_SEMANTIC_GRAPH_SHA256)
                .isEqualTo(groupedArtifact.canonicalGraphSha256());
        assertThat(plainArtifact.artifactSha256())
                .isNotEqualTo(groupedArtifact.artifactSha256());
        verify(diagramExportService).exportAsArchiMate(plain);
        verify(diagramExportService).exportAsVisio(grouped);
    }

    private static DiagramModel plainDiagram() {
        return new DiagramModel(
                "Plain view",
                List.of(
                        capability(1, null),
                        service(3)),
                List.of(semanticEdge()),
                new DiagramLayout("LR", true));
    }

    private static DiagramModel groupedDiagram() {
        DiagramNode container = new DiagramNode(
                "group-1",
                "Visual capability group",
                "Capabilities",
                0.0,
                false,
                0,
                0,
                false,
                null,
                true);
        DiagramEdge visualContainment = new DiagramEdge(
                "visual-group-edge",
                "group-1",
                "CP-1",
                "CONTAINS",
                1.0,
                "presentation");
        return new DiagramModel(
                "Grouped view",
                List.of(
                        container,
                        capability(11, "group-1"),
                        service(12)),
                List.of(
                        visualContainment,
                        semanticEdge()),
                new DiagramLayout("TB", false));
    }

    private static DiagramNode capability(int layer, String parentId) {
        return new DiagramNode(
                "CP-1",
                "Command capability",
                "Capabilities",
                0.92,
                true,
                layer,
                1,
                true,
                parentId,
                false);
    }

    private static DiagramNode service(int layer) {
        return new DiagramNode(
                "CR-1",
                "Secure exchange",
                "Core Services",
                0.84,
                false,
                layer,
                3,
                true,
                null,
                false);
    }

    private static DiagramEdge semanticEdge() {
        return new DiagramEdge(
                "rel-1",
                "CP-1",
                "CR-1",
                "REALIZED_BY",
                0.82,
                "impact");
    }

    private static Projection projection(
            String snapshotId,
            DiagramModel diagram) {
        return new Projection(
                PROJECT_ID,
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
                "abcdef1234567890",
                diagram,
                null,
                Map.of(),
                Map.of(),
                List.of());
    }
}
