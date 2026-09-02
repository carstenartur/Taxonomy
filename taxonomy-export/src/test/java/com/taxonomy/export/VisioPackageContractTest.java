package com.taxonomy.export;

import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisioPackageContractTest {

    private static final String VISIO_NS =
            "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String PACKAGE_REL_NS =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CONTENT_TYPES_NS =
            "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String CORE_PROPERTIES_NS =
            "http://schemas.openxmlformats.org/package/2006/metadata/core-properties";
    private static final String DUBLIN_CORE_NS =
            "http://purl.org/dc/elements/1.1/";

    private final VisioPackageBuilder builder = new VisioPackageBuilder();

    @Test
    void pageContentsUsesSchemaCollectionsAndMasterlessGeometry() throws Exception {
        Document page = parseXml(entry(
                builder.build(VisioPackageBuilderTest.representativeDocument()),
                "visio/pages/page1.xml"));

        Element root = page.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("PageContents");
        assertThat(root.getNamespaceURI()).isEqualTo(VISIO_NS);
        assertThat(directElementNames(root)).containsExactly("Shapes", "Connects");

        Element shapes = directChild(root, "Shapes");
        List<Element> shapeElements = directChildren(shapes, "Shape");
        assertThat(shapeElements).hasSize(3);
        assertThat(shapeElements)
                .allSatisfy(shape -> assertThat(shape.getAttribute("ID"))
                        .matches("[1-9][0-9]*"));

        Element connector = shapeById(shapeElements, "43");
        assertThat(connector.hasAttribute("Master")).isFalse();
        assertThat(cellValue(connector, "OneD")).isEqualTo("1");
        assertThat(cellValue(connector, "ObjType")).isEqualTo("2");
        assertThat(cellValue(connector, "GlueType")).isEqualTo("2");
        assertThat(cellValue(connector, "BeginX")).isEqualTo("1.5");
        assertThat(cellValue(connector, "BeginY")).isEqualTo("2");
        assertThat(cellValue(connector, "EndX")).isEqualTo("4.5");
        assertThat(cellValue(connector, "EndY")).isEqualTo("2");
        assertThat(cellValue(connector, "Width")).isEqualTo("3");
        assertThat(cellValue(connector, "Height")).isEqualTo("0");
        assertThat(cellValue(connector, "Angle")).isEqualTo("0");
        assertThat(directChildren(connector, "Section"))
                .anySatisfy(section -> assertThat(section.getAttribute("N"))
                        .isEqualTo("Geometry"));

        Element capability = shapeById(shapeElements, "10");
        assertThat(directChildren(capability, "Section"))
                .anySatisfy(section -> assertThat(section.getAttribute("N"))
                        .isEqualTo("Geometry"));
        assertThat(directChild(capability, "Text").getTextContent())
                .isEqualTo("Capability A");

        Element connects = directChild(root, "Connects");
        List<Element> glueEntries = directChildren(connects, "Connect");
        assertThat(glueEntries).hasSize(2);
        assertThat(glueEntries)
                .extracting(connect -> connect.getAttribute("FromSheet")
                        + "->" + connect.getAttribute("ToSheet"))
                .containsExactly("43->10", "43->42");
    }

    @Test
    void packageUsesStandardCorePropertiesInsteadOfLegacyInlineProperties()
            throws Exception {
        byte[] packageBytes = builder.build(
                VisioPackageBuilderTest.representativeDocument());

        Document mainDocument = parseXml(entry(packageBytes, "visio/document.xml"));
        assertThat(mainDocument.getDocumentElement().getLocalName())
                .isEqualTo("VisioDocument");
        assertThat(mainDocument.getElementsByTagNameNS(
                VISIO_NS, "DocumentProperties").getLength()).isZero();
        assertThat(mainDocument.getElementsByTagNameNS(
                VISIO_NS, "Description").getLength()).isZero();

        Document properties = parseXml(entry(packageBytes, "docProps/core.xml"));
        assertThat(properties.getDocumentElement().getNamespaceURI())
                .isEqualTo(CORE_PROPERTIES_NS);
        assertThat(properties.getElementsByTagNameNS(
                DUBLIN_CORE_NS, "title").item(0).getTextContent())
                .isEqualTo("Architecture");
        assertThat(properties.getElementsByTagNameNS(
                DUBLIN_CORE_NS, "creator").item(0).getTextContent())
                .isEqualTo("Taxonomy Architecture Analyzer");
    }

    @Test
    void everyPackageRelationshipTargetAndContentTypeResolves() throws Exception {
        Map<String, byte[]> parts = unzip(
                builder.build(VisioPackageBuilderTest.representativeDocument()));

        assertRelationshipTargetsResolve(parts, "_rels/.rels", "");
        assertRelationshipTargetsResolve(
                parts, "visio/_rels/document.xml.rels", "visio/");
        assertRelationshipTargetsResolve(
                parts, "visio/pages/_rels/pages.xml.rels", "visio/pages/");

        Document contentTypes = parseXml(parts.get("[Content_Types].xml"));
        List<String> overrides = elements(
                contentTypes.getElementsByTagNameNS(CONTENT_TYPES_NS, "Override"))
                .stream()
                .map(element -> element.getAttribute("PartName"))
                .toList();

        assertThat(overrides).contains(
                "/docProps/core.xml",
                "/visio/document.xml",
                "/visio/pages/pages.xml",
                "/visio/pages/page1.xml");
        assertThat(overrides)
                .allSatisfy(partName -> assertThat(parts).containsKey(
                        partName.substring(1)));
    }

    @Test
    void pageIndexDeclaresUsablePageBounds() throws Exception {
        Document pages = parseXml(entry(
                builder.build(VisioPackageBuilderTest.representativeDocument()),
                "visio/pages/pages.xml"));

        Element page = (Element) pages.getElementsByTagNameNS(
                VISIO_NS, "Page").item(0);
        Element pageSheet = directChild(page, "PageSheet");

        assertThat(Double.parseDouble(cellValue(pageSheet, "PageWidth")))
                .isGreaterThanOrEqualTo(11.0);
        assertThat(Double.parseDouble(cellValue(pageSheet, "PageHeight")))
                .isGreaterThanOrEqualTo(8.5);
        assertThat(cellValue(pageSheet, "PageScale")).isEqualTo("1");
        assertThat(cellValue(pageSheet, "DrawingScale")).isEqualTo("1");
    }

    @Test
    void repeatedBuildIsByteForByteDeterministic() throws Exception {
        VisioDocument document = VisioPackageBuilderTest.representativeDocument();

        byte[] first = builder.build(document);
        byte[] second = builder.build(document);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void rejectsNonNumericAndDuplicateShapeIds() {
        VisioDocument nonNumeric = documentWithShapes(
                new VisioShape("node-a", "A", 1, 1, 1, 1, "A", false));
        assertThatThrownBy(() -> builder.build(nonNumeric))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive numeric Visio ID");

        VisioDocument duplicate = documentWithShapes(
                new VisioShape("1", "A", 1, 1, 1, 1, "A", false),
                new VisioShape("1", "B", 3, 1, 1, 1, "B", false));
        assertThatThrownBy(() -> builder.build(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate Visio shape ID 1");
    }

    @Test
    void rejectsUnresolvedConnectorEndpoints() {
        VisioDocument document = documentWithShapes(
                new VisioShape("1", "A", 1, 1, 1, 1, "A", false));
        document.getPages().get(0).getConnects()
                .add(new VisioConnect("1", "2", "USES"));

        assertThatThrownBy(() -> builder.build(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing target shape 2");
    }

    @Test
    void rejectsCollapsedOrOverflowingDerivedGeometry() {
        VisioDocument coincident = documentWithShapes(
                new VisioShape("1", "A", 1, 1, 1, 1, "A", false),
                new VisioShape("2", "B", 1, 1, 1, 1, "B", false));
        coincident.getPages().get(0).getConnects()
                .add(new VisioConnect("1", "2", "USES"));

        assertThatThrownBy(() -> builder.build(coincident))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Straight-line length of connector 0")
                .hasMessageContaining("finite and positive");

        VisioDocument connectorOverflow = documentWithShapes(
                new VisioShape("1", "A", 0, 0, 1, 1, "A", false),
                new VisioShape(
                        "2", "B", Double.MAX_VALUE, Double.MAX_VALUE,
                        1, 1, "B", false));
        connectorOverflow.getPages().get(0).getConnects()
                .add(new VisioConnect("1", "2", "USES"));

        assertThatThrownBy(() -> builder.build(connectorOverflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Straight-line length of connector 0")
                .hasMessageContaining("finite and positive");

        VisioDocument pageOverflow = documentWithShapes(
                new VisioShape(
                        "1", "A", Double.MAX_VALUE, 1,
                        Double.MAX_VALUE, 1, "A", false));

        assertThatThrownBy(() -> builder.build(pageOverflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Right page extent of shape 1")
                .hasMessageContaining("finite and positive");
    }

    @Test
    void rejectsUnsafeXmlTextAndInvalidGeometry() {
        String unsafe = "Unsafe" + Character.toString(0x1) + "text";
        VisioDocument unsafeText = documentWithShapes(
                new VisioShape("1", unsafe, 1, 1, 1, 1, "A", false));
        assertThatThrownBy(() -> builder.build(unsafeText))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML 1.0 control");

        VisioDocument invalidGeometry = documentWithShapes(
                new VisioShape("1", "A", Double.NaN, 1, 1, 1, "A", false));
        assertThatThrownBy(() -> builder.build(invalidGeometry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PinX")
                .hasMessageContaining("finite");
    }

    private static VisioDocument documentWithShapes(VisioShape... shapes) {
        VisioDocument document = new VisioDocument();
        VisioPage page = new VisioPage("page-1", "Architecture");
        page.getShapes().addAll(Arrays.asList(shapes));
        document.getPages().add(page);
        return document;
    }

    private static void assertRelationshipTargetsResolve(
            Map<String, byte[]> parts,
            String relationshipsPart,
            String ownerDirectory) throws Exception {
        Document relationships = parseXml(parts.get(relationshipsPart));
        for (Element relationship : elements(
                relationships.getElementsByTagNameNS(
                        PACKAGE_REL_NS, "Relationship"))) {
            String target = relationship.getAttribute("Target");
            assertThat(target).doesNotStartWith("/");
            assertThat(parts).containsKey(normalize(ownerDirectory + target));
        }
    }

    private static String normalize(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new AssertionError("Relationship escapes package root: " + path);
                }
                segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }
        return String.join("/", segments);
    }

    private static Element shapeById(List<Element> shapes, String id) {
        return shapes.stream()
                .filter(shape -> id.equals(shape.getAttribute("ID")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing shape " + id));
    }

    private static String cellValue(Element parent, String name) {
        return elements(parent.getElementsByTagNameNS(VISIO_NS, "Cell")).stream()
                .filter(cell -> name.equals(cell.getAttribute("N")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing cell " + name))
                .getAttribute("V");
    }

    private static List<String> directElementNames(Element parent) {
        return directChildren(parent, null).stream()
                .map(Element::getLocalName)
                .toList();
    }

    private static Element directChild(Element parent, String localName) {
        return directChildren(parent, localName).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing direct child " + localName
                                + " below " + parent.getLocalName()));
    }

    private static List<Element> directChildren(
            Element parent,
            String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && (localName == null || localName.equals(element.getLocalName()))) {
                result.add(element);
            }
        }
        return result;
    }

    private static List<Element> elements(NodeList nodes) {
        List<Element> result = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            result.add((Element) nodes.item(index));
        }
        return result;
    }

    private static byte[] entry(byte[] packageBytes, String name) throws IOException {
        byte[] value = unzip(packageBytes).get(name);
        if (value == null) {
            throw new AssertionError("Missing package part " + name);
        }
        return value;
    }

    private static Map<String, byte[]> unzip(byte[] packageBytes) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(packageBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertThat(entry.isDirectory()).isFalse();
                assertThat(parts.put(entry.getName(), zip.readAllBytes()))
                        .as("duplicate ZIP entry %s", entry.getName())
                        .isNull();
            }
        }
        return parts;
    }

    private static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
