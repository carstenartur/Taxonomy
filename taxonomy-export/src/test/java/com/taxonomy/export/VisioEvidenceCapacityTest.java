package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.visio.*;
import org.apache.poi.xdgf.usermodel.XmlVisioDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class VisioEvidenceCapacityTest {
    @Test
    void completeReasonsExceedTheOldPageAndOpcLimitsWithoutLosingGraphOrText() throws Exception {
        var nodes = new ArrayList<DiagramNode>();
        var metadata = new LinkedHashMap<String, Map<String, VisioProperty>>();
        for (int i = 0; i < 60; i++) {
            String id = "N" + i;
            nodes.add(new DiagramNode(id, "Node " + i, "Capability", .5, false, i % 3));
            metadata.put(id, Map.of("taxonomy.presenceReason", VisioProperty.text(
                    java.util.stream.IntStream.range(0, 140)
                            .mapToObj(item -> "Recorded justification for " + id + " item " + item + ". ")
                            .collect(Collectors.joining()) + "END-" + id)));
        }
        var document = new VisioDiagramService().convert(
                new DiagramModel("Complete evidence", nodes,
                        List.of(new DiagramEdge("R", "N0", "N1", "USES", .5)), new DiagramLayout("LR", false)),
                new VisioExportMetadata(Map.of(), metadata, Map.of(), List.of()));
        assertTrue(document.getPages().size() > 52, "Exercise both the old 32-page and 64-part limits");
        assertEquals(nodes.size(), document.getPages().getFirst().getShapes().size());
        assertEquals("1", document.getProperties().get("taxonomy.detailRelationshipsCovered").value(),
                "Mandatory evidence must not consume optional diagram-page capacity");
        String visible = document.getPages().stream().filter(VisioPage::isEvidencePage)
                .flatMap(page -> page.getShapes().stream()).map(VisioShape::getText)
                .collect(Collectors.joining("\n")).replaceAll("\\s+", "");
        metadata.values().forEach(values -> assertTrue(visible.contains(
                values.get("taxonomy.presenceReason").value().replaceAll("\\s+", ""))));
        byte[] bytes = new VisioPackageBuilder().build(document);
        var parts = VisioHandoffContractTest.bytes(bytes);
        assertTrue(parts.size() > 64);
        assertTrue(parts.values().stream().mapToLong(value -> value.length).sum() <= VisioHandoffProfile.MAX_XML_BYTES);
        try (var reader = new XmlVisioDocument(new ByteArrayInputStream(bytes))) {
            assertEquals(document.getPages().size(), reader.getPages().size());
        }
    }

    @Test
    void diagramAndEvidenceBudgetsRemainIndependentlyBounded() throws Exception {
        var document = new VisioDocument();
        for (int i = 0; i < VisioPackageValidator.MAX_DIAGRAM_PAGES; i++) {
            var page = new VisioPage(Integer.toString(i), "Diagram " + i);
            page.getShapes().add(new VisioShape("1", "Graph node", 2, 2, 2, 1, "Capability", false));
            document.getPages().add(page);
        }
        for (int i = 0; i < VisioPackageValidator.MAX_EVIDENCE_PAGES; i++) {
            var page = new VisioPage(Integer.toString(document.getPages().size()), "Evidence " + i);
            page.setEvidencePage(true);
            var text = new VisioShape("1", "Complete evidence", 2, 2, 2, 1, "Evidence", false);
            text.getProperties().put("taxonomy.presentationOnly", VisioProperty.bool(true));
            page.getShapes().add(text);
            document.getPages().add(page);
        }
        byte[] bytes = new VisioPackageBuilder().build(document);
        assertEquals(VisioPackageValidator.MAX_OPC_PARTS, VisioHandoffContractTest.bytes(bytes).size());
        document.getPages().removeLast();
        document.getPages().add(new VisioPage("159", "Excess diagram"));
        assertThrows(IllegalArgumentException.class, () -> new VisioPackageBuilder().build(document));
        document.getPages().removeLast();
        document.getPages().removeIf(page -> !page.isEvidencePage());
        for (int i = 0; i < 2; i++) {
            var page = new VisioPage(Integer.toString(200 + i), "Excess evidence " + i);
            page.setEvidencePage(true);
            document.getPages().add(page);
        }
        assertThrows(IllegalArgumentException.class, () -> new VisioPackageBuilder().build(document));
    }

    @Test
    void graphShapesCannotUseTheEvidenceBudget() {
        var document = new VisioDocument();
        var page = new VisioPage("0", "Not an evidence page");
        page.setEvidencePage(true);
        page.getShapes().add(new VisioShape("1", "Graph node", 2, 2, 2, 1, "Capability", false));
        document.getPages().add(page);
        assertThrows(IllegalArgumentException.class, () -> new VisioPackageBuilder().build(document));
        var shape = page.getShapes().getFirst();
        shape.getProperties().put("taxonomy.presentationOnly", VisioProperty.bool(true));
        shape.getProperties().put("taxonomy.id", VisioProperty.text("graph-node"));
        assertThrows(IllegalArgumentException.class, () -> new VisioPackageBuilder().build(document),
                "Presentation markers must not let graph objects bypass the diagram-page budget");
    }

    @Test
    void declaredProfileMatchesTheIndependentValidationBudgets() throws Exception {
        var limits = new com.fasterxml.jackson.databind.json.JsonMapper()
                .readTree(VisioHandoffProfile.profileJson()).path("limits");
        assertEquals(VisioPackageValidator.MAX_PAGES, limits.path("pages").asInt());
        assertEquals(VisioPackageValidator.MAX_DIAGRAM_PAGES, limits.path("diagramPages").asInt());
        assertEquals(VisioPackageValidator.MAX_EVIDENCE_PAGES, limits.path("evidencePages").asInt());
        assertEquals(VisioPackageValidator.MAX_OPC_PARTS, limits.path("packageParts").asInt());
        assertEquals(VisioHandoffProfile.MAX_XML_BYTES, limits.path("xmlBytes").asInt());
    }
}
