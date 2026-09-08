package com.taxonomy.archimate.exchange;

import com.taxonomy.archimate.*;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Typed ArchiMate 3.1 subset writer. Every result passes offline normative validation. */
public class ArchiMateXmlExporter {
    public byte[] export(ArchiMateModel model) {
        validateReferences(model);
        try {
            ByteArrayOutputStream output = new BoundedOutput();
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8");
            try {
                write(xml, model);
            } finally {
                xml.close();
            }
            byte[] bytes = output.toByteArray();
            ArchiMateSchema.parse(bytes);
            return bytes;
        } catch (XMLStreamException exception) {
            throw new IllegalArgumentException("Cannot serialize ArchiMate exchange model", exception);
        }
    }

    private void write(XMLStreamWriter xml, ArchiMateModel model) throws XMLStreamException {
        Map<String, ArchiMateProperty> modelProperties = modelProperties(model);
        Map<String, String> definitions = new TreeMap<>();
        collectDefinitions(definitions, modelProperties);
        for (ArchiMateElement element : model.elements()) collectDefinitions(definitions, identity(element.id(), element.properties()));
        for (ArchiMateRelationship relation : model.relationships()) collectDefinitions(definitions, identity(relation.id(), relation.properties()));
        if (!model.views().isEmpty()) definitions.put("taxonomy.id", "string");

        xml.writeStartDocument("UTF-8", "1.0");
        xml.writeStartElement("model");
        xml.writeDefaultNamespace(ArchiMateSchema.NAMESPACE);
        xml.writeNamespace("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
        xml.writeAttribute("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "schemaLocation",
                ArchiMateSchema.NAMESPACE + " http://www.opengroup.org/xsd/archimate/3.1/archimate3_Diagram.xsd");
        attr(xml, "identifier", ArchiMateIds.id("model", model.id()));
        text(xml, "name", model.title());
        properties(xml, modelProperties);
        if (!model.elements().isEmpty()) {
            xml.writeStartElement("elements");
            for (ArchiMateElement element : model.elements()) {
                xml.writeStartElement("element");
                attr(xml, "identifier", ArchiMateIds.id("element", element.id()));
                type(xml, element.archiMateType());
                text(xml, "name", element.label());
                if (element.documentation() != null && !element.documentation().isEmpty()) text(xml, "documentation", element.documentation());
                properties(xml, identity(element.id(), element.properties()));
                xml.writeEndElement();
            }
            xml.writeEndElement();
        }
        if (!model.relationships().isEmpty()) {
            xml.writeStartElement("relationships");
            for (ArchiMateRelationship relation : model.relationships()) {
                xml.writeStartElement("relationship");
                attr(xml, "identifier", ArchiMateIds.id("relationship", relation.id()));
                type(xml, relation.archiMateType());
                attr(xml, "source", ArchiMateIds.id("element", relation.sourceId()));
                attr(xml, "target", ArchiMateIds.id("element", relation.targetId()));
                if (relation.accessType() != null) attr(xml, "accessType", relation.accessType());
                text(xml, "name", relation.name());
                properties(xml, identity(relation.id(), relation.properties()));
                xml.writeEndElement();
            }
            xml.writeEndElement();
        }
        if (!model.organizations().isEmpty()) {
            xml.writeStartElement("organizations");
            for (var entry : new TreeMap<>(model.organizations()).entrySet()) {
                xml.writeStartElement("item");
                text(xml, "label", entry.getKey());
                for (String id : entry.getValue()) {
                    xml.writeEmptyElement("item");
                    attr(xml, "identifierRef", ArchiMateIds.id("element", id));
                }
                xml.writeEndElement();
            }
            xml.writeEndElement();
        }
        if (!definitions.isEmpty()) {
            xml.writeStartElement("propertyDefinitions");
            for (var entry : definitions.entrySet()) {
                xml.writeStartElement("propertyDefinition");
                attr(xml, "identifier", ArchiMateIds.id("property", entry.getKey()));
                attr(xml, "type", entry.getValue());
                text(xml, "name", entry.getKey());
                xml.writeEndElement();
            }
            xml.writeEndElement();
        }
        if (!model.views().isEmpty()) {
            xml.writeStartElement("views");
            xml.writeStartElement("diagrams");
            for (ArchiMateView view : model.views()) writeView(xml, view);
            xml.writeEndElement();
            xml.writeEndElement();
        }
        xml.writeEndElement();
        xml.writeEndDocument();
    }

    private void writeView(XMLStreamWriter xml, ArchiMateView view) throws XMLStreamException {
        // The XSD declares diagrams/view directly as Diagram; xsi:type is not required here.
        xml.writeStartElement("view");
        attr(xml, "identifier", ArchiMateIds.id("view", view.id()));
        attr(xml, "viewpoint", "Layered");
        text(xml, "name", view.name());
        properties(xml, identity(view.id(), Map.of()));
        for (ArchiMateViewNode node : view.nodes()) {
            xml.writeStartElement("node");
            attr(xml, "identifier", ArchiMateIds.id("node", view.id(), node.id()));
            type(xml, "Element");
            attr(xml, "elementRef", ArchiMateIds.id("element", node.elementId()));
            attr(xml, "x", node.x()); attr(xml, "y", node.y());
            attr(xml, "w", node.w()); attr(xml, "h", node.h());
            text(xml, "label", node.label());
            xml.writeStartElement("style");
            if (node.lineWidth() != 1) attr(xml, "lineWidth", node.lineWidth());
            xml.writeEmptyElement("fillColor");
            attr(xml, "r", node.r()); attr(xml, "g", node.g()); attr(xml, "b", node.b());
            xml.writeEndElement();
            xml.writeEndElement();
        }
        for (ArchiMateViewConnection connection : view.connections()) {
            xml.writeEmptyElement("connection");
            attr(xml, "identifier", ArchiMateIds.id("connection", view.id(), connection.id()));
            type(xml, "Relationship");
            attr(xml, "relationshipRef", ArchiMateIds.id("relationship", connection.relationshipId()));
            attr(xml, "source", ArchiMateIds.id("node", view.id(), connection.sourceNodeId()));
            attr(xml, "target", ArchiMateIds.id("node", view.id(), connection.targetNodeId()));
        }
        xml.writeEndElement();
    }

    public static Map<String, ArchiMateProperty> modelProperties(ArchiMateModel model) {
        Map<String, ArchiMateProperty> result = new TreeMap<>(identity(model.id(), model.properties()));
        for (int i = 0; i < model.losses().size(); i++) {
            ArchiMateLoss loss = model.losses().get(i);
            String prefix = "taxonomy.loss." + i + ".";
            result.put(prefix + "scope", ArchiMateProperty.text(loss.scope()));
            result.put(prefix + "id", ArchiMateProperty.text(loss.id()));
            result.put(prefix + "field", ArchiMateProperty.text(loss.field()));
            result.put(prefix + "kind", ArchiMateProperty.text(loss.kind()));
            result.put(prefix + "rationale", ArchiMateProperty.text(loss.rationale()));
        }
        return result;
    }

    private static Map<String, ArchiMateProperty> identity(String id, Map<String, ArchiMateProperty> properties) {
        Map<String, ArchiMateProperty> result = new TreeMap<>(properties);
        ArchiMateProperty original = result.put("taxonomy.id", ArchiMateProperty.text(id));
        if (original != null && !original.equals(ArchiMateProperty.text(id))) {
            throw new IllegalArgumentException("Conflicting Taxonomy identity property");
        }
        return result;
    }

    private static void collectDefinitions(Map<String, String> definitions, Map<String, ArchiMateProperty> properties) {
        properties.forEach((key, value) -> {
            String previous = definitions.putIfAbsent(key, value.type());
            if (previous != null && !previous.equals(value.type())) throw new IllegalArgumentException("Conflicting property types: " + key);
        });
    }

    private static void properties(XMLStreamWriter xml, Map<String, ArchiMateProperty> properties) throws XMLStreamException {
        if (properties.isEmpty()) return;
        xml.writeStartElement("properties");
        for (var entry : new TreeMap<>(properties).entrySet()) {
            xml.writeStartElement("property");
            attr(xml, "propertyDefinitionRef", ArchiMateIds.id("property", entry.getKey()));
            text(xml, "value", entry.getValue().value());
            xml.writeEndElement();
        }
        xml.writeEndElement();
    }

    private static void type(XMLStreamWriter xml, String value) throws XMLStreamException {
        xml.writeAttribute("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type", ArchiMateIds.requireXmlText(value));
    }

    private static void attr(XMLStreamWriter xml, String name, Object value) throws XMLStreamException {
        xml.writeAttribute(name, ArchiMateIds.requireXmlText(String.valueOf(value)));
    }

    private static void text(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeAttribute("xml", XMLConstants.XML_NS_URI, "lang", "en");
        String[] parts = ArchiMateIds.requireXmlText(value).split("\r", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) xml.writeEntityRef("#xD");
            xml.writeCharacters(parts[i]);
        }
        xml.writeEndElement();
    }

