package com.taxonomy.portfolio.workbench;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneNode;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.StructurizrExportService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.ExportFormat;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService.SnapshotArtifact;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArchitectureSnapshotExportServiceTest {

    private final ArchitectureWorkbenchService workbenchService =
            mock(ArchitectureWorkbenchService.class);
    private final SvgDiagramRenderer svgRenderer = mock(SvgDiagramRenderer.class);
    private final ArchitecturePdfRenderer pdfRenderer = mock(ArchitecturePdfRenderer.class);
    private final ArchiMateDiagramService archiMateDiagramService =
            mock(ArchiMateDiagramService.class);
    private final ArchiMateXmlExporter archiMateXmlExporter = mock(ArchiMateXmlExporter.class);
    private final MermaidExportService mermaidExportService = mock(MermaidExportService.class);
    private final StructurizrExportService structurizrExportService =
            mock(StructurizrExportService.class);

    private final ArchitectureSnapshotExportService service =
            new ArchitectureSnapshotExportService(
                    workbenchService,
                    svgRenderer,
                    pdfRenderer,
                    archiMateDiagramService,
                    archiMateXmlExporter,
                    mermaidExportService,
                    structurizrExportService,
                    JsonMapper.builder().build());

    @Test
    void everyFormatUsesTheSameScopedPersistedProjectionAndGraphFingerprint() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");
        Projection projection = projection(diagram());
        ArchiMateModel archiMateModel = mock(ArchiMateModel.class);
        when(workbenchService.load(42L, "snapshot-1", "alice", context))
                .thenReturn(projection);
        when(svgRenderer.render(projection.scene())).thenReturn("<svg/>");
        when(pdfRenderer.render(projection)).thenReturn(
                "%PDF-snapshot".getBytes(StandardCharsets.ISO_8859_1));
        when(archiMateDiagramService.convert(projection.diagram())).thenReturn(archiMateModel);
        when(archiMateXmlExporter.export(archiMateModel)).thenReturn(
                "<model/>".getBytes(StandardCharsets.UTF_8));
        when(mermaidExportService.export(projection.diagram())).thenReturn("flowchart LR\n");
        when(structurizrExportService.export(projection.diagram())).thenReturn("workspace {}\n");

        List<SnapshotArtifact> artifacts = new ArrayList<>();
        for (ExportFormat format : ExportFormat.values()) {
            artifacts.add(service.export(
                    42L, "snapshot-1", "alice", context, format.id()));
        }

        assertThat(artifacts)
                .extracting(SnapshotArtifact::snapshotId)
                .containsOnly("snapshot-1");
        assertThat(artifacts)
                .extracting(SnapshotArtifact::commitSha)
                .containsOnly("0123456789abcdef");
        assertThat(artifacts)
                .extracting(SnapshotArtifact::graphSha256)
                .containsOnly(artifacts.get(0).graphSha256());
        assertThat(artifacts)
                .extracting(artifact -> artifact.format().id())
                .containsExactly("json", "svg", "pdf", "archimate", "mermaid", "structurizr");
        assertThat(artifacts)
                .allSatisfy(artifact -> assertThat(artifact.contentSha256()).hasSize(64));

        verify(workbenchService, times(ExportFormat.values().length))
                .load(42L, "snapshot-1", "alice", context);
        verify(archiMateDiagramService).convert(projection.diagram());
        verify(archiMateXmlExporter).export(archiMateModel);
        verify(mermaidExportService).export(projection.diagram());
        verify(structurizrExportService).export(projection.diagram());
    }

    @Test
    void evidenceJsonCarriesTheImmutableSnapshotCoordinates() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");
        Projection projection = projection(diagram());
        when(workbenchService.load(42L, "snapshot-1", "alice", context))
                .thenReturn(projection);

        SnapshotArtifact artifact = service.export(
                42L, "snapshot-1", "alice", context, "json");
        String json = new String(artifact.content(), StandardCharsets.UTF_8);

        assertThat(json)
                .contains("\"snapshotId\" : \"snapshot-1\"")
                .contains("\"commitSha\" : \"0123456789abcdef\"")
                .contains("\"requirementText\" : \"Need secure communications\"")
                .contains("\"diagram\"")
                .contains("\"scene\"");
        verifyNoInteractions(
                svgRenderer,
                pdfRenderer,
                archiMateDiagramService,
                archiMateXmlExporter,
                mermaidExportService,
                structurizrExportService);
    }

    @Test
    void graphFingerprintIsOrderIndependentButChangesWithSemantics() {
        DiagramModel original = diagram();
        List<DiagramNode> reversedNodes = new ArrayList<>(original.nodes());
        List<DiagramEdge> reversedEdges = new ArrayList<>(original.edges());
        Collections.reverse(reversedNodes);
        Collections.reverse(reversedEdges);
        DiagramModel reordered = new DiagramModel(
                original.title(), reversedNodes, reversedEdges, original.layout());
        DiagramModel changed = new DiagramModel(
                original.title(),
                original.nodes(),
                List.of(new DiagramEdge(
                        "rel-1", "service", "capability", "SUPPORTS", 0.72, "impact")),
                original.layout());

        assertThat(ArchitectureSnapshotExportService.graphFingerprint(reordered))
                .isEqualTo(ArchitectureSnapshotExportService.graphFingerprint(original));
        assertThat(ArchitectureSnapshotExportService.graphFingerprint(changed))
                .isNotEqualTo(ArchitectureSnapshotExportService.graphFingerprint(original));
    }

    @Test
    void rejectsInvalidCoordinatesAndFormatsBeforeReadingAnySnapshot() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");

        assertThatThrownBy(() -> service.export(
                0L, "snapshot-1", "alice", context, "json"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> service.export(
                42L, "../snapshot", "alice", context, "json"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("snapshotId");
        assertThatThrownBy(() -> service.export(
                42L, "snapshot-1", "alice", context, "visio"))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Unsupported architecture export format");

        verify(workbenchService, never())
                .load(42L, "snapshot-1", "alice", context);
        verifyNoInteractions(
                svgRenderer,
                pdfRenderer,
                archiMateDiagramService,
                archiMateXmlExporter,
                mermaidExportService,
                structurizrExportService);
    }

    @Test
    void artifactContentIsDefensivelyCopied() {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "feature-a");
        Projection projection = projection(diagram());
        when(workbenchService.load(42L, "snapshot-1", "alice", context))
                .thenReturn(projection);

        SnapshotArtifact artifact = service.export(
                42L, "snapshot-1", "alice", context, "json");
        byte[] first = artifact.content();
        first[0] = (byte) 'X';

        assertThat(new String(artifact.content(), StandardCharsets.UTF_8))
                .startsWith("{");
    }

    private static Projection projection(DiagramModel diagram) {
        DiagramScene scene = new DiagramScene(
                "Persisted architecture", 360, 180, "LR",
                List.of(new DiagramSceneNode(
                        "capability", "Secure communications", "Capabilities",
                        0.91, true, 1, 2, true, null, false,
                        20, 30, 180, 80)),
                List.of());
        return new Projection(
                42L,
                "PRJ-001",
                "Secure communications project",
                7L,
                "REQ-001",
                "Secure communications",
                "Need secure communications",
                "snapshot-1",
                AnalysisStatus.SUCCESS,
                Instant.parse("2026-09-01T12:00:00Z"),
                "MOCK",
                "deterministic",
                "workspace-a",
                "feature-a",
                "0123456789abcdef",
                diagram,
                scene,
                Map.of(),
                Map.of(),
                List.of("Example warning"));
    }

    private static DiagramModel diagram() {
        return new DiagramModel(
                "Persisted architecture",
                List.of(
                        new DiagramNode(
                                "capability", "Secure communications", "Capabilities",
                                0.91, true, 1, 2, true, null, false),
                        new DiagramNode(
                                "service", "Messaging service", "Core Services",
                                0.72, false, 3, 2, true, null, false)),
                List.of(new DiagramEdge(
                        "rel-1", "capability", "service", "SUPPORTS", 0.72, "impact")),
                new DiagramLayout("LR", true));
    }
}
