package com.taxonomy.architecture.report;

import static org.assertj.core.api.Assertions.*;

import com.taxonomy.diagram.*;
import com.taxonomy.export.LayeredDiagramLayoutService;

import org.junit.jupiter.api.Test;

import java.util.*;

class ArchitectureFigurePlannerTest {
    static DiagramModel graph(int count, boolean dense) {
        var nodes = new ArrayList<DiagramNode>();
        var edges = new ArrayList<DiagramEdge>();
        for (int i = 0; i < count; i++)
            nodes.add(new DiagramNode("N" + i, "Saved node " + i, "Layer", .8, i == 0, i % 4));
        for (int i = 1; i < count - 2; i++) {
            edges.add(new DiagramEdge("E" + i, "N0", "N" + i, "serves", .7));
            if (dense && i > 1)
                edges.add(new DiagramEdge("D" + i, "N" + (i - 1), "N" + i, "flows", .6));
        }
        return new DiagramModel("Frozen", nodes, edges, new DiagramLayout("LR", true));
    }

    @Test
    void boundedPanelsCoverDenseDisconnectedGraphAndAreDeterministic() {
        var graph = graph(45, true);
        var scene = new LayeredDiagramLayoutService().layout(graph);
        var planner = new ArchitectureFigurePlanner();
        var plan = planner.plan(graph, scene);
        assertThat(plan).isEqualTo(planner.plan(graph, scene));
        assertThat(plan.overview().scene()).isEqualTo(scene);
        assertThat(plan.details())
                .allSatisfy(
                        p -> {
                            assertThat(p.nodeIds()).hasSizeLessThanOrEqualTo(12);
                            assertThat(p.edgeIds()).hasSizeLessThanOrEqualTo(18);
                            assertThat(p.scene().width())
                                    .isLessThanOrEqualTo(
                                            ArchitectureFigurePlanner.MAX_DETAIL_WIDTH);
                            assertThat(p.scene().height())
                                    .isLessThanOrEqualTo(
                                            ArchitectureFigurePlanner.MAX_DETAIL_HEIGHT);
                        });
        assertThat(
                        plan.details().stream()
                                .flatMap(p -> p.nodeIds().stream())
                                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(
                        graph.nodes().stream().map(DiagramNode::id).toList());
        assertThat(
                        plan.details().stream()
                                .flatMap(p -> p.edgeIds().stream())
                                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(
                        graph.edges().stream().map(DiagramEdge::id).toList());
    }

    @Test
    void rejectsOversizedGraphBeforeRenderingAndMissingEndpoints() {
        var graph = graph(1001, false);
        assertThatThrownBy(
                        () ->
                                new ArchitectureFigurePlanner()
                                        .plan(
                                                graph,
                                                new LayeredDiagramLayoutService().layout(graph)))
                .hasMessageContaining("policy ceiling");
        var broken =
                new DiagramModel(
                        "broken",
                        List.of(new DiagramNode("A", "A", "", 1, true, 0)),
                        List.of(new DiagramEdge("E", "A", "missing", "serves", 1)),
                        null);
        assertThatThrownBy(
                        () ->
                                new ArchitectureFigurePlanner()
                                        .plan(
                                                broken,
                                                new LayeredDiagramLayoutService().layout(broken)))
                .hasMessageContaining("endpoint");
    }

    @Test
    void chainAndDisconnectedIsolatesRetainNodeMetadataAndAllActualSceneBounds() {
        var base = graph(40, false);
        var edges = new ArrayList<DiagramEdge>();
        for (int i = 0; i < 35; i++)
            edges.add(
                    new DiagramEdge("chain-" + i, "N" + i, "N" + (i + 1), "SUPPORTS", .4, "trace"));
        var graph = new DiagramModel("Chain", base.nodes(), edges, base.layout());
        var result =
                new ArchitectureFigurePlanner()
                        .plan(graph, new LayeredDiagramLayoutService().layout(graph));
        assertThat(result.details())
                .allSatisfy(
                        p -> {
                            assertThat(p.scene().width())
                                    .isLessThanOrEqualTo(
                                            ArchitectureFigurePlanner.MAX_DETAIL_WIDTH);
                            assertThat(p.scene().height())
                                    .isLessThanOrEqualTo(
                                            ArchitectureFigurePlanner.MAX_DETAIL_HEIGHT);
                            assertThat(graph.nodes()).containsAll(p.diagram().nodes());
                        });
        assertThat(result.details().stream().flatMap(p -> p.edgeIds().stream()).toList())
                .containsExactlyInAnyOrderElementsOf(edges.stream().map(DiagramEdge::id).toList());
    }
}