    static void validateReferences(ArchiMateModel model) {
        Objects.requireNonNull(model, "model");
        ArchiMateIds.requireIdentity(model.id());
        if (model.title() == null) throw new IllegalArgumentException("Missing model title");
        if (model.elements().size() > ArchiMateSchema.MAX_ELEMENTS
                || model.relationships().size() > ArchiMateSchema.MAX_RELATIONSHIPS || model.views().size() > 32) {
            throw new IllegalArgumentException("Model exceeds bounded exchange profile");
        }
        Map<String, ArchiMateElement> elements = index(model.elements(), ArchiMateElement::id);
        if (elements.values().stream().anyMatch(element -> element.label() == null)) {
            throw new IllegalArgumentException("Missing element label");
        }
        Map<String, ArchiMateRelationship> relations = index(model.relationships(), ArchiMateRelationship::id);
        for (var relation : relations.values()) {
            if (relation.name() == null) throw new IllegalArgumentException("Missing relationship name");
            requireRef(elements, relation.sourceId()); requireRef(elements, relation.targetId());
        }
        model.organizations().values().forEach(ids -> ids.forEach(id -> requireRef(elements, id)));
        index(model.views(), ArchiMateView::id);
        for (var view : model.views()) {
            if (view.name() == null) throw new IllegalArgumentException("Missing view name");
            var nodes = index(view.nodes(), ArchiMateViewNode::id);
            if (nodes.values().stream().anyMatch(node -> node.label() == null)) throw new IllegalArgumentException("Missing view node label");
            index(view.connections(), ArchiMateViewConnection::id);
            nodes.values().forEach(node -> requireRef(elements, node.elementId()));
            for (var connection : view.connections()) {
                var relation = requireRef(relations, connection.relationshipId());
                var source = requireRef(nodes, connection.sourceNodeId());
                var target = requireRef(nodes, connection.targetNodeId());
                if (!relation.sourceId().equals(source.elementId()) || !relation.targetId().equals(target.elementId())) {
                    throw new IllegalArgumentException("View connection does not follow relationship endpoints: " + connection.id());
                }
            }
        }
    }

    private static <T> Map<String, T> index(List<T> values, java.util.function.Function<T, String> identity) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String id = identity.apply(value);
            ArchiMateIds.requireIdentity(id);
            if (result.putIfAbsent(id, value) != null) throw new IllegalArgumentException("Duplicate architecture identity: " + id);
        }
        return result;
    }

    private static <T> T requireRef(Map<String, T> values, String id) {
        T value = values.get(id);
        if (value == null) throw new IllegalArgumentException("Unresolved architecture reference: " + id);
        return value;
    }

    static String escapeXml(String text) {
        return ArchiMateIds.requireXmlText(text).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if (count >= ArchiMateSchema.MAX_BYTES) throw new IllegalArgumentException("Exchange XML exceeds byte limit");
            super.write(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > ArchiMateSchema.MAX_BYTES - count) throw new IllegalArgumentException("Exchange XML exceeds byte limit");
            super.write(bytes, offset, length);
        }
    }
}
