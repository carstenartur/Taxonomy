package com.taxonomy.export;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.visio.*;
import org.apache.poi.xdgf.usermodel.XmlVisioDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** File-format contracts; a desktop Visio move/save/reopen check is separate. */
class VisioEditableNativeTest {
    private static final String NS = "http://schemas.microsoft.com/office/visio/2012/main";

    @ParameterizedTest
    @CsvSource({"7,3,4,3,6,3,2,1", "3,6,3,3.5,3,5.5,4,3", "7,6,4,3,6,6,2,1", "0.5,3,2,3,1.5,3,1,2"})
    void endpointsDependOnRealBoundaryPoints(double targetX, double targetY,
            double beginX, double beginY, double endX, double endY, int fromPoint, int toPoint) throws Exception {
        var document = pair(targetX, targetY);
        var parts = VisioHandoffContractTest.parts(new VisioPackageBuilder().build(document));
        var page = parse(parts.get("visio/pages/page1.xml"));
        Element connector = shape(page, "3");
        assertThat(cell(connector, "BeginX").getAttribute("F"))
                .isEqualTo("PAR(PNT(Sheet.1!Connections.X" + fromPoint + ",Sheet.1!Connections.Y" + fromPoint + "))");
        assertThat(cell(connector, "BeginY").getAttribute("F")).isEqualTo(cell(connector, "BeginX").getAttribute("F"));
        assertThat(cell(connector, "EndX").getAttribute("F"))
                .isEqualTo("PAR(PNT(Sheet.2!Connections.X" + toPoint + ",Sheet.2!Connections.Y" + toPoint + "))");
        assertThat(cell(connector, "EndY").getAttribute("F")).isEqualTo(cell(connector, "EndX").getAttribute("F"));
        assertThat(number(connector, "BeginX")).isEqualTo(beginX);
        assertThat(number(connector, "BeginY")).isEqualTo(beginY);
        assertThat(number(connector, "EndX")).isEqualTo(endX);
        assertThat(number(connector, "EndY")).isEqualTo(endY);
        var glue = page.getElementsByTagNameNS(NS, "Connect");
        assertThat(((Element) glue.item(0)).getAttribute("ToCell")).isEqualTo("Connections.X" + fromPoint);
        assertThat(((Element) glue.item(1)).getAttribute("ToCell")).isEqualTo("Connections.X" + toPoint);
        var ports = section(shape(page, "1"), "Connection").getElementsByTagNameNS(NS, "Row");
        assertThat(ports.getLength()).isEqualTo(4);
        for (int index = 0; index < ports.getLength(); index++) {
            var port = (Element) ports.item(index);
            assertThat(cell(port, "X").getAttribute("F")).startsWith("Width*");
            assertThat(cell(port, "Y").getAttribute("F")).startsWith("Height*");
        }
    }

    @Test
    void embeddedMastersPreserveDeclaredTypesAndLoadInAnIndependentReader() throws Exception {
        var document = pair(7, 3);
        document.getPages().getFirst().getShapes().add(new VisioShape("9", "Information", 11, 3, 2, 1, "InformationProduct", false));
        document.getPages().getFirst().getShapes().add(new VisioShape("10", "A new concept", 15, 3, 2, 1, "Extension type", false));
        document.getPages().getFirst().getShapes().add(new VisioShape("11", "A literal custom type", 19, 3, 2, 1, "Evidence", false));
        byte[] bytes = new VisioPackageBuilder().build(document);
        var parts = VisioHandoffContractTest.parts(bytes);
        assertThat(parts).containsKeys("visio/masters/masters.xml", "visio/masters/_rels/masters.xml.rels",
                "visio/pages/_rels/page1.xml.rels");
        var page = parse(parts.get("visio/pages/page1.xml"));
        assertThat(shape(page, "1").getAttribute("Master")).isNotBlank();
        assertThat(shape(page, "9").getAttribute("Master")).isNotEqualTo(shape(page, "1").getAttribute("Master"));
        assertThat(shape(page, "10").getAttribute("Master")).isEqualTo(shape(page, "1").getAttribute("Master"));
        assertThat(shape(page, "11").getAttribute("Master")).isEqualTo(shape(page, "1").getAttribute("Master"));
        try (var reader = new XmlVisioDocument(new ByteArrayInputStream(bytes))) {
            var source = reader.getPages().iterator().next().getContent().getShapeById(1);
            assertThat(source.hasMaster()).isTrue();
            assertThat(source.getMasterShape()).isNotNull();
            assertThat(source.getGeometrySections()).isNotEmpty();
        }
    }

