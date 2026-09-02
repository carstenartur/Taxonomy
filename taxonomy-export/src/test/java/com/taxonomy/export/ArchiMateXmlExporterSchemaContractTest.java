package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiMateXmlExporterSchemaContractTest {

    private static final String ARCHIMATE_NAMESPACE =
            "http://www.opengroup.org/xsd/archimate/3.0/";
    private static final String XSI_NAMESPACE =
            "http://www.w3.org/2001/XMLSchema-instance";

    private final ArchiMateDiagramService diagramService = new ArchiMateDiagramService();
    private final ArchiMateXmlExporter exporter = new ArchiMateXmlExporter();

    @Test
    void emitsConcreteDiagramNodeAndConnectionTypes() throws Exception {
        Document document = exportRepresentativeDiagram();

        Element anchorNode = elementByIdentifier(document, "node", "id-vn-capability");
        Element serviceNode = elementByIdentifier(document, "node", "id-vn-service");
        Element connection = elementByIdentifier(document, "connection", "id-vc-supports");

        assertThat(anchorNode.getAttributeNS(XSI_NAMESPACE, "type")).isEqualTo("Element");
        assertThat(serviceNode.getAttributeNS(XSI_NAMESPACE, "type")).isEqualTo("Element");
        assertThat(connection.getAttributeNS(XSI_NAMESPACE, "type"))
                .isEqualTo("Relationship");
        assertThat(connection.getAttribute("relationshipRef")).isEqualTo("id-rel-supports");
        assertThat(connection.getAttribute("source")).isEqualTo("id-vn-capability");
        assertThat(connection.getAttribute("target")).isEqualTo("id-vn-service");
    }

    @Test
    void writesLineWidthAsStyleAttributeInsteadOfInvalidChildElement() throws Exception {
        Document document = exportRepresentativeDiagram();

        Element anchorStyle = directChildElement(
                elementByIdentifier(document, "node", "id-vn-capability"), "style");
        Element regularStyle = directChildElement(
                elementByIdentifier(document, "node", "id-vn-service"), "style");

        assertThat(anchorStyle.getAttribute("lineWidth")).isEqualTo("3");
        assertThat(regularStyle.hasAttribute("lineWidth")).isFalse();
        assertThat(document.getElementsByTagNameNS(ARCHIMATE_NAMESPACE, "lineWidth").getLength())
                .isZero();
        assertThat(directChildElement(anchorStyle, "fillColor")).isNotNull();
        assertThat(directChildElement(regularStyle, "fillColor")).isNotNull();
    }

    @Test
    void retainsTheDeclaredArchiMate31DiagramSchemaLocation() throws Exception {
        Document document = exportRepresentativeDiagram();

        Element model = document.getDocumentElement();
        assertThat(model.getNamespaceURI()).isEqualTo(ARCHIMATE_NAMESPACE);
        assertThat(model.getAttributeNS(XSI_NAMESPACE, "schemaLocation"))
                .isEqualTo(ARCHIMATE_NAMESPACE
                        + " http://www.opengroup.org/xsd/archimate/3.1/archimate3_Diagram.xsd");
    }

    private Document exportRepresentativeDiagram() throws Exception {
        DiagramModel diagram = new DiagramModel(
                "Schema contract",
                List.of(
                        new DiagramNode(
                                "capability", "Secure communications", "Capabilities",
                                0.91, true, 1),
                        new DiagramNode(
                                "service", "Messaging service", "Core Services",
                                0.74, false, 3)),
                List.of(new DiagramEdge(
                        "supports", "capability", "service", "SUPPORTS", 0.74)),
                new DiagramLayout("LR", true));

        byte[] xml = exporter.export(diagramService.convert(diagram));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element elementByIdentifier(
            Document document, String localName, String identifier) {
        NodeList candidates = document.getElementsByTagNameNS(
                ARCHIMATE_NAMESPACE, localName);
        for (int index = 0; index < candidates.getLength(); index++) {
            Element candidate = (Element) candidates.item(index);
            if (identifier.equals(candidate.getAttribute("identifier"))) {
                return candidate;
            }
        }
        throw new AssertionError(
                "Missing " + localName + " with identifier " + identifier);
    }

    private static Element directChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && ARCHIMATE_NAMESPACE.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        throw new AssertionError(
                "Missing direct child " + localName + " below " + parent.getTagName());
    }
}
