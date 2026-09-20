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
    void wideCivilianSceneKeepsReadableLabelsAndHeaderTextInsideTheNode() throws Exception {
        var node = new com.taxonomy.diagram.DiagramSceneNode("CO-1048", "Short Messaging Access Services",
                "Communications Services", 1, true, 8, 4, true, null, false,
                2200, 40, 240, 84);
        var wide = new DiagramScene("Civilian flood information", 2516, 650, "LR", List.of(node), List.of());
        try (var document = org.apache.pdfbox.Loader.loadPDF(renderer.render(wide, "CIV-FLOOD-001"))) {
            var labels = new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();
            var nodeText = new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();
            var text = new org.apache.pdfbox.text.PDFTextStripper() {
                @Override protected void writeString(String value, List<org.apache.pdfbox.text.TextPosition> positions)
                        throws java.io.IOException {
                    if (value.contains("Short Messaging")) labels.addAll(positions);
                    if (value.contains("Short Messaging") || value.contains("CO-1048")
                            || value.contains("Communications Services") || value.contains("100%")) {
                        nodeText.addAll(positions);
                    }
                    super.writeString(value, positions);
                }
            };
            assertThat(text.getText(document)).contains("Short Messaging Access Services", "CO-1048", "100%", "Communications Services");
            assertThat(labels).isNotEmpty().allSatisfy(position -> assertThat(position.getFontSizeInPt()).isGreaterThanOrEqualTo(8));
            assertThat(nodeText).isNotEmpty().allSatisfy(position -> {
                assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(2232);
                assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThanOrEqualTo(2472);
            });
            assertThat(document.getPage(0).getMediaBox().getWidth()).isGreaterThan(2000);
        }
    }

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
