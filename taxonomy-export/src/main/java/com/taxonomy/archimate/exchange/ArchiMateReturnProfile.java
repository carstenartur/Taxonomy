package com.taxonomy.archimate.exchange;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.archimate.ArchiMateLoss;
import com.taxonomy.exchange.ExchangeXml;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Explicit content-preservation return profile for the observed Archi 5.10 export.
 * Requires a strict original; never selected as a fallback by the strict reader.
 * Returned semantic edits are unsupported. The original supplies erased representation,
 * never missing identity, property values or provenance. Callers must expose all losses.
 */
public final class ArchiMateReturnProfile {
    public static final String VERSION = "taxonomy-archi-5.10-return-v1";
    private static final String NS = "http://www.opengroup.org/xsd/archimate/3.0/";
    private static final String NODE_DEFAULTS = "<style xmlns=\"" + NS + "\"><lineColor r=\"92\" g=\"92\" b=\"92\" a=\"100\"/>"
            + "<font name=\"Sans\" size=\"9\"><color r=\"0\" g=\"0\" b=\"0\"/></font></style>";
    private static final String CONNECTION_STYLE = "<style xmlns=\"" + NS + "\"><lineColor r=\"0\" g=\"0\" b=\"0\"/>"
            + "<font name=\"Sans\" size=\"9\"><color r=\"0\" g=\"0\" b=\"0\"/></font></style>";

