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

        assertTrue(result.contains("CP_1023 -->|realizes| CR_1047"));
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

    // ── Showcase mode tests ──────────────────────────────────────────────

    @Test
    void showcaseNullModelProducesTDEmptyDiagram() {
        String result = service.exportShowcase(null);

        assertTrue(result.startsWith("flowchart TD"));
        assertTrue(result.contains("No data"));
    }

    @Test
    void showcaseUsesTDDirection() {
        var node = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.9, true, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.exportShowcase(model);

        assertTrue(result.startsWith("flowchart TD"));
    }

    @Test
    void showcaseSuppressesRootNodesWhenLeavesExist() {
        var root = new DiagramNode("CP", "Capabilities", "Capabilities", 0.92, true, 1);
        var leaf = new DiagramNode("CP-1023", "Communication Capabilities", "Capabilities", 0.85, true, 1);
        var model = new DiagramModel("Test", List.of(root, leaf), List.of(),
                new DiagramLayout("LR", true));

        String result = service.exportShowcase(model);

        assertTrue(result.contains("CP_1023"), "Leaf node should be present");
        // Root node "CP" should NOT appear as a standalone node declaration
        assertFalse(result.matches("(?s).*\\bCP\\[\"Capabilities.*"), "Root node should be suppressed");
    }

    @Test
    void showcaseKeepsRootWhenNoLeavesExist() {
        var root = new DiagramNode("CI", "COI Services", "COI Services", 0.74, false, 3);
        var model = new DiagramModel("Test", List.of(root), List.of(),
                new DiagramLayout("LR", true));

        String result = service.exportShowcase(model);

        assertTrue(result.contains("CI["), "Root-only layer should keep root node");
    }

    @Test
    void showcaseReroutesEdgesFromSuppressedRoots() {
        var rootCp = new DiagramNode("CP", "Capabilities", "Capabilities", 0.92, true, 1);
        var leafCp = new DiagramNode("CP-1023", "Comm Cap", "Capabilities", 0.85, true, 1);
        var rootCr = new DiagramNode("CR", "Core Services", "Core Services", 0.81, true, 3);
        var leafCr = new DiagramNode("CR-1047", "Infra Services", "Core Services", 0.75, true, 3);
        var edge = new DiagramEdge("e1", "CP", "CR", "REALIZES", 0.74);
        var model = new DiagramModel("Test",
                List.of(rootCp, leafCp, rootCr, leafCr), List.of(edge),
                new DiagramLayout("LR", true));

        String result = service.exportShowcase(model);

        // Edge should be re-routed from CP→CR to CP-1023→CR-1047 with localized label
        assertTrue(result.contains("CP_1023 -->|realizes| CR_1047"),
                "Edge should be re-routed through leaf nodes");
        assertFalse(result.contains("CP -->|"),
                "Original root-based edge should not appear");
    }

    @Test
    void showcaseLabelsOmitRedundantCodePrefix() {
        var node = new DiagramNode("CP-1023", "Communication Capabilities", "Capabilities", 0.85, true, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.exportShowcase(model);

        // Showcase mode uses label only (no code prefix) for cleaner display
        assertTrue(result.contains("Communication Capabilities ★ ⚠"));
    }

    @Test
    void showcaseDeduplicatesReroutedEdges() {
        var rootCp = new DiagramNode("CP", "Capabilities", "Capabilities", 0.9, true, 1);
        var leafCp = new DiagramNode("CP-1023", "Cap", "Capabilities", 0.85, true, 1);
        var rootCr = new DiagramNode("CR", "Core Services", "Core Services", 0.8, true, 3);
        var leafCr = new DiagramNode("CR-1047", "Svc", "Core Services", 0.75, true, 3);
        // Two different edges that re-route to the same pair
        var edge1 = new DiagramEdge("e1", "CP", "CR", "REALIZES", 0.7);
        var edge2 = new DiagramEdge("e2", "CP", "CR", "REALIZES", 0.6);
        var model = new DiagramModel("Test",
                List.of(rootCp, leafCp, rootCr, leafCr), List.of(edge1, edge2),
                new DiagramLayout("LR", true));

        String result = service.exportShowcase(model);

        // Count occurrences of the re-routed edge (now lowercase label)
        int count = result.split("CP_1023 -->\\|realizes\\| CR_1047", -1).length - 1;
        assertEquals(1, count, "Deduplicated edges should appear only once");
    }

    // ── Localization tests ──────────────────────────────────────────────

    @Test
    void exportWithGermanLabelsUsesLocalizedLayerNames() {
        var node = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.8, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model, MermaidLabels.german());

        assertTrue(result.contains("F\u00e4higkeiten"), "German layer label expected");
        assertFalse(result.contains("subgraph Capabilities[\"Capabilities\""),
                "English label should not appear in subgraph title");
    }

    @Test
    void exportWithGermanLabelsUsesLocalizedRelationLabels() {
        var node1 = new DiagramNode("CP-1023", "Cap A", "Capabilities", 0.8, false, 1);
        var node2 = new DiagramNode("CR-1047", "Svc B", "Core Services", 0.7, false, 3);
        var edge = new DiagramEdge("e1", "CP-1023", "CR-1047", "REALIZES", 0.75);
        var model = new DiagramModel("Test", List.of(node1, node2), List.of(edge),
                new DiagramLayout("LR", true));

        String result = service.export(model, MermaidLabels.german());

        assertTrue(result.contains("realisiert"), "German relation label expected");
    }

    @Test
    void showcaseWithGermanLabels() {
        var node = new DiagramNode("CP-1023", "Comm Cap", "Capabilities", 0.85, true, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = service.exportShowcase(model, MermaidLabels.german());

        assertTrue(result.contains("F\u00e4higkeiten"), "German layer label expected");
    }

    @Test
    void hotspotMarkerAppliedToHighRelevanceNodes() {
        var hot = new DiagramNode("CP-1023", "Hot Node", "Capabilities", 0.85, true, 1);
        var normal = new DiagramNode("CR-1047", "Normal Node", "Core Services", 0.5, false, 3);
        var model = new DiagramModel("Test", List.of(hot, normal), List.of(),
                new DiagramLayout("LR", true));

        String result = service.export(model);

        assertTrue(result.contains("Hot Node ★ ⚠"), "Hotspot marker expected on high-relevance node");
        assertFalse(result.contains("Normal Node ★"), "Normal node should not have anchor marker");
        assertFalse(result.contains("Normal Node") && result.contains("⚠ [50%]"),
                "Normal node should not have hotspot marker");
        assertTrue(result.contains("classDef hotspot"), "Hotspot class definition expected");
        assertTrue(result.contains("class CP_1023 hotspot"), "Hotspot class should be applied to hot node");
    }

    @Test
    void subgraphIdRemainsStableAcrossLocales() {
        var node = new DiagramNode("CP-1023", "Cap", "Capabilities", 0.8, false, 1);
        var model = new DiagramModel("Test", List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String english = service.export(model, MermaidLabels.english());
        String german = service.export(model, MermaidLabels.german());

        // Subgraph ID must be the same (stable) — only the display label changes
        assertTrue(english.contains("subgraph Capabilities[\"Capabilities\"]"));
        assertTrue(german.contains("subgraph Capabilities[\"F\u00e4higkeiten\"]"));
    }

    @Test
    void mermaidLabelsEnglishDefaults() {
        MermaidLabels labels = MermaidLabels.english();
        assertEquals("Capabilities", labels.layerLabel("Capabilities"));
        assertEquals("realizes", labels.relationLabel("REALIZES"));
        assertEquals("depends on", labels.relationLabel("DEPENDS_ON"));
        assertEquals("★", labels.anchorMarker());
        assertEquals("⚠", labels.hotspotMarker());
    }

    @Test
    void mermaidLabelsGermanDefaults() {
        MermaidLabels labels = MermaidLabels.german();
        assertEquals("F\u00e4higkeiten", labels.layerLabel("Capabilities"));
        assertEquals("Gesch\u00e4ftsprozesse", labels.layerLabel("Business Processes"));
        assertEquals("realisiert", labels.relationLabel("REALIZES"));
        assertEquals("unterst\u00fctzt", labels.relationLabel("SUPPORTS"));
    }

    @Test
    void mermaidLabelsFallbackForUnknownKeys() {
        MermaidLabels labels = MermaidLabels.english();
        assertEquals("Custom Layer", labels.layerLabel("Custom Layer"));
        assertEquals("custom type", labels.relationLabel("CUSTOM_TYPE"));
    }
}
