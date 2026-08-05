package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SvgDiagramRendererTest {

    private final LayeredDiagramLayoutService layout = new LayeredDiagramLayoutService();
    private final SvgDiagramRenderer renderer = new SvgDiagramRenderer();

    @Test
    void rendersStandaloneScriptFreeSvgFromTheScene() {
        DiagramScene scene = layout.layout(new DiagramModel(
                "Secure <architecture>",
                List.of(
                        new DiagramNode("CP-1", "Command & Control", "Capabilities", 0.91, true, 1),
                        new DiagramNode("CR-1", "Secure exchange", "Core Services", 0.84, false, 3)),
                List.of(new DiagramEdge(
                        "edge-1", "CP-1", "CR-1", "REALIZED_BY", 0.8, "impact")),
                new DiagramLayout("LR", true)));

        String svg = renderer.render(scene);

        assertThat(svg)
                .startsWith("<?xml version=")
                .contains("<svg xmlns=\"http://www.w3.org/2000/svg\"")
                .contains("Secure &lt;architecture&gt;")
                .contains("Command &amp; Control")
                .contains("REALIZED_BY")
                .doesNotContain("<script")
                .doesNotContain("window.print");
    }

    @Test
    void refusesToPretendAnEmptySceneIsAnArchitecture() {
        DiagramScene empty = layout.layout(
                new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", true)));

        assertThatThrownBy(() -> renderer.render(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one node");
    }
}
