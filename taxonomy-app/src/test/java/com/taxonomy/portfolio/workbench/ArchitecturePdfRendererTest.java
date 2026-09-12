package com.taxonomy.portfolio.workbench;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.export.ArchitecturePdfRenderer.SnapshotMetadata;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ArchitecturePdfRendererTest {

    private static final String COMMIT = "abcdef1234567890abcdef1234567890abcdef1234";
    private static final List<String> WARNINGS = List.of("Review relation", "Verify dependency");
    private static final String INVALID_PROJECTION =
            "Architecture projection must contain a renderable scene";

    private final com.taxonomy.export.ArchitecturePdfRenderer delegate =
            mock(com.taxonomy.export.ArchitecturePdfRenderer.class);
    private final ArchitecturePdfRenderer renderer = new ArchitecturePdfRenderer(delegate);

    @Test
    void requiresTheConfiguredRenderer() {
        assertThatThrownBy(() -> new ArchitecturePdfRenderer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("delegate");
    }

    @Test
    void rejectsMissingProjectionBeforeCallingTheRenderer() {
        assertThatThrownBy(() -> renderer.render(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_PROJECTION);

        verifyNoInteractions(delegate);
    }

    @Test
    void rejectsMissingSceneBeforeCallingTheRenderer() {
        assertThatThrownBy(() -> renderer.render(projection(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_PROJECTION);

        verifyNoInteractions(delegate);
    }

    @Test
    void rejectsEmptySceneBeforeCallingTheRenderer() {
        DiagramScene empty = new LayeredDiagramLayoutService().layout(
                new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", true)));

        assertThatThrownBy(() -> renderer.render(projection(empty)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_PROJECTION);

        verifyNoInteractions(delegate);
    }

    @Test
    void passesTheSceneAndAllSnapshotMetadataToTheConfiguredRenderer() {
        DiagramScene scene = scene();
        SnapshotMetadata metadata = metadata();
        byte[] rendered = {1, 2, 3};
        when(delegate.render(scene, metadata)).thenReturn(rendered);

        assertThat(renderer.render(projection(scene))).isSameAs(rendered);

        verify(delegate).render(scene, metadata);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void propagatesRendererFailureWithoutReturningAPartialDocument() {
        DiagramScene scene = scene();
        IllegalStateException failure = new IllegalStateException("Unable to render architecture PDF");
        when(delegate.render(scene, metadata())).thenThrow(failure);

        assertThatThrownBy(() -> renderer.render(projection(scene))).isSameAs(failure);
    }

    @Test
    void rendersARealPdfWithSnapshotProvenanceAndWarnings() throws Exception {
        ArchitecturePdfRenderer realRenderer = new ArchitecturePdfRenderer(
                new com.taxonomy.export.ArchitecturePdfRenderer());

        byte[] pdf = realRenderer.render(projection(scene()));

        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            var information = document.getDocumentInformation();
            assertThat(information.getTitle()).isEqualTo("Payment architecture");
            assertThat(information.getSubject())
                    .isEqualTo("Requirement-derived architecture snapshot snapshot-17");
            assertThat(information.getAuthor()).isEqualTo("Taxonomy Architecture Analyzer");
            assertThat(information.getCreator()).isEqualTo("Taxonomy server-side vector renderer");
            assertThat(new PDFTextStripper().getText(document)).contains(
                    "Project P-001", "Requirement REQ-001", "Snapshot snapshot-17",
                    "Provider GEMINI", "Branch feature-pdf", "Commit abcdef123456",
                    "Warnings: Review relation | Verify dependency",
                    "APP-1", "Payment application", "SVC-1", "Payment service", "USES");
        }
    }

    private static SnapshotMetadata metadata() {
        return new SnapshotMetadata("P-001", "REQ-001", "snapshot-17", "GEMINI",
                "feature-pdf", COMMIT, WARNINGS);
    }

    private static Projection projection(DiagramScene scene) {
        return new Projection(
                1L, "P-001", "Payments", 2L, "REQ-001", "Secure payments", "Keep payments secure.",
                "snapshot-17", null, Instant.parse("2026-09-11T12:00:00Z"),
                "GEMINI", "test-model", "workspace-a", "feature-pdf", COMMIT,
                null, scene, Map.of(), Map.of(), WARNINGS);
    }

    private static DiagramScene scene() {
        return new LayeredDiagramLayoutService().layout(new DiagramModel(
                "Payment architecture",
                List.of(
                        new DiagramNode("APP-1", "Payment application", "Applications", 0.9, true, 1),
                        new DiagramNode("SVC-1", "Payment service", "Services", 0.8, false, 2)),
                List.of(new DiagramEdge("edge-1", "APP-1", "SVC-1", "USES", 0.8, "manual")),
                new DiagramLayout("LR", true)));
    }
}
