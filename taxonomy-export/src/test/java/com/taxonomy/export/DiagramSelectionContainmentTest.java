package com.taxonomy.export;

import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DiagramSelectionContainmentTest {

    @Test
    void collapsedChainLiftsLeafToTopLevelAndExports() throws Exception {
        var original = model(List.of(node("CP-1001", null, .8),
                node("CP-1002", "CP-1001", .8), node("CP-1003", "CP-1002", .8)));

        var selected = new ClusteringDiagramSelectionPolicy().apply(original);

        assertThat(selected.nodes()).extracting(DiagramNode::id).containsExactly("CP-1003");
        assertThat(selected.nodes().getFirst().parentId()).isNull();
        assertExports(selected);
        assertThat(original.nodes().getLast().parentId()).isEqualTo("CP-1002");
    }

    @Test
    void leafOnlyClearsExcludedParentsAndExports() throws Exception {
        var selected = new LeafOnlyDiagramSelectionPolicy().apply(model(List.of(
                node("CP-1001", null, .8), node("CP-1002", "CP-1001", .8))));

        assertThat(selected.nodes()).extracting(DiagramNode::id).containsExactly("CP-1002");
        assertThat(selected.nodes().getFirst().parentId()).isNull();
        assertExports(selected);
    }

    @Test
    void relevanceFilterFindsNearestSurvivingAncestorWithoutChangingSemanticEdges() throws Exception {
        var root = node("CP-1001", null, .9);
        var parent = node("CP-1002", root.id(), .1);
        var child = node("CP-1003", parent.id(), .8);
        var edge = new DiagramEdge("relation", root.id(), child.id(), "REALIZES", .7, "impact");
        var original = new DiagramModel("Filtered ancestry", List.of(root, parent, child),
                List.of(edge), new DiagramLayout("LR", true));

        var selected = policy(.35, 10).apply(original);

        assertThat(selected.nodes().getLast().parentId()).isEqualTo(root.id());
        assertThat(selected.edges()).containsExactly(edge);
        assertThat(original.nodes()).containsExactly(root, parent, child);
        assertExports(selected);
    }

    @Test
    void nodeLimitFindsNearestSurvivingAncestorAndExports() throws Exception {
        var selected = policy(0, 2).apply(model(List.of(node("CP-1001", null, .9),
                node("CP-1002", "CP-1001", .5), node("CP-1003", "CP-1002", .8))));

        assertThat(selected.nodes()).extracting(DiagramNode::id)
                .containsExactly("CP-1001", "CP-1003");
        assertThat(selected.nodes().getLast().parentId()).isEqualTo("CP-1001");
        assertExports(selected);
    }

    @Test
    void cyclesAndUnknownParentsDoNotLeaveLoopsOrDanglingReferences() {
        var selected = assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                policy(.35, 10).apply(model(List.of(
                        node("CP-1001", "CP-1002", .8), node("CP-1002", "CP-1001", .8),
                        node("CP-1003", "CP-1004", .8), node("CP-1004", "CP-1005", .1),
                        node("CP-1005", "CP-1004", .1), node("CP-1006", "missing", .8),
                        node("CP-1007", "CP-1007", .8)))));

        assertThat(selected.nodes()).extracting(DiagramNode::parentId).containsOnlyNulls();
    }

    private static ConfigurableDiagramSelectionPolicy policy(double minimum, int maximum) {
        return new ConfigurableDiagramSelectionPolicy(new DiagramSelectionConfig(
                false, false, false, false, false, false, minimum, maximum, 20));
    }

    private static DiagramNode node(String id, String parent, double relevance) {
        return new DiagramNode(id, id, "Capabilities", relevance, false, 1, 2, false, parent, false);
    }

    private static DiagramModel model(List<DiagramNode> nodes) {
        return new DiagramModel("Containment", nodes, List.of(), new DiagramLayout("LR", true));
    }

    private static void assertExports(DiagramModel selected) throws Exception {
        assertThat(new ArchiMateXmlExporter().export(new ArchiMateDiagramService().convert(selected)))
                .isNotEmpty();
        assertThat(new VisioPackageBuilder().build(new VisioDiagramService().convert(selected)))
                .isNotEmpty();
    }
}
