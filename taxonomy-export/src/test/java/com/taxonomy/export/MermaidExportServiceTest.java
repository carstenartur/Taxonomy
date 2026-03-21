package com.taxonomy.export;

import com.taxonomy.diagram.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MermaidExportServiceTest {

    private final MermaidExportService service = new MermaidExportService();

    @Test
    void nullModelProducesEmptyDiagram() {
        String result = service.export(null);

        assertTrue(result.startsWith("flowchart LR"));
        assertTrue(result.contains("No data"));
    }

    @Test
    void emptyNodesProducesEmptyDiagram() {
        var model = new DiagramModel("Test", List.of(), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.contains("No data"));
    }

    @Test
    void singleNodeIsRendered() {
        var node = new DiagramNode("CP-1023", "Capability A", "Capabilities", 0.8, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.startsWith("flowchart LR"));
        assertTrue(result.contains("CP_1023"));
        assertTrue(result.contains("Capability A"));
    }

    @Test
    void anchorNodeGetsStarMarker() {
        var node = new DiagramNode("CP-1023", "Anchor Node", "Capabilities", 0.9, true, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.contains("★"));
    }

    @Test
    void nonAnchorNodeHasNoStarMarker() {
        var node = new DiagramNode("CP-1023", "Normal Node", "Capabilities", 0.8, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertFalse(result.contains("★"));
    }

    @Test
    void relevancePercentageIsShown() {
        var node = new DiagramNode("CP-1023", "Capability A", "Capabilities", 0.85, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.contains("85%"));
    }

    @Test
    void zeroRelevanceIsNotShown() {
        var node = new DiagramNode("CP-1023", "Capability A", "Capabilities", 0.0, true, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertFalse(result.contains("0%"));
    }

    @Test
    void edgesAreRenderedWithRelationType() {
        var node1 = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.8, false, 1);
        var node2 = new DiagramNode("CR-1047", "Service B", "Core Services", 0.7, false, 3);
        var edge = new DiagramEdge("e1", "CP-1023", "CR-1047", "REALIZES", 0.75);
        var model = new DiagramModel("Test", List.of(node1, node2), List.of(edge),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.contains("CP_1023 -->|REALIZES| CR_1047"));
    }

    @Test
    void nodesGroupedByTypeInSubgraphs() {
        var node1 = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.8, false, 1);
        var node2 = new DiagramNode("CP-1024", "Cap B", "Capabilities", 0.7, false, 1);
        var model = new DiagramModel("Test", List.of(node1, node2), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.contains("subgraph Capabilities"));
    }

    @Test
    void layoutDirectionIsUsed() {
        var node = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.8, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("TB", true));

        String result = service.export(model);

        assertTrue(result.startsWith("flowchart TB"));
    }

    @Test
    void nullLayoutDefaultsToLR() {
        var node = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.8, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(), null);

        String result = service.export(model);

        assertTrue(result.startsWith("flowchart LR"));
    }

    @Test
    void classDefsAreIncluded() {
        var node = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.8, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.contains("classDef cap"));
        assertTrue(result.contains("classDef svc"));
    }

    @Test
    void classIsAppliedToCapabilityNodes() {
        var node = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.8, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.contains("class CP_1023 cap"));
    }

    @Test
    void sanitizeIdReplacesSpecialCharacters() {
        assertEquals("CP_1023", MermaidExportService.sanitizeId("CP-1023"));
        assertEquals("unknown", MermaidExportService.sanitizeId(null));
        assertEquals("hello_world", MermaidExportService.sanitizeId("hello world"));
    }

    @Test
    void escapeHandlesSpecialCharacters() {
        assertEquals("", MermaidExportService.escape(null));
        assertEquals("&quot;quoted&quot;", MermaidExportService.escape("\"quoted\""));
        assertEquals("&num;tag", MermaidExportService.escape("#tag"));
    }

    @Test
    void multipleLayerTypesAreSortedCorrectly() {
        var cap = new DiagramNode("CP-1023", "Cap", "Capabilities", 0.8, false, 1);
        var svc = new DiagramNode("CR-1047", "Svc", "Core Services", 0.7, false, 3);
        var app = new DiagramNode("UA-2001", "App", "User Applications", 0.6, false, 4);
        var model = new DiagramModel("Test", List.of(svc, app, cap), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        int capPos = result.indexOf("Capabilities");
        int svcPos = result.indexOf("Core Services");
        int appPos = result.indexOf("User Applications");
        assertTrue(capPos < svcPos, "Capabilities should appear before Core Services");
        assertTrue(svcPos < appPos, "Core Services should appear before User Applications");
    }
}