    public ArchiMateModel read(byte[] original, byte[] returned) {
        ArchiMateModel model = new ArchiMateExchangeReader().read(original);
        ArchiMateSchema.parse(returned);
        Document before = ExchangeXml.parse(original), after = ExchangeXml.parse(returned);
        List<ArchiMateLoss> losses = new ArrayList<>(model.losses());
        requireKnownLanguageLocations(before);
        requireKnownLanguageLocations(after);
        comparePropertyRepresentations(before, after, losses);
        compareOrganizations(before, after, losses);
        compareNames(before, after, losses);
        compareLabels(before, after, losses);
        compareStyles(before, after, losses);
        compareDefinitions(before, after, losses);
        for (Element view : ExchangeXml.all(before, NS, "view")) {
            Element actual = ExchangeXml.all(after, NS, "view").stream()
                    .filter(e -> e.getAttribute("identifier").equals(view.getAttribute("identifier"))).findFirst().orElseThrow();
            if (!view.hasAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type")
                    && actual.hasAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type")
                    && "Diagram".equals(ArchiMateExchangeReader.type(actual))) {
                view.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "Diagram");
                losses.add(new ArchiMateLoss("return", view.getAttribute("identifier"), "view.xsi:type", "ARCHI_DIAGRAM_TYPE_EXPLICIT",
                        "Archi writes the previously implicit Diagram type explicitly; view membership and attributes remain exact."));
            }
        }
        requireEqual("complete content and attributes", canonical(before.getDocumentElement()), canonical(after.getDocumentElement()));
        return new ArchiMateModel(model.id(), model.title(), model.elements(), model.relationships(),
                model.organizations(), model.views(), model.properties(), losses);
    }

    private static void compareDefinitions(Document before, Document after, List<ArchiMateLoss> losses) {
        Map<String, Element> originals = definitions(before), returned = definitions(after);
        requireEqual("property names", originals.keySet(), returned.keySet());
        Map<String, String> references = new HashMap<>();
        originals.forEach((name, definition) -> {
            Element actual = returned.get(name);
            String originalType = definition.getAttribute("type"), actualType = actual.getAttribute("type");
            if (!originalType.equals(actualType)) {
                if (!Set.of("number", "boolean").contains(originalType) || !"string".equals(actualType))
                    throw new IllegalArgumentException("Unsupported property declaration change: " + name);
                losses.add(new ArchiMateLoss("return", name, "propertyDefinition.type", "ARCHI_PROPERTY_TYPE_TO_STRING",
                        "Archi changed " + originalType + " to string. Exact unchanged values are interpreted using the supplied original declaration."));
                definition.setAttribute("type", actualType);
            }
            String originalId = definition.getAttribute("identifier"), actualId = actual.getAttribute("identifier");
            if (!originalId.equals(actualId)) losses.add(new ArchiMateLoss("return", name, "propertyDefinition.identifier",
                    "ARCHI_PROPERTY_DEFINITION_ID_REWRITTEN", "Property declaration identifier changed from " + originalId + " to " + actualId + "; matched by the unique unchanged property name."));
            references.put(actualId, originalId);
            actual.setAttribute("identifier", originalId);
        });
        for (Element property : ExchangeXml.all(after, NS, "property")) {
            String ref = references.get(property.getAttribute("propertyDefinitionRef"));
            if (ref == null) throw new IllegalArgumentException("Unknown returned property reference");
            property.setAttribute("propertyDefinitionRef", ref);
        }
    }

    private static Map<String, Element> definitions(Document document) {
        Map<String, Element> result = new TreeMap<>();
        for (Element definition : ExchangeXml.all(document, NS, "propertyDefinition")) {
            String name = ExchangeXml.text(definition, "name");
            if (name.isBlank() || result.putIfAbsent(name, definition) != null)
                throw new IllegalArgumentException("Missing or duplicate property name");
        }
        return result;
    }

    // Compare every field after only the declared transformations. Collection order and namespace
    // prefix choices do not establish identity. Text (including blank property values) stays exact.
    private static String canonical(Element element) {
        Map<String, String> attrs = attributes(element);
        attrs.remove(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI + "|schemaLocation");
        String typeKey = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI + "|type";
        if (attrs.containsKey(typeKey)) {
            String value = attrs.get(typeKey); int colon = value.indexOf(':');
            attrs.put(typeKey, "{" + element.lookupNamespaceURI(colon < 0 ? null : value.substring(0, colon)) + "}" + value.substring(colon + 1));
        }
        var children = ExchangeXml.children(element);
        List<String> content = children.stream().map(ArchiMateReturnProfile::canonical).toList();
        if (Set.of("elements", "relationships", "properties", "propertyDefinitions").contains(element.getLocalName()))
            content = content.stream().sorted().toList();
        StringBuilder text = new StringBuilder();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
            if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && (children.isEmpty() || !child.getNodeValue().isBlank())) text.append(child.getNodeValue());
        StringBuilder result = new StringBuilder();
        part(result, element.getNamespaceURI()); part(result, element.getLocalName());
        part(result, Integer.toString(attrs.size()));
        attrs.forEach((key, value) -> { part(result, key); part(result, value); });
        part(result, Integer.toString(content.size()));
        content.forEach(value -> part(result, value));
        part(result, text.toString());
        return result.toString();
    }

    private static void part(StringBuilder result, String value) {
        result.append(value.length()).append(':').append(value);
    }

    private static void requireEqual(String part, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new IllegalArgumentException("Unsupported Archi return change: " + part);
    }
    private static void comparePropertyRepresentations(org.w3c.dom.Document before, org.w3c.dom.Document after, List<ArchiMateLoss> losses) {
        var originals = scopedProperties(before);
        var returned = scopedProperties(after);
        requireEqual("property membership", originals.keySet(), returned.keySet());
        originals.forEach((key, property) -> {
            var actual = returned.get(key);
            var values = ExchangeXml.children(property);
            var actualValues = ExchangeXml.children(actual);
            requireEqual("property value count " + key, values.size(), actualValues.size());
            for (int index = 0; index < values.size(); index++) {
                var value = values.get(index);
                if ("en".equals(value.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
                        && !actualValues.get(index).hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")) {
                    value.removeAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang");
                    losses.add(new ArchiMateLoss("return", key, "value.xml:lang", "ARCHI_PROPERTY_LANGUAGE_OMITTED",
                            "Archi 5.10.0 omits the emitted en property-value language marker; the value and all remaining attributes are compared exactly."));
                }
            }
            var expectedValue = (org.w3c.dom.Element) property.cloneNode(true);
            var actualValue = (org.w3c.dom.Element) actual.cloneNode(true);
            expectedValue.removeAttribute("propertyDefinitionRef");
            actualValue.removeAttribute("propertyDefinitionRef");
            requireEqual("property content " + key, ExchangeXml.semantic(ExchangeXml.xml(expectedValue)), ExchangeXml.semantic(ExchangeXml.xml(actualValue)));
        });
    }

    private static Map<String, org.w3c.dom.Element> scopedProperties(org.w3c.dom.Document document) {
        var definitions = new HashMap<String, String>();
        var nodes = document.getElementsByTagNameNS("http://www.opengroup.org/xsd/archimate/3.0/", "propertyDefinition");
        for (int index = 0; index < nodes.getLength(); index++) {
            var definition = (org.w3c.dom.Element) nodes.item(index);
            definitions.put(definition.getAttribute("identifier"), ExchangeXml.text(definition, "name"));
        }
        var result = new TreeMap<String, org.w3c.dom.Element>();
        nodes = document.getElementsByTagNameNS("http://www.opengroup.org/xsd/archimate/3.0/", "property");
        for (int index = 0; index < nodes.getLength(); index++) {
            var property = (org.w3c.dom.Element) nodes.item(index);
            var owner = (org.w3c.dom.Element) property.getParentNode().getParentNode();
            String name = definitions.get(property.getAttribute("propertyDefinitionRef"));
            if (name == null || owner.getAttribute("identifier").isBlank()) throw new IllegalArgumentException("Unscoped property");
            if (result.putIfAbsent(owner.getAttribute("identifier") + "/" + name, property) != null)
                throw new IllegalArgumentException("Duplicate scoped property");
        }
        return result;
    }

    private static void compareNames(org.w3c.dom.Document before, org.w3c.dom.Document after, List<ArchiMateLoss> losses) {
        var original = scopedFields(before, "name"); var returned = scopedFields(after, "name");
        requireEqual("name membership", original.keySet(), returned.keySet());
        original.forEach((key, name) -> {
            var actual = returned.get(key);
            if ("en".equals(name.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
                    && !actual.hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")) {
                name.removeAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang");
                losses.add(new ArchiMateLoss("return", key, "name.xml:lang", "ARCHI_NAME_LANGUAGE_OMITTED",
                        "Archi 5.10.0 omits the emitted en name language marker for " + name.getTextContent()
                                + "; the name text and remaining attributes are compared exactly."));
            }
            requireEqual("name content " + key, field(name), field(actual));
        });
    }

    private static void compareLabels(org.w3c.dom.Document before, org.w3c.dom.Document after, List<ArchiMateLoss> losses) {
        var original = scopedFields(before, "label"); var returned = scopedFields(after, "label");
        var keys = new TreeSet<>(original.keySet()); keys.addAll(returned.keySet());
        for (String key : keys) {
            var label = original.get(key); var actual = returned.get(key);
            if (label != null && actual == null && key.startsWith("node/") && emittedEnglishLabel(label)) {
                losses.add(new ArchiMateLoss("return", key, "label", "ARCHI_NODE_LABEL_OMITTED",
                        "Archi 5.10.0 omits the complete emitted node label, including its en language marker: " + ExchangeXml.xml(label)));
                label.getParentNode().removeChild(label);
            } else requireEqual("visual label " + key, field(label), field(actual));
        }
    }

    private static void compareStyles(org.w3c.dom.Document before, org.w3c.dom.Document after, List<ArchiMateLoss> losses) {
        var original = scopedFields(before, "style"); var returned = scopedFields(after, "style");
        Map<String, Element> connections = new HashMap<>();
        ExchangeXml.all(before, NS, "connection").forEach(e -> connections.put(e.getAttribute("identifier"), e));
        var keys = new TreeSet<>(original.keySet()); keys.addAll(returned.keySet());
        for (String key : keys) {
            var style = original.get(key); var actual = returned.get(key);
            if (Objects.equals(field(style), field(actual))) continue;
            if (style == null && actual != null && key.startsWith("connection/")) {
                requireEqual("observed connection style " + key, ExchangeXml.semantic(CONNECTION_STYLE), field(actual));
                losses.add(new ArchiMateLoss("return", key, "style", "ARCHI_CONNECTION_STYLE_ADDED",
                        "Archi 5.10.0 adds the observed black line and Sans 9 black font: " + ExchangeXml.xml(actual)));
            } else if (style != null && actual != null && key.startsWith("node/")) {
                var children = ExchangeXml.children(style); var attributes = attributes(style);
                if (!(attributes.isEmpty() || attributes.equals(Map.of("lineWidth", "3"))) || children.size() != 1
                        || !children.getFirst().getLocalName().equals("fillColor")
                        || !attributes(children.getFirst()).keySet().equals(Set.of("r", "g", "b")))
                    throw new IllegalArgumentException("Unrecognized source node style for product transformation: " + key);
                var expected = (org.w3c.dom.Element) style.cloneNode(true);
                expected.removeAttribute("lineWidth");
                ExchangeXml.children(expected).getFirst().setAttribute("a", "100");
                var additions = ExchangeXml.parse(NODE_DEFAULTS.getBytes(StandardCharsets.UTF_8));
                for (var addition : ExchangeXml.children(additions.getDocumentElement()))
                    expected.appendChild(expected.getOwnerDocument().importNode(addition, true));
                requireEqual("observed node style " + key, field(expected), field(actual));
                if (style.hasAttribute("lineWidth")) losses.add(new ArchiMateLoss("return", key, "style.lineWidth", "ARCHI_NODE_LINE_WIDTH_OMITTED",
                        "Archi 5.10.0 omits the emitted lineWidth=3; arbitrary line-width replacements are rejected."));
                losses.add(new ArchiMateLoss("return", key, "style", "ARCHI_NODE_STYLE_DEFAULTS_ADDED",
                        "Archi 5.10.0 adds fill alpha 100, line RGBA 92/92/92/100 and Sans 9 black font; original fill RGB remains exact: " + ExchangeXml.xml(actual)));
            } else throw new IllegalArgumentException("Unrecognized product style change for " + key);
            if (style != null) style.getParentNode().replaceChild(before.importNode(actual, true), style);
            else {
                String id = key.substring(key.indexOf('/') + 1);
                Element connection = connections.get(id);
                if (connection == null) throw new IllegalArgumentException("Unknown returned connection: " + id);
                connection.appendChild(before.importNode(actual, true));
            }
        }
    }

    private static Map<String, org.w3c.dom.Element> scopedFields(org.w3c.dom.Document document, String field) {
        var result = new TreeMap<String, org.w3c.dom.Element>();
        var nodes = document.getElementsByTagNameNS(NS, field);
        for (int index = 0; index < nodes.getLength(); index++) {
            var value = (org.w3c.dom.Element) nodes.item(index);
            var owner = (org.w3c.dom.Element) value.getParentNode();
            if (field.equals("label") && owner.getLocalName().equals("item")) continue; // Compared with the complete organizations subtree.
            String identity = owner.getLocalName().equals("propertyDefinition") ? ExchangeXml.text(owner, "name") : owner.getAttribute("identifier");
            if (identity.isBlank() || result.putIfAbsent(owner.getLocalName() + "/" + identity, value) != null)
                throw new IllegalArgumentException("Unscoped or duplicate " + field + " field");
        }
        return result;
    }

    private static void requireKnownLanguageLocations(org.w3c.dom.Document document) {
        var elements = document.getElementsByTagNameNS("*", "*");
        for (int index = 0; index < elements.getLength(); index++) {
            var element = (org.w3c.dom.Element) elements.item(index);
            if (element.hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")
                    && (!NS.equals(element.getNamespaceURI()) || !Set.of("name", "label", "value").contains(element.getLocalName())))
                throw new IllegalArgumentException("Unexpected language-bearing field: " + element.getLocalName());
        }
    }

    private static boolean emittedEnglishLabel(org.w3c.dom.Element label) {
        return attributes(label).equals(Map.of(javax.xml.XMLConstants.XML_NS_URI + "|lang", "en"))
                && ExchangeXml.children(label).isEmpty() && !label.getTextContent().isBlank();
    }

    private static Map<String, String> attributes(org.w3c.dom.Element element) {
        var result = new TreeMap<String, String>();
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            var attribute = element.getAttributes().item(index);
            if (javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) continue;
            result.put(attribute.getNamespaceURI() == null ? attribute.getNodeName() : attribute.getNamespaceURI() + "|" + attribute.getLocalName(), attribute.getNodeValue());
        }
        return result;
    }

    private static String field(org.w3c.dom.Element element) {
        return element == null ? null : ExchangeXml.semantic(ExchangeXml.xml(element));
    }

    private static void compareOrganizations(org.w3c.dom.Document before, org.w3c.dom.Document after, List<ArchiMateLoss> losses) {
        var original = ExchangeXml.children(before.getDocumentElement()).stream().filter(e -> e.getLocalName().equals("organizations")).toList();
        var returned = ExchangeXml.children(after.getDocumentElement()).stream().filter(e -> e.getLocalName().equals("organizations")).toList();
        if (original.size() == 1 && returned.isEmpty()) {
            var groups = ExchangeXml.children(original.getFirst());
            for (var group : groups) {
                if (!group.getLocalName().equals("item") || group.hasAttribute("identifierRef")) throw new IllegalArgumentException("Unexpected source folder shape");
                var members = ExchangeXml.children(group).stream().filter(e -> e.getLocalName().equals("item")).toList();
                if (members.isEmpty() || members.stream().anyMatch(e -> !e.hasAttribute("identifierRef") || !ExchangeXml.children(e).isEmpty()))
                    throw new IllegalArgumentException("Only the observed flat source type folders may be omitted");
                var labels = ExchangeXml.children(group).stream().filter(e -> e.getLocalName().equals("label")).toList();
                if (labels.size() != 1 || !emittedEnglishLabel(labels.getFirst()) || ExchangeXml.children(group).size() != members.size() + 1)
                    throw new IllegalArgumentException("Only the observed en-labelled source type folders may be omitted");
                losses.add(new ArchiMateLoss("return", before.getDocumentElement().getAttribute("identifier") + "/organizations/" + ExchangeXml.text(group, "label"), "organizations", "ARCHI_TYPE_FOLDERS_OMITTED",
                        "Archi 5.10.0 omits the flat source type folder " + ExchangeXml.text(group, "label")
                                + ", including its label's en language marker, with references " + members.stream().map(e -> e.getAttribute("identifierRef")).toList() + "; element identities and view membership are compared separately."));
            }
            before.getDocumentElement().removeChild(original.getFirst());
        } else {
            requireEqual("organization folders", original.stream().map(e -> ExchangeXml.semantic(ExchangeXml.xml(e))).toList(),
                    returned.stream().map(e -> ExchangeXml.semantic(ExchangeXml.xml(e))).toList());
        }
    }

}
