package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDiagramRendererTest {

    private final LayeredDiagramLayoutService layout = new LayeredDiagramLayoutService();
    private final PdfDiagramRenderer renderer = new PdfDiagramRenderer();

    @Test
    void rendersVectorPdfWithCallerOwnedMetadataAndPageText() throws Exception {
        DiagramScene scene = layout.layout(new DiagramModel(
                "Secure architecture",
                List.of(
                        new DiagramNode("CP-1", "Command & Control", "Capabilities", 0.91, true, 1),
                        new DiagramNode("CR-1", "Secure exchange", "Core Services", 0.84, false, 3)),
                List.of(new DiagramEdge(
                        "edge-1", "CP-1", "CR-1", "REALIZED_BY", 0.8, "impact")),
                new DiagramLayout("LR", true)));
        var details = new PdfDiagramRenderer.DocumentDetails(
                "Exact architecture state",
                "Taxonomy test",
                "architecture, test",
                List.of(
                        new PdfDiagramRenderer.HeaderLine("Repository demo / workspace-a", 8, 17),
                        new PdfDiagramRenderer.HeaderLine("Checkpoint abc123", 8, 29)),
                List.of(new PdfDiagramRenderer.FooterLine("Git-authoritative architecture", 8, 16)));

        byte[] pdf = renderer.render(scene, details);

        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Secure architecture");
            assertThat(document.getDocumentInformation().getSubject()).isEqualTo("Exact architecture state");
            assertThat(document.getDocumentInformation().getAuthor()).isEqualTo("Taxonomy test");
            assertThat(document.getDocumentInformation().getCreator()).isEqualTo("Taxonomy server-side vector renderer");
            assertThat(document.getDocumentInformation().getKeywords()).isEqualTo("architecture, test");
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Secure architecture", "CP-1", "Command & Control", "REALIZED_BY")
                    .contains("Repository demo / workspace-a", "Checkpoint abc123", "Git-authoritative architecture");
        }
    }

    @Test
    void refusesToPretendAnEmptySceneIsAnArchitecture() {
        DiagramScene empty = layout.layout(
                new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", true)));
        var details = new PdfDiagramRenderer.DocumentDetails(null, null, null, List.of(), List.of());

        assertThatThrownBy(() -> renderer.render(empty, details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one node");
    }
}