    @Test
    void visualContainersContainTheirMembersWithoutReinterpretingSemanticParents() throws Exception {
        var nodes = List.of(
                new DiagramNode("group", "Area", "Capabilities", 0, false, 0, 0, false, null, true),
                new DiagramNode("a", "Parent", "Capability", .5, false, 1, 1, false, "group", false),
                new DiagramNode("b", "Child", "UserApplication", .5, false, 2, 2, false, "a", false));
        var document = new VisioDiagramService().convert(new DiagramModel("Containers", nodes, List.of(), new DiagramLayout("LR", true)));
        var overview = document.getPages().getFirst();
        var group = overview.getShapes().stream().filter(s -> "group".equals(s.getProperties().get("taxonomy.id").value())).findFirst().orElseThrow();
        for (var member : overview.getShapes()) if (member != group) {
            assertThat(group.getX() - group.getWidth() / 2).isLessThan(member.getX() - member.getWidth() / 2);
            assertThat(group.getX() + group.getWidth() / 2).isGreaterThan(member.getX() + member.getWidth() / 2);
            assertThat(group.getY() - group.getHeight() / 2).isLessThan(member.getY() - member.getHeight() / 2);
            assertThat(group.getY() + group.getHeight() / 2).isGreaterThan(member.getY() + member.getHeight() / 2);
        }
        var xml = parse(VisioHandoffContractTest.parts(new VisioPackageBuilder().build(document)).get("visio/pages/page1.xml"));
        var nativeGroup = shape(xml, group.getId());
        assertThat(section(nativeGroup, "User")).isNotNull();
        assertThat(cell(nativeGroup, "Relationships").getAttribute("F"))
                .isEqualTo("SUM(DEPENDSON(1,Sheet.1!SheetRef(),Sheet.2!SheetRef()))");
        assertThat(cell(shape(xml, "1"), "Relationships").getAttribute("F")).isEqualTo("SUM(DEPENDSON(4,Sheet.3!SheetRef()))");
        assertThat(cell(shape(xml, "2"), "Relationships").getAttribute("F")).isEqualTo("SUM(DEPENDSON(4,Sheet.3!SheetRef()))");
        assertThat(section(shape(xml, "1"), "User")).isNull();
    }

    @Test
    void requirementHyperlinkRemainsLiteralAndCoexistsWithFullEvidence() throws Exception {
        var document = pair(7, 3);
        var properties = document.getPages().getFirst().getShapes().getFirst().getProperties();
        properties.put("taxonomy.requirementUrl", VisioProperty.text("https://taxonomy.example/context/projects/1/requirements/2"));
        properties.put("taxonomy.evidencePage", VisioProperty.text("Evidence 1"));
        var page = parse(VisioHandoffContractTest.parts(new VisioPackageBuilder().build(document)).get("visio/pages/page1.xml"));
        var hyperlinks = section(shape(page, "1"), "Hyperlink");
        assertThat(hyperlinks).isNotNull();
        assertThat(hyperlinks.getElementsByTagNameNS(NS, "Row").getLength()).isEqualTo(2);
        assertThat(hyperlinks.getElementsByTagNameNS(NS, "Cell"))
                .satisfies(cells -> {
                    boolean requirement = false;
                    for (int i = 0; i < cells.getLength(); i++) {
                        var c = (Element) cells.item(i);
                        assertThat(c.hasAttribute("F")).isFalse();
                        if (c.getAttribute("V").equals(properties.get("taxonomy.requirementUrl").value())) requirement = true;
                    }
                    assertThat(requirement).isTrue();
                });
    }

