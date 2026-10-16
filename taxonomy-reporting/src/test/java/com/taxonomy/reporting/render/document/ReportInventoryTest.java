package com.taxonomy.reporting.render.document;

import com.taxonomy.diagram.*;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReportInventoryTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void countsUniqueSavedElementsNotRepeatedDetailPanelsOrInferredApprovals(boolean de) {
        var node = new DiagramNode("function", "Function", "CP", .005, true, 1, 2, false, null, false);
        var container = new DiagramNode("container", "Group", "CP", 0, false, 1, 1, false, null, true);
        var edge = new DiagramEdge("edge", "container", "function", "CONTAINS", .5, "trace");
        var graph = new DiagramModel("Fixture", List.of(node, node, container), List.of(edge, edge), new DiagramLayout("LR", true));
        String text = String.join("\n", ReportInventory.diagram(graph, de));
        assertThat(text).contains(de ? "1 Elemente · Navigationsknoten: 1 · Beziehungen: 1"
                : "1 elements · Navigation nodes: 1 · Relationships: 1");
        assertThat(text).contains("CP: 1", de ? "nicht aufgezeichnet" : "not recorded");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void countsCompleteTreeIncludingUnassessedRowsRatherThanLeadingCandidates(boolean de) {
        var root = new DecisionTreeOverview.DecisionTreeRow(0, "CP", "Capabilities", .005, null, null, null);
        var child = new DecisionTreeOverview.DecisionTreeRow(1, "CP-1", "Function", null, null, null, null);
        var tree = new DecisionTreeOverview(List.of(root, child, child), List.of());
        String text = String.join("\n", ReportInventory.tree(tree, de));
        assertThat(text).contains(de ? "einschließlich Navigation): 2" : "including navigation): 2", "CP: 2");
    }
}
