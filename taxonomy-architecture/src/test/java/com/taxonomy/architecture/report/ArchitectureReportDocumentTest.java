package com.taxonomy.architecture.report;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.export.LayeredDiagramLayoutService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureReportDocumentTest {
    @Test
    void reportGraphAndScenePresentationTitlesDoNotChangeContentDigest() {
        var graph = graph("Original graph title", "Saved node label");
        var evidence = evidence(graph);
        var original = document("Original report title", graph, "Original scene title", evidence);
        var retitled = document("Changed report title",
                graph("Changed graph title", "Saved node label"), "Changed scene title", evidence);

        assertThat(retitled.title()).isNotEqualTo(original.title());
        assertThat(retitled.diagram().title()).isNotEqualTo(original.diagram().title());
        assertThat(retitled.scene().title()).isNotEqualTo(original.scene().title());
        assertThat(retitled.evidence().graphSha256()).isEqualTo(original.evidence().graphSha256());
    }

    @Test
    void editedFrozenNodeLabelChangesContentDigestAndRejectsOriginalEvidence() {
        var original = graph("Graph title", "Saved node label");
        var edited = graph("Graph title", "Edited saved node label");

        assertThat(ArchitectureReportDocument.graphSha256(edited))
                .isNotEqualTo(ArchitectureReportDocument.graphSha256(original));
        assertThatThrownBy(() -> document("Report title", edited, "Scene title", evidence(original)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Frozen graph digest disagrees");
    }

    private static DiagramModel graph(String title, String savedNodeLabel) {
        return new DiagramModel(title,
                List.of(new DiagramNode("A", savedNodeLabel, "Capability", 1, true, 0)),
                List.of(), new DiagramLayout("LR", true));
    }

    private static ArchitectureReportDocument.SnapshotEvidence evidence(DiagramModel graph) {
        return new ArchitectureReportDocument.SnapshotEvidence(
                1L, 2L, 3L, 1, "snapshot-1", "repo", "workspace", "main", "commit",
                "MOCK", "model", "taxonomy-sha", ArchitectureReportDocument.graphSha256(graph));
    }

    private static ArchitectureReportDocument document(String title, DiagramModel graph,
            String sceneTitle, ArchitectureReportDocument.SnapshotEvidence evidence) {
        var layout = new LayeredDiagramLayoutService().layout(graph);
        var scene = new DiagramScene(sceneTitle, layout.width(), layout.height(),
                layout.direction(), layout.nodes(), layout.edges());
        return ArchitectureReportDocument.from(title, "en", "Requirement", "Scope", "Recommendation",
                List.of(), graph, scene, DecisionTreeOverview.from(List.of()), evidence);
    }
}
