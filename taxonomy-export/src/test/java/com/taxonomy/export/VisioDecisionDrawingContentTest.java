package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.visio.*;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class VisioDecisionDrawingContentTest {
    private final Assignment payroll = new Assignment("U1", "Payroll", "BP-1");
    private final VisioDecisionTree tree = new VisioDecisionTree(List.of(
            new VisioDecisionTree.Node(0, "BP", "Processes", 90d, "Recorded branch reason.", null),
            new VisioDecisionTree.Node(1, "BP-1", "Time capture", 0d, "The saved explanation.", payroll),
            new VisioDecisionTree.Node(0, "BR", "Roles", null, null, null)), true);
    private final VisioExportMetadata metadata = new VisioExportMetadata(
            Map.of("taxonomy.requirementText", VisioProperty.text("Full source.\nSecond paragraph."),
                    "taxonomy.snapshotId", VisioProperty.text("snapshot-1")),
            Map.of(), Map.of(), List.of());

    private DiagramModel graph(boolean withArchitecture) {
        return new DiagramModel("Überblick", withArchitecture ? List.of(
                new DiagramNode("BP-1", "Time capture", "BP", 0, true, 0),
                new DiagramNode("UA-2", "Payroll application", "UA", .6, false, 1)) : List.of(),
                withArchitecture ? List.of(new DiagramEdge("edge-1", "BP-1", "UA-2", "USES", .4)) : List.of(),
                new DiagramLayout("LR", false));
    }

    @Test
    void screenContainsOnlyTheSelectedDrawingsAndCarriesSourceOnItsFirstPage() {
        for (boolean withArchitecture : List.of(true, false)) {
            var document = new VisioDiagramService().convert(graph(withArchitecture), metadata, tree, VisioDrawingOptions.screen(true));
            var names = document.getPages().stream().map(VisioPage::getName).toList();
            assertEquals(withArchitecture ? List.of("Überblick", "Entscheidungsbaum – BP", "Entscheidungsbaum – BR")
                    : List.of("Entscheidungsbaum – BP", "Entscheidungsbaum – BR"), names);
            var sourceShapes = document.getPages().stream().flatMap(page -> page.getShapes().stream())
                    .filter(shape -> shape.getText().equals("Anforderung")).toList();
            assertEquals(1, sourceShapes.size());
            var source = sourceShapes.getFirst();
            assertTrue(document.getPages().getFirst().getShapes().contains(source));
            assertEquals("Full source.\nSecond paragraph.", source.getProperties().get("taxonomy.requirementText").value());
            assertEquals("snapshot-1", source.getProperties().get("taxonomy.snapshotId").value());
            assertTrue(document.getPages().stream().flatMap(page -> page.getShapes().stream())
                    .noneMatch(shape -> shape.getProperties().containsKey("taxonomy.evidencePage")));
        }
    }

    @Test
    void compactCaptionsPreserveCanonicalValuesAndTheOriginalHierarchy() {
        var document = new VisioDiagramService().convert(graph(true), metadata, tree, VisioDrawingOptions.screen(true));
        var overview = document.getPages().getFirst();
        var architectureNode = overview.getShapes().getFirst();
        assertTrue(architectureNode.getText().contains("Time capture"));
        assertTrue(architectureNode.getText().contains("0%"));
        assertFalse(architectureNode.getText().contains("The saved explanation."));
        assertEquals("The saved explanation.", architectureNode.getProperties().get("taxonomy.presenceReason").value());
        assertEquals("Payroll", architectureNode.getProperties().get("taxonomy.responsibilityName").value());
        var relation = overview.getConnects().getFirst();
        assertEquals("nutzt", relation.getTextBox().text());
        assertEquals("USES", relation.getProperties().get("taxonomy.type").value());
        assertEquals("Time capture", relation.getProperties().get("taxonomy.sourceLabel").value());
        assertEquals("Payroll application", relation.getProperties().get("taxonomy.targetLabel").value());
        var decision = document.getPages().get(1);
        var child = decision.getShapes().get(1);
        assertEquals("BP", child.getProperties().get("taxonomy.parentId").value());
        assertEquals("0", child.getProperties().get("taxonomy.relevance").value());
        assertEquals("The saved explanation.", child.getProperties().get("taxonomy.presenceReason").value());
        var hierarchy = decision.getConnects().getFirst().getProperties();
        assertEquals("DECISION_HIERARCHY", hierarchy.get("taxonomy.type").value());
        assertFalse(hierarchy.containsKey("taxonomy.derivationReason"));
        var pending = document.getPages().get(2).getShapes().getFirst();
        assertTrue(pending.getText().contains("Nicht bewertet"));
        assertFalse(pending.getProperties().containsKey("taxonomy.relevance"));
    }

    @Test
    void wallDrawingsShowTheUnitAndOnlyShowReasonExcerptsWhenRequested() {
        for (boolean reasons : List.of(false, true)) {
            var options = new VisioDrawingOptions(VisioDrawingOptions.Mode.WALL, VisioDrawingOptions.Paper.A1,
                    false, reasons, true);
            var document = new VisioDiagramService().convert(graph(true), metadata, tree, options);
            var architecture = document.getPages().getFirst().getShapes().getFirst();
            var decision = document.getPages().get(1).getShapes().get(1);
            for (VisioShape node : List.of(architecture, decision)) {
                assertTrue(node.getText().contains("Payroll"));
                assertEquals(reasons, node.getText().contains("The saved explanation."));
                assertEquals("The saved explanation.", node.getProperties().get("taxonomy.presenceReason").value());
            }
        }
    }

    @Test
    void appendixIsOptInAndSourceShapeLinksToAnExistingRequirementPage() {
        var options = new VisioDrawingOptions(VisioDrawingOptions.Mode.SCREEN, VisioDrawingOptions.Paper.A1,
                true, false, true);
        var input = new VisioExportMetadata(metadata.document(), Map.of("UA-2",
                Map.of("taxonomy.presenceReason", VisioProperty.text("Recorded architecture reason."))), Map.of(), List.of());
        var document = new VisioDiagramService().convert(graph(true), input, tree, options);
        var source = document.getPages().getFirst().getShapes().stream()
                .filter(shape -> shape.getText().equals("Anforderung")).findFirst().orElseThrow();
        var target = source.getProperties().get("taxonomy.evidencePage").value();
        assertTrue(document.getPages().stream().anyMatch(page -> page.isEvidencePage() && page.getName().equals(target)));
        assertTrue(document.getPages().stream().anyMatch(page -> page.getName().startsWith("Nachweise ")));
    }

    @Test
    void taxonomyOnlyAppendixContainsPrintableOriginalDecisionReasons() {
        var options = new VisioDrawingOptions(VisioDrawingOptions.Mode.SCREEN, VisioDrawingOptions.Paper.A1,
                true, false, true);
        var document = new VisioDiagramService().convert(graph(false), metadata, tree, options);
        var child = document.getPages().getFirst().getShapes().get(1);
        var evidenceLink = child.getProperties().get("taxonomy.evidencePage");
        assertNotNull(evidenceLink, "The original reason must be readable in the selected appendix");
        var reasonPage = document.getPages().stream().filter(page -> page.getName().equals(evidenceLink.value()))
                .findFirst().orElseThrow();
        assertTrue(reasonPage.getShapes().stream().anyMatch(shape -> shape.getText().contains("The saved explanation.")));
    }

    @Test
    void oneReportLanguageControlsDrawingNamesAndPendingScores() {
        var document = new VisioDiagramService().convert(graph(false), metadata, tree, VisioDrawingOptions.screen(false));
        var pending = document.getPages().get(1);
        assertEquals("Decision tree – BR", pending.getName());
        assertTrue(pending.getShapes().getFirst().getText().contains("Not evaluated"));
        assertTrue(document.getPages().getFirst().getShapes().stream().anyMatch(shape -> shape.getText().equals("Requirement")));
    }

    @Test
    void denseWallTreesUseLandscapeWidthAndKeepTextReadableAtA1() {
        var rows = new java.util.ArrayList<VisioDecisionTree.Node>();
        rows.add(new VisioDecisionTree.Node(0, "CR", "Core services", 70d, "Root rationale", null));
        rows.add(new VisioDecisionTree.Node(1, "CR-1", "Infrastructure", 70d, "Infrastructure rationale", null));
        for (int i = 0; i < 32; i++) {
            rows.add(new VisioDecisionTree.Node(2, "CR-" + (100 + i), "Service application " + i, 50d,
                    "Recorded reason for the assessed service.", i % 4 == 0 ? payroll : null));
        }
        var options = new VisioDrawingOptions(VisioDrawingOptions.Mode.WALL, VisioDrawingOptions.Paper.A1, false, true, true);
        var document = new VisioDiagramService().convert(graph(false), metadata, new VisioDecisionTree(rows, true), options);
        var shapes = document.getPages().getFirst().getShapes();
        double top = shapes.stream().mapToDouble(shape -> shape.getY() + shape.getHeight() / 2).max().orElseThrow();
        double bottom = shapes.stream().mapToDouble(shape -> shape.getY() - shape.getHeight() / 2).min().orElseThrow();
        double right = shapes.stream().mapToDouble(shape -> shape.getX() + shape.getWidth() / 2).max().orElseThrow();
        double left = shapes.stream().mapToDouble(shape -> shape.getX() - shape.getWidth() / 2).min().orElseThrow();
        // ISO A1 landscape, with three percent of sheet height reserved at each side.
        double scale = Math.min(31.71 / (right - left), 21.98 / (top - bottom));
        assertTrue(12 * scale >= 10, "Dense wall tree print size is " + 12 * scale + " pt");
        var leaves = shapes.stream().filter(shape -> VisioProperty.number(2).equals(shape.getProperties().get("taxonomy.depth")))
                .sorted(java.util.Comparator.comparingDouble(VisioShape::getY)).toList();
        assertEquals(32, leaves.size());
        for (int i = 1; i < leaves.size(); i++) {
            var previous = leaves.get(i - 1);
            var next = leaves.get(i);
            assertTrue(previous.getY() + previous.getHeight() / 2 < next.getY() - next.getHeight() / 2);
        }
    }

    @Test
    void wallInformationProductTitlesStayOutsideTheNativeFold() throws Exception {
        String title = "Airborne Early Warning Status of Forces Reports";
        String finalWord = "TitleEndMarker";
        String longTitle = "Interdepartmental information exchange, verification, and release coordination. ".repeat(6) + finalWord;
        var informationProducts = new VisioDecisionTree(List.of(
                new VisioDecisionTree.Node(0, "IP", "Information Products", 100d, null, null),
                new VisioDecisionTree.Node(1, "IP-1772", title, null,
                        "Die gespeicherte Begründung beschreibt eine überprüfbare Übergabe und ihre Bedingung.", payroll),
                new VisioDecisionTree.Node(1, "IP-long-title-fixture", longTitle, 100d, null, null)), true);
        var options = new VisioDrawingOptions(VisioDrawingOptions.Mode.WALL, VisioDrawingOptions.Paper.A1,
                false, true, true);
        var drawing = new VisioDiagramService().convert(graph(false), metadata, informationProducts, options);
        var parts = VisioHandoffContractTest.parts(new VisioPackageBuilder().build(drawing));
        var page = DocumentBuilderFactory.newNSInstance().newDocumentBuilder().parse(new ByteArrayInputStream(
                parts.get("visio/pages/page1.xml").getBytes(StandardCharsets.UTF_8)));
        var shapes = page.getElementsByTagNameNS("*", "Shape");
        var elements = IntStream.range(0, shapes.getLength()).mapToObj(index -> (Element) shapes.item(index)).toList();
        var product = elements.stream().filter(shape -> shape.getAttribute("Name").startsWith("IP-1772 · "))
                .findFirst().orElseThrow();
        String visible = product.getElementsByTagNameNS("*", "Text").item(0).getTextContent();
        assertTrue(visible.startsWith("IP-1772"));
        assertTrue(visible.replaceAll("\\s+", " ").contains(title));
        var longProduct = elements.stream().filter(shape -> shape.getAttribute("Name").startsWith("IP-long-title-fixture · "))
                .findFirst().orElseThrow();
        String longVisible = longProduct.getElementsByTagNameNS("*", "Text").item(0).getTextContent();
        assertTrue(longVisible.lines().count() > 3, "The authored fixture must exercise more than three title lines");
        assertTrue(longVisible.stripTrailing().endsWith(finalWord));
        assertTrue(longVisible.replaceAll("\\s+", " ").contains(longTitle));
        assertEquals("5", product.getAttribute("Master"), "Keep the native Information master");
        var cells = product.getElementsByTagNameNS("*", "Cell");
        double width = 0, rightMargin = 0;
        for (int index = 0; index < cells.getLength(); index++) {
            var cell = (Element) cells.item(index);
            if (cell.getAttribute("N").equals("Width")) width = Double.parseDouble(cell.getAttribute("V"));
            if (cell.getAttribute("N").equals("RightMargin")) {
                rightMargin = Double.parseDouble(cell.getAttribute("V"));
                assertTrue(cell.getAttribute("F").startsWith("Width*0.12+"), "The fold inset must follow shape resizing");
            }
        }
        assertTrue(width > 0);
        assertTrue(rightMargin >= width * .12, "The visible text area must end before the native fold");
    }

    @Test
    void supplementalShapeDataCannotDisplaceRecordedFieldsAtTheNativeLimit() {
        var nodeData = new java.util.HashMap<String, VisioProperty>();
        var edgeData = new java.util.HashMap<String, VisioProperty>();
        for (int i = 0; i < 53; i++) nodeData.put("taxonomy.recorded" + i, VisioProperty.text("note " + i));
        for (int i = 0; i < 56; i++) edgeData.put("taxonomy.recorded" + i, VisioProperty.text("note " + i));
        var input = new VisioExportMetadata(metadata.document(), Map.of("BP-1", nodeData), Map.of("edge-1", edgeData), List.of());
        var document = new VisioDiagramService().convert(graph(true), input, tree, VisioDrawingOptions.screen(true));
        VisioPackageValidator.validate(document);
        var shape = document.getPages().getFirst().getShapes().getFirst();
        var edge = document.getPages().getFirst().getConnects().getFirst();
        nodeData.forEach((key, val) -> assertEquals(val, shape.getProperties().get(key)));
        edgeData.forEach((key, val) -> assertEquals(val, edge.getProperties().get(key)));
        assertEquals("BP-1", edge.getProperties().get("taxonomy.sourceId").value());
        assertEquals("Time capture", shape.getProperties().get("taxonomy.label").value());
        var decision = document.getPages().get(1).getShapes().get(1);
        assertEquals("The saved explanation.", decision.getProperties().get("taxonomy.presenceReason").value());
        assertEquals("Payroll", decision.getProperties().get("taxonomy.responsibilityName").value());
    }
}
