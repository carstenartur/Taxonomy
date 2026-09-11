package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitecturePdfRendererTest {

    private final LayeredDiagramLayoutService layout = new LayeredDiagramLayoutService();
    private final ArchitecturePdfRenderer renderer = new ArchitecturePdfRenderer();

    @Test
    void rendersLiveEditorSceneWithoutDependingOnApplicationTypes() {
        DiagramScene scene = scene();

        byte[] pdf = renderer.render(scene,
                "repo-a / workspace-a / draft\nRevision 3 / checkpoint abcdef1234567890");

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void preservesExactLiveProvenanceInPdfMetadata() throws Exception {
        String provenance = "  repo-a / workspace-a / draft  \nCheckpoint abcdef1234567890  ";

        byte[] pdf = renderer.render(scene(), provenance);

        try (var parsed = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(parsed.getDocumentInformation().getSubject()).isEqualTo(provenance);
        }
    }

    @Test
    void rendersSnapshotSceneThroughNeutralExportMetadata() {
        DiagramScene scene = scene();
        var metadata = new ArchitecturePdfRenderer.SnapshotMetadata(
                "P-001", "REQ-001", "snapshot-1", "GEMINI",
                "feature-a", "abcdef1234567890", List.of("Review relation"));

        byte[] pdf = renderer.render(scene, metadata);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void rejectsEmptySceneInsteadOfProducingMisleadingPdf() {
        DiagramScene empty = layout.layout(
                new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", true)));

        assertThatThrownBy(() -> renderer.render(empty, "provenance"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    private DiagramScene scene() {
        return layout.layout(new DiagramModel(
                "Secure architecture",
                List.of(
                        new DiagramNode("CP-1", "Command capability", "Capabilities", 0.91, true, 1),
                        new DiagramNode("CR-1", "Secure exchange", "Core Services", 0.84, false, 3)),
                List.of(new DiagramEdge(
                        "edge-1", "CP-1", "CR-1", "REALIZED_BY", 0.8, "impact")),
                new DiagramLayout("LR", true)));
    }
}
