package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LayeredDiagramLayoutServiceTest {

    private final LayeredDiagramLayoutService service = new LayeredDiagramLayoutService();

    @Test
    void positionsLayersDeterministicallyAndKeepsOnlyResolvableEdges() {
        DiagramModel model = new DiagramModel(
                "Requirement architecture",
                List.of(
                        new DiagramNode("UA-2", "User application", "User Applications", 0.72, false, 4),
                        new DiagramNode("CP-1", "Command capability", "Capabilities", 0.94, true, 1),
                        new DiagramNode("CR-1", "Core service", "Core Services", 0.81, false, 3)),
                List.of(
                        new DiagramEdge("e2", "CR-1", "UA-2", "SERVES", 0.7, "impact"),
                        new DiagramEdge("e1", "CP-1", "CR-1", "REALIZED_BY", 0.9, "impact"),
                        new DiagramEdge("broken", "CP-1", "MISSING", "TRACE", 0.2, "trace")),
                new DiagramLayout("LR", true));

        DiagramScene first = service.layout(model);
        DiagramScene second = service.layout(model);

        assertThat(first).isEqualTo(second);
        assertThat(first.nodes()).extracting(node -> node.id())
                .containsExactly("CP-1", "CR-1", "UA-2");
        assertThat(first.nodes().get(0).x()).isLessThan(first.nodes().get(1).x());
        assertThat(first.nodes().get(1).x()).isLessThan(first.nodes().get(2).x());
        assertThat(first.edges()).extracting(edge -> edge.id())
                .containsExactly("e1", "e2");
        assertThat(first.width()).isGreaterThan(700);
        assertThat(first.height()).isGreaterThan(400);
    }

    @Test
    void returnsExplicitEmptySceneForMissingArchitecture() {
        DiagramScene scene = service.layout(
                new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", true)));

        assertThat(scene.isEmpty()).isTrue();
        assertThat(scene.width()).isGreaterThanOrEqualTo(760);
        assertThat(scene.height()).isGreaterThanOrEqualTo(420);
    }
}
