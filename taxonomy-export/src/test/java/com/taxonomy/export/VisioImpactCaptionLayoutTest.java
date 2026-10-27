package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisioImpactCaptionLayoutTest {
    @Test
    void impactCaptionsUseTheirOwnGeometryWhenOverviewContainersShiftTheOrigin() {
        var nodes = new ArrayList<DiagramNode>();
        for (int index = 0; index < 7; index++)
            nodes.add(new DiagramNode("c" + index, "Container " + index, "Layer", 0, false, 0, 0,
                    false, index == 0 ? null : "c" + (index - 1), true));
        nodes.add(new DiagramNode("leaf-a", "First selected leaf", "Capabilities", .8, false, 0, 1,
                true, "c6", false));
        nodes.add(new DiagramNode("leaf-b", "Second selected leaf", "Business Processes", .7, false, 0, 1,
                true, "c6", false));
        var graph = new DiagramModel("Nested selection", nodes,
                List.of(new DiagramEdge("edge", "leaf-a", "leaf-b", "SUPPORTS", .7)), new DiagramLayout("LR", true));
        var document = new VisioDiagramService().convert(graph);
        var overview = document.getPages().getFirst();
        var impact = document.getPages().get(1);
        var leaf = impact.getShapes().getFirst();
        var overviewLeaf = overview.getShapes().stream().filter(shape -> shape.getId().equals(leaf.getId()))
                .findFirst().orElseThrow();
        assertThat(overviewLeaf.getX()).isGreaterThan(leaf.getX());
        assertThat(impact.getShapes()).allSatisfy(shape -> assertThat(shape.getX()).isEqualTo(leaf.getX()));
        assertThat(impact.getConnects().getFirst().getTextBox().x()).isEqualTo(leaf.getX());
    }
}
