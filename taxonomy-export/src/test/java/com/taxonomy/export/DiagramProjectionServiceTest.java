package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiagramProjectionServiceTest {

    private final DiagramProjectionService service = new DiagramProjectionService();

    @Test
    void nullViewProducesEmptyModel() {
        DiagramModel result = service.project(null, "Test");

        assertNotNull(result);
        assertEquals("Test", result.title());
        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
        assertEquals("LR", result.layout().direction());
    }

    @Test
    void emptyViewProducesEmptyModel() {
        var view = new RequirementArchitectureView();
        DiagramModel result = service.project(view, "Empty");

        assertNotNull(result);
        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void highRelevanceElementsAreIncluded() {
        var view = new RequirementArchitectureView();
        var elem = createElement("CP-1023", "Capability A", "Capabilities", 0.8, false);
        view.setIncludedElements(List.of(elem));

        DiagramModel result = service.project(view, "Test");

        assertEquals(1, result.nodes().size());
        assertEquals("CP-1023", result.nodes().get(0).id());
        assertEquals("Capability A", result.nodes().get(0).label());
    }

    @Test
    void lowRelevanceNonAnchorElementsAreExcluded() {
        var view = new RequirementArchitectureView();
        var elem = createElement("CP-1023", "Low Relevance", "Capabilities", 0.1, false);
        view.setIncludedElements(List.of(elem));

        DiagramModel result = service.project(view, "Test");

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void lowRelevanceAnchorElementsAreIncluded() {
        var view = new RequirementArchitectureView();
        var elem = createElement("CP-1023", "Anchor Node", "Capabilities", 0.1, true);
        view.setIncludedElements(List.of(elem));

        DiagramModel result = service.project(view, "Test");

        assertEquals(1, result.nodes().size());
        assertTrue(result.nodes().get(0).anchor());
    }

    @Test
    void edgesFilteredByIncludedNodes() {
        var view = new RequirementArchitectureView();
        var elem1 = createElement("CP-1023", "Cap A", "Capabilities", 0.8, false);
        var elem2 = createElement("CR-1047", "Svc B", "Core Services", 0.7, false);
        view.setIncludedElements(List.of(elem1, elem2));

        var rel = createRelation("CP-1023", "CR-1047", "REALIZES", 0.75);
        view.setIncludedRelationships(List.of(rel));

        DiagramModel result = service.project(view, "Test");

        assertEquals(2, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals("CP-1023", result.edges().get(0).sourceId());
        assertEquals("CR-1047", result.edges().get(0).targetId());
    }

    @Test
    void edgesWithMissingNodesAreExcluded() {
        var view = new RequirementArchitectureView();
        var elem = createElement("CP-1023", "Cap A", "Capabilities", 0.8, false);
        view.setIncludedElements(List.of(elem));

        var rel = createRelation("CP-1023", "CR-MISSING", "REALIZES", 0.5);
        view.setIncludedRelationships(List.of(rel));

        DiagramModel result = service.project(view, "Test");

        assertEquals(1, result.nodes().size());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void nodesAreSortedByRelevanceDescending() {
        var view = new RequirementArchitectureView();
        var low = createElement("CP-1001", "Low", "Capabilities", 0.4, false);
        var high = createElement("CP-1002", "High", "Capabilities", 0.9, false);
        var mid = createElement("CP-1003", "Mid", "Capabilities", 0.6, false);
        view.setIncludedElements(List.of(low, high, mid));

        DiagramModel result = service.project(view, "Test");

        assertEquals(3, result.nodes().size());
        assertEquals("CP-1002", result.nodes().get(0).id());
        assertEquals("CP-1003", result.nodes().get(1).id());
        assertEquals("CP-1001", result.nodes().get(2).id());
    }

    @Test
    void nodesAreLimitedToMaxNodes() {
        var view = new RequirementArchitectureView();
        var elements = new ArrayList<RequirementElementView>();
        for (int i = 0; i < 30; i++) {
            elements.add(createElement("CP-" + String.format("%04d", i),
                    "Node " + i, "Capabilities", 0.5 + (i * 0.01), false));
        }
        view.setIncludedElements(elements);

        DiagramModel result = service.project(view, "Test");

        assertTrue(result.nodes().size() <= DiagramProjectionService.MAX_NODES);
    }

    @Test
    void edgesAreLimitedToMaxEdges() {
        var view = new RequirementArchitectureView();
        var elements = new ArrayList<RequirementElementView>();
        var rels = new ArrayList<RequirementRelationshipView>();

        for (int i = 0; i < 10; i++) {
            elements.add(createElement("CP-" + String.format("%04d", i),
                    "Node " + i, "Capabilities", 0.9, false));
        }
        view.setIncludedElements(elements);

        // Create many edges between all pairs
        for (int i = 0; i < 10; i++) {
            for (int j = i + 1; j < 10; j++) {
                rels.add(createRelation(
                        "CP-" + String.format("%04d", i),
                        "CP-" + String.format("%04d", j),
                        "RELATED_TO", 0.5));
            }
        }
        view.setIncludedRelationships(rels);

        DiagramModel result = service.project(view, "Test");

        assertTrue(result.edges().size() <= DiagramProjectionService.MAX_EDGES);
    }

    @Test
    void layerIsAssignedFromTaxonomySheet() {
        var view = new RequirementArchitectureView();
        var elem = createElement("CP-1023", "Cap", "Capabilities", 0.8, false);
        view.setIncludedElements(List.of(elem));

        DiagramModel result = service.project(view, "Test");

        assertEquals(1, result.nodes().get(0).layer());
    }

    @Test
    void nullTaxonomySheetGetsLayerZero() {
        var view = new RequirementArchitectureView();
        var elem = createElement("XX-0001", "Unknown", null, 0.8, true);
        view.setIncludedElements(List.of(elem));

        DiagramModel result = service.project(view, "Test");

        assertEquals(0, result.nodes().get(0).layer());
        assertEquals("Unknown", result.nodes().get(0).type());
    }

    @Test
    void nullTitleFallsBackToNodeCode() {
        var view = new RequirementArchitectureView();
        var elem = createElement("CP-1023", null, "Capabilities", 0.8, false);
        view.setIncludedElements(List.of(elem));

        DiagramModel result = service.project(view, "Test");

        assertEquals("CP-1023", result.nodes().get(0).label());
    }

    @Test
    void rootCodeIsResolvedToFullLayerName() {
        var view = new RequirementArchitectureView();
        var elem = createElement("CP-1023", "Cap A", "CP", 0.8, false);
        view.setIncludedElements(List.of(elem));

        DiagramModel result = service.project(view, "Test");

        assertEquals("Capabilities", result.nodes().get(0).type());
        assertEquals(1, result.nodes().get(0).layer());
    }

    @Test
    void allRootCodesAreResolvedCorrectly() {
        var view = new RequirementArchitectureView();
        view.setIncludedElements(List.of(
                createElement("N1", "n", "BP", 0.9, true),
                createElement("N2", "n", "BR", 0.9, true),
                createElement("N3", "n", "CP", 0.9, true),
                createElement("N4", "n", "CI", 0.9, true),
                createElement("N5", "n", "CO", 0.9, true),
                createElement("N6", "n", "CR", 0.9, true),
                createElement("N7", "n", "UA", 0.9, true),
                createElement("N8", "n", "IP", 0.9, true)
        ));

        DiagramModel result = service.project(view, "Test");

        var types = result.nodes().stream().map(DiagramNode::type).toList();
        assertTrue(types.contains("Business Processes"));
        assertTrue(types.contains("Business Roles"));
        assertTrue(types.contains("Capabilities"));
        assertTrue(types.contains("COI Services"));
        assertTrue(types.contains("Communications Services"));
        assertTrue(types.contains("Core Services"));
        assertTrue(types.contains("User Applications"));
        assertTrue(types.contains("Information Products"));
    }

    @Test
    void layoutIsAlwaysLRWithGrouping() {
        var view = new RequirementArchitectureView();
        var elem = createElement("CP-1023", "Cap", "Capabilities", 0.8, false);
        view.setIncludedElements(List.of(elem));

        DiagramModel result = service.project(view, "Test");

        assertEquals("LR", result.layout().direction());
        assertTrue(result.layout().groupByLayer());
    }

    // --- helper methods ---

    private RequirementElementView createElement(String code, String title,
                                                  String sheet, double relevance, boolean anchor) {
        var elem = new RequirementElementView();
        elem.setNodeCode(code);
        elem.setTitle(title);
        elem.setTaxonomySheet(sheet);
        elem.setRelevance(relevance);
        elem.setAnchor(anchor);
        return elem;
    }

    private RequirementRelationshipView createRelation(String source, String target,
                                                        String type, double relevance) {
        var rel = new RequirementRelationshipView();
        rel.setSourceCode(source);
        rel.setTargetCode(target);
        rel.setRelationType(type);
        rel.setPropagatedRelevance(relevance);
        return rel;
    }
}
