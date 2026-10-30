package com.taxonomy.export;

import com.taxonomy.archimate.exchange.*;
import com.taxonomy.diagram.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ArchiMateReturnProfileTest {
    static byte[] original() {
        return new ArchiMateXmlExporter().export(new ArchiMateDiagramService().convert(new DiagramModel("Return fixture",
                List.of(new DiagramNode("CP", "Capability", "Capabilities", .9, true, 1),
                        new DiagramNode("CR", "Service", "Core Services", .8, false, 2)),
                List.of(new DiagramEdge("r", "CR", "CP", "FULFILLS", .8)), new DiagramLayout("LR", true))));
    }
    static byte[] typedReturn(byte[] original) {
        return new String(original, StandardCharsets.UTF_8).replace("type=\"number\"", "type=\"string\"")
                .replace("type=\"boolean\"", "type=\"string\"").getBytes(StandardCharsets.UTF_8);
    }
    @Test void explicitlyBoundReturnAcceptsObservedTypeErasureWhileStrictReaderStillRejects() throws Exception {
        byte[] source = original(), returned = typedReturn(source);
        assertThrows(IllegalArgumentException.class, () -> new ArchiMateExchangeReader().read(returned));
        var model = new ArchiMateReturnProfile().read(source, returned);
        assertEquals(new ArchiMateExchangeReader().toDiagram(new ArchiMateExchangeReader().read(source)),
                new ArchiMateExchangeReader().toDiagram(model));
        assertTrue(model.losses().stream().anyMatch(loss -> loss.kind().equals("ARCHI_PROPERTY_TYPE_TO_STRING")));
    }

    @Test void actualInstalledArchiReturnPreservesContentWithExplicitLosses() throws Exception {
        byte[] source = fixture("workbench-original.xml"), returned = fixture("archi-5.10-returned.xml");
        assertThrows(IllegalArgumentException.class, () -> new ArchiMateExchangeReader().read(returned));
        var result = new ArchiMateReturnProfile().read(source, returned);
        assertEquals(new ArchiMateExchangeReader().read(source).relationships(), result.relationships());
        var kinds = result.losses().stream().map(com.taxonomy.archimate.ArchiMateLoss::kind).collect(java.util.stream.Collectors.toSet());
        assertTrue(kinds.containsAll(List.of("ARCHI_PROPERTY_TYPE_TO_STRING", "ARCHI_PROPERTY_LANGUAGE_OMITTED",
                "ARCHI_NAME_LANGUAGE_OMITTED", "ARCHI_NODE_LABEL_OMITTED", "ARCHI_TYPE_FOLDERS_OMITTED",
                "ARCHI_NODE_LINE_WIDTH_OMITTED", "ARCHI_NODE_STYLE_DEFAULTS_ADDED", "ARCHI_CONNECTION_STYLE_ADDED")));
    }

    @Test void rejectsSemanticEditsMissingProvenanceAndUnknownPresentationChanges() throws Exception {
        byte[] source = fixture("workbench-original.xml");
        String returned = new String(fixture("archi-5.10-returned.xml"), StandardCharsets.UTF_8);
        for (String changed : List.of(
                returned.replace("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                returned.replace("<value>0.98765</value>", "<value>0.98766</value>"),
                returned.replace("<value>true</value>", "<value>false</value>"),
                returned.replace("Subscriber protection", "Edited label"),
                returned.replace("x=\"40\"", "x=\"41\""),
                returned.replace("name=\"Sans\"", "name=\"Serif\""),
                returned.replace("id-element-000000024350", "id-element-000000024351"),
                returned.replace("<name>taxonomy.anchor</name>", "<name>taxonomy.unknown</name>"),
                returned.replace("<value>LR</value>", "<value xml:lang=\"de\">LR</value>"),
                returned.replace("<value>LR</value>", "<value> </value>"))) {
            assertNotEquals(returned, changed, "Mutation must modify fixture");
            assertThrows(IllegalArgumentException.class, () -> new ArchiMateReturnProfile().read(source, changed.getBytes(StandardCharsets.UTF_8)));
        }
        var document = com.taxonomy.exchange.ExchangeXml.parse(returned.getBytes(StandardCharsets.UTF_8));
        var properties = com.taxonomy.exchange.ExchangeXml.child(document.getDocumentElement(), "properties");
        properties.removeChild(com.taxonomy.exchange.ExchangeXml.children(properties).getFirst());
        assertThrows(IllegalArgumentException.class, () -> new ArchiMateReturnProfile().read(source, com.taxonomy.exchange.ExchangeXml.write(document)));
        assertThrows(IllegalArgumentException.class, () -> new ArchiMateReturnProfile().read(fixture("archi-5.10-returned.xml"), fixture("archi-5.10-returned.xml")));
    }

    @Test void rejectsUndeclaredLossesEndpointReversalAndViewOrderChanges() throws Exception {
        byte[] source = fixture("workbench-original.xml");
        byte[] returned = fixture("archi-5.10-returned.xml");
        for (String mutation : List.of("endpoint", "type", "view-order", "extra-attribute", "missing-value")) {
            var document = com.taxonomy.exchange.ExchangeXml.parse(returned);
            var relation = com.taxonomy.exchange.ExchangeXml.all(document, ArchiMateSchema.NAMESPACE, "relationship").getFirst();
            switch (mutation) {
                case "endpoint" -> {
                    String previous = relation.getAttribute("source");
                    relation.setAttribute("source", relation.getAttribute("target")); relation.setAttribute("target", previous);
                }
                case "type" -> relation.setAttributeNS(javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "Association");
                case "view-order" -> {
                    var view = com.taxonomy.exchange.ExchangeXml.all(document, ArchiMateSchema.NAMESPACE, "view").getFirst();
                    var nodes = ArchiMateExchangeReader.children(view, "node");
                    view.insertBefore(nodes.get(1), nodes.getFirst());
                }
                case "extra-attribute" -> relation.setAttribute("isDirected", "true");
                case "missing-value" -> {
                    var property = com.taxonomy.exchange.ExchangeXml.all(document, ArchiMateSchema.NAMESPACE, "property").getFirst();
                    property.removeChild(com.taxonomy.exchange.ExchangeXml.children(property).getFirst());
                }
                default -> fail("Unknown fixture mutation");
            }
            assertThrows(IllegalArgumentException.class, () -> new ArchiMateReturnProfile().read(source,
                    com.taxonomy.exchange.ExchangeXml.write(document)), mutation);
        }
    }

    private static byte[] fixture(String name) throws java.io.IOException {
        try (var stream = ArchiMateReturnProfileTest.class.getResourceAsStream("/interoperability/archi-return/" + name)) {
            return java.util.Objects.requireNonNull(stream).readAllBytes();
        }
    }
}