    @Test
    void unsafeRequirementLinksAndIncompleteAttachmentCannotPassValidation() throws Exception {
        for (String unsafe : List.of("javascript:alert(1)", "file:///etc/passwd", "https://user:secret@example.org/x", "//example.org/x",
                "https://example.org/x?token=secret", "https://example.org/x#secret", "https://example.org/a/../x")) {
            var document = pair(7, 3);
            document.getPages().getFirst().getShapes().getFirst().getProperties().put("taxonomy.requirementUrl", VisioProperty.text(unsafe));
            assertThatThrownBy(() -> new VisioPackageBuilder().build(document)).isInstanceOf(IllegalArgumentException.class);
        }
        var parts = new HashMap<>(VisioHandoffContractTest.parts(new VisioPackageBuilder().build(pair(7, 3))));
        String page = parts.get("visio/pages/page1.xml");
        // Removing the source dependency must fail even when the cached picture and Connect rows survive.
        parts.put("visio/pages/page1.xml", page.replaceAll("(N=\"BeginX\"[^>]*?) F=\"[^\"]+\"", "$1"));
        assertThatThrownBy(() -> VisioOpcValidator.validate(parts)).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"from-part", "to-part", "port-width-dependency"})
    void damagedNativePointContractsAreRejected(String corruption) throws Exception {
        var parts = new HashMap<>(VisioHandoffContractTest.parts(new VisioPackageBuilder().build(pair(7, 3))));
        var page = parse(parts.get("visio/pages/page1.xml"));
        var glue = (Element) page.getElementsByTagNameNS(NS, "Connect").item(0);
        switch (corruption) {
            case "from-part" -> glue.setAttribute("FromPart", "12");
            case "to-part" -> glue.setAttribute("ToPart", "100");
            case "port-width-dependency" -> {
                var right = (Element) section(shape(page, "1"), "Connection").getElementsByTagNameNS(NS, "Row").item(1);
                cell(right, "X").removeAttribute("F");
            }
            default -> throw new AssertionError(corruption);
        }
        parts.put("visio/pages/page1.xml", xml(page));
        assertThatThrownBy(() -> VisioOpcValidator.validate(parts))
                .as("Corrupt point-glue contract: %s", corruption)
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void nestedContainersHaveReciprocalMembershipAndExcludePresentationText() throws Exception {
        var document = new VisioDocument();
        var page = new VisioPage("0", "Nested containers");
        page.getShapes().add(identified("1", "outer", null, true));
        page.getShapes().add(identified("2", "inner", "outer", true));
        page.getShapes().add(identified("3", "leaf", "inner", false));
        var presentation = identified("4", "note", "outer", false);
        presentation.getProperties().put("taxonomy.presentationOnly", VisioProperty.bool(true));
        page.getShapes().add(presentation);
        document.getPages().add(page);
        assertThat(VisioContainers.membership(page).owners()).containsExactlyInAnyOrderEntriesOf(Map.of("2", "1", "3", "2"));
        VisioContainers.fit(page);
        var parts = new HashMap<>(VisioHandoffContractTest.parts(new VisioPackageBuilder().build(document)));
        var xml = parse(parts.get("visio/pages/page1.xml"));
        assertThat(cell(shape(xml, "1"), "Relationships").getAttribute("F")).isEqualTo("SUM(DEPENDSON(1,Sheet.2!SheetRef()))");
        assertThat(cell(shape(xml, "2"), "Relationships").getAttribute("F"))
                .isEqualTo("SUM(DEPENDSON(1,Sheet.3!SheetRef()),DEPENDSON(4,Sheet.1!SheetRef()))");
        assertThat(cell(shape(xml, "3"), "Relationships").getAttribute("F")).isEqualTo("SUM(DEPENDSON(4,Sheet.2!SheetRef()))");
        assertThat(shape(xml, "4").hasAttribute("Master")).isFalse();
        // A cached diagram remains possible after this corruption, but a native
        // owner/member relationship must be reciprocal to be editable as a container.
        shape(xml, "3").removeChild(cell(shape(xml, "3"), "Relationships"));
        parts.put("visio/pages/page1.xml", xml(xml));
        assertThatThrownBy(() -> VisioOpcValidator.validate(parts)).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void missingParentDoesNotInventMembershipAndParentCyclesAreRejected() {
        var page = new VisioPage("0", "Partial hierarchy");
        page.getShapes().add(identified("1", "present", "absent", false));
        assertThat(VisioContainers.membership(page).owners()).isEmpty();
        page.getShapes().add(identified("2", "absent", "present", true));
        assertThatThrownBy(() -> VisioContainers.membership(page)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }

    @Test
    void parallelAndReverseRelationshipsRetainIndependentNativeConnectors() throws Exception {
        var document = pair(7, 3);
        document.getPages().getFirst().getConnects().add(new VisioConnect("1", "2", "SUPPORTS"));
        document.getPages().getFirst().getConnects().add(new VisioConnect("2", "1", "REALIZES"));
        var page = parse(VisioHandoffContractTest.parts(new VisioPackageBuilder().build(document)).get("visio/pages/page1.xml"));
        assertThat(page.getElementsByTagNameNS(NS, "Connect").getLength()).isEqualTo(6);
        assertThat(cell(shape(page, "3"), "BeginX").getAttribute("F")).isEqualTo(cell(shape(page, "4"), "BeginX").getAttribute("F"));
        assertThat(cell(shape(page, "3"), "EndX").getAttribute("F")).isEqualTo(cell(shape(page, "5"), "BeginX").getAttribute("F"));
    }

    private static VisioShape identified(String id, String canonicalId, String parent, boolean container) {
        var shape = new VisioShape(id, canonicalId, 3, 3, 2, 1, "Capability", false);
        shape.getProperties().put("taxonomy.id", VisioProperty.text(canonicalId));
        shape.getProperties().put("taxonomy.container", VisioProperty.bool(container));
        if (parent != null) shape.getProperties().put("taxonomy.parentId", VisioProperty.text(parent));
        return shape;
    }

    private static String xml(Document document) throws Exception {
        var writer = new StringWriter();
        var factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        factory.newTransformer().transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private static VisioDocument pair(double targetX, double targetY) {
        var document = new VisioDocument();
        var page = new VisioPage("0", "Attached objects");
        page.getShapes().add(new VisioShape("1", "Source", 3, 3, 2, 1, "Capability", false));
        page.getShapes().add(new VisioShape("2", "Target", targetX, targetY, 2, 1, "UserApplication", false));
        page.getConnects().add(new VisioConnect("1", "2", "USES"));
        document.getPages().add(page);
        return document;
    }

    private static Document parse(String value) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element shape(Document document, String id) {
        var shapes = document.getElementsByTagNameNS(NS, "Shape");
        for (int i = 0; i < shapes.getLength(); i++) if (((Element) shapes.item(i)).getAttribute("ID").equals(id)) return (Element) shapes.item(i);
        throw new AssertionError("Shape absent: " + id);
    }

    private static Element section(Element shape, String name) {
        var children = shape.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) if (children.item(i) instanceof Element e && e.getLocalName().equals("Section") && e.getAttribute("N").equals(name)) return e;
        return null;
    }

    private static Element cell(Element shape, String name) {
        var children = shape.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) if (children.item(i) instanceof Element e && e.getLocalName().equals("Cell") && e.getAttribute("N").equals(name)) return e;
        throw new AssertionError("Cell absent: " + name);
    }

    private static double number(Element shape, String name) { return Double.parseDouble(cell(shape, name).getAttribute("V")); }
}
