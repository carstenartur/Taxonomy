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
    @Test void sparseOverviewRoutesPastInterveningCardAndKeepsTheCaptionOnEachStroke() throws Exception {
        var graph = new DiagramModel("Records", List.of(new DiagramNode("BP", "Process", "Business Processes", .8, true, 1),
                new DiagramNode("IP", "Record", "Information Products", .7, false, 2),
                new DiagramNode("UA", "Portal", "User Applications", .8, false, 3)),
                List.of(new DiagramEdge("a", "BP", "IP", "PRODUCES", .8), new DiagramEdge("b", "UA", "BP", "SUPPORTS", .7)), new DiagramLayout("LR", true));
        var document = new VisioDiagramService().convert(graph);
        var page = document.getPages().getFirst();
        var boxes = page.getConnects().stream().map(link -> link.getTextBox()).toList();
        assertThat(boxes).extracting(box -> box.text()).containsExactly("R1 PRODUCES", "R2 SUPPORTS");
        double strokeY = page.getShapes().getFirst().getY();
        assertThat(boxes.getFirst().y()).isEqualTo(strokeY);
        var middle = page.getShapes().get(1);
        var returning = page.getConnects().get(1);
        var route = new com.taxonomy.visio.VisioConnectionPoints.Columns(page.getShapes())
                .route(returning, page.getShapes().get(2), page.getShapes().getFirst());
        assertThat(route.lowerClearance()).isNotNull();
        assertThat(boxes.get(1).y()).isEqualTo(route.lowerLaneY());
        assertThat(boxes.get(1).y() + boxes.get(1).height() / 2).isLessThan(middle.getY() - middle.getHeight() / 2);
        var nativePage = VisioHandoffContractTest.parts(new VisioPackageBuilder().build(document)).get("visio/pages/page1.xml");
        assertThat(nativePage).contains("MIN(BeginY,EndY)", "Sheet.3!Connections.X3", "Sheet.1!Connections.X3");
    }

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
