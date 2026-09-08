package com.taxonomy.archimate.exchange;

import com.taxonomy.archimate.*;
import com.taxonomy.diagram.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import java.util.*;

/** Standards-aware, identity-preserving reader for the declared Taxonomy exchange profile. */
public final class ArchiMateExchangeReader {
    public ArchiMateModel read(byte[] xml) {
        return read(ArchiMateSchema.parse(xml));
    }

    public ArchiMateModel read(Document document) {
        Element root = document.getDocumentElement();
        requireSupportedStructure(root);
        Map<String, Definition> definitions = definitions(root);
        Map<String, ArchiMateProperty> modelProperties = properties(root, definitions);
        if (!ArchiMateExchangeProfile.VERSION.equals(value(modelProperties, "taxonomy.mappingProfile"))) {
            throw new IllegalArgumentException("Unsupported Taxonomy ArchiMate exchange profile");
        }
        String modelId = identity(root, "model", modelProperties);
        modelProperties.remove("taxonomy.id");
        List<ArchiMateLoss> losses = losses(modelProperties);
        List<ArchiMateElement> elements = new ArrayList<>();
        for (Element element : children(child(root, "elements"), "element")) {
            Map<String, ArchiMateProperty> props = properties(element, definitions);
            String id = identity(element, "element", props);
            props.remove("taxonomy.id");
            var mapping = ArchiMateExchangeProfile.element(value(props, "taxonomy.type"));
            String type = type(element);
            if (!mapping.targetType().equals(type)) throw new IllegalArgumentException("Element type conflicts with mapping profile: " + id);
            elements.add(new ArchiMateElement(id, content(element, "name"), type,
                    content(element, "documentation"), props));
        }
        List<ArchiMateRelationship> relationships = new ArrayList<>();
        for (Element relation : children(child(root, "relationships"), "relationship")) {
            Map<String, ArchiMateProperty> props = properties(relation, definitions);
            String id = identity(relation, "relationship", props);
            props.remove("taxonomy.id");
            var mapping = ArchiMateExchangeProfile.relationship(value(props, "taxonomy.type"));
            String accessType = relation.hasAttribute("accessType") ? relation.getAttribute("accessType") : null;
            // Schema validation materializes the XSD default. Unqualified Access
            // means the same thing whether a consumer writes that default or omits it.
            if (mapping.accessType() == null && "Access".equals(accessType)) accessType = null;
            String type = type(relation);
            if (!mapping.targetType().equals(type) || !Objects.equals(mapping.accessType(), accessType)) {
                throw new IllegalArgumentException("Relationship qualifier conflicts with mapping profile: " + id);
            }
            relationships.add(new ArchiMateRelationship(id, decode("element", relation.getAttribute("source")),
                    decode("element", relation.getAttribute("target")), type, accessType, content(relation, "name"), props));
        }
        Map<String, List<String>> organizations = new TreeMap<>();
        for (Element item : children(child(root, "organizations"), "item")) {
            String label = content(item, "label");
            List<String> ids = children(item, "item").stream()
                    .map(e -> decode("element", e.getAttribute("identifierRef"))).toList();
            if (label == null || organizations.putIfAbsent(label, ids) != null) {
                throw new IllegalArgumentException("Unsupported or duplicate organization group");
            }
        }
        List<ArchiMateView> views = new ArrayList<>();
        for (Element view : children(child(child(root, "views"), "diagrams"), "view")) {
            Map<String, ArchiMateProperty> viewProperties = properties(view, definitions);
            String viewId = identity(view, "view", viewProperties);
            viewProperties.remove("taxonomy.id");
            if (!viewProperties.isEmpty()) {
                throw new IllegalArgumentException("Unsupported view properties in " + viewId + ": " + viewProperties.keySet());
            }
            List<ArchiMateViewNode> nodes = new ArrayList<>();
            for (Element node : children(view, "node")) {
                if (!"Element".equals(type(node))) throw new IllegalArgumentException("Unsupported diagram node type");
                Element style = child(node, "style");
                Element color = child(style, "fillColor");
                if (color == null) throw new IllegalArgumentException("Missing profile node style");
                nodes.add(new ArchiMateViewNode(viewIdentity("node", node, viewId),
                        decode("element", node.getAttribute("elementRef")), integer(node, "x"), integer(node, "y"),
                        integer(node, "w"), integer(node, "h"), content(node, "label"), integer(color, "r"),
                        integer(color, "g"), integer(color, "b"), style.hasAttribute("lineWidth") ? integer(style, "lineWidth") : 1));
            }
            List<ArchiMateViewConnection> connections = new ArrayList<>();
            for (Element connection : children(view, "connection")) {
                if (!"Relationship".equals(type(connection))) throw new IllegalArgumentException("Unsupported connection type");
                connections.add(new ArchiMateViewConnection(viewIdentity("connection", connection, viewId),
                        decode("relationship", connection.getAttribute("relationshipRef")),
                        viewReference("node", connection.getAttribute("source"), viewId),
                        viewReference("node", connection.getAttribute("target"), viewId)));
            }
            views.add(new ArchiMateView(viewId, content(view, "name"), nodes, connections));
        }
        ArchiMateModel model = new ArchiMateModel(modelId, content(root, "name"), elements, relationships,
                organizations, views, modelProperties, losses);
        ArchiMateXmlExporter.validateReferences(model);
        toDiagram(model); // validate required property types and values before handing off to application code
        return model;
    }

    public DiagramModel toDiagram(ArchiMateModel model) {
        List<DiagramNode> nodes = model.elements().stream().map(element -> {
            var p = element.properties();
            return new DiagramNode(element.id(), element.label(), value(p, "taxonomy.type"),
                    number(p, "taxonomy.relevance"), flag(p, "taxonomy.anchor"), integer(p, "taxonomy.layer"),
                    integer(p, "taxonomy.depth"), flag(p, "taxonomy.selectedForImpact"),
                    optional(p, "taxonomy.parentId"), false);
        }).toList();
        List<DiagramEdge> edges = model.relationships().stream().map(relation -> {
            var p = relation.properties();
            return new DiagramEdge(relation.id(), relation.sourceId(), relation.targetId(),
                    value(p, "taxonomy.type"), number(p, "taxonomy.relevance"), optional(p, "taxonomy.relationCategory"));
        }).toList();
        DiagramLayout layout = model.properties().containsKey("taxonomy.layoutDirection")
                ? new DiagramLayout(value(model.properties(), "taxonomy.layoutDirection"), flag(model.properties(), "taxonomy.groupByLayer"))
                : null;
        return new DiagramModel(model.title(), nodes, edges, layout);
    }

    public static boolean hasTaxonomyProfile(Document document) {
        return definitions(document.getDocumentElement()).values().stream()
                .anyMatch(definition -> definition.name().startsWith("taxonomy."));
    }

    private static String viewIdentity(String kind, Element element, String viewId) {
        return viewReference(kind, element.getAttribute("identifier"), viewId);
    }

    private static String viewReference(String kind, String identifier, String viewId) {
        List<String> parts = ArchiMateIds.decode(kind, identifier);
        if (parts.size() != 2 || !parts.getFirst().equals(viewId)) throw new IllegalArgumentException("Cross-view identity reference");
        return parts.get(1);
    }

    private static String decode(String kind, String identifier) {
        List<String> parts = ArchiMateIds.decode(kind, identifier);
        if (parts.size() != 1) throw new IllegalArgumentException("Invalid identity arity");
        return parts.getFirst();
    }

    private static String identity(Element element, String kind, Map<String, ArchiMateProperty> props) {
        String decoded = decode(kind, element.getAttribute("identifier"));
        if (!decoded.equals(value(props, "taxonomy.id"))) throw new IllegalArgumentException("Conflicting original identity");
        return decoded;
    }

    private static Map<String, Definition> definitions(Element root) {
        Map<String, Definition> result = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Element definition : children(child(root, "propertyDefinitions"), "propertyDefinition")) {
            String name = content(definition, "name");
            if (name == null || !names.add(name)) throw new IllegalArgumentException("Duplicate or absent property name");
            if (result.putIfAbsent(definition.getAttribute("identifier"), new Definition(name, definition.getAttribute("type"))) != null) {
                throw new IllegalArgumentException("Duplicate property definition");
            }
        }
        return result;
    }

    private static Map<String, ArchiMateProperty> properties(Element parent, Map<String, Definition> definitions) {
        Map<String, ArchiMateProperty> result = new TreeMap<>();
        for (Element property : children(child(parent, "properties"), "property")) {
            Definition definition = definitions.get(property.getAttribute("propertyDefinitionRef"));
            List<Element> values = children(property, "value");
            if (definition == null || values.size() != 1) throw new IllegalArgumentException("Unsupported property cardinality or reference");
            if (result.putIfAbsent(definition.name(), new ArchiMateProperty(definition.type(), values.getFirst().getTextContent())) != null) {
                throw new IllegalArgumentException("Duplicate exchange property: " + definition.name());
            }
        }
        return result;
    }

    private static List<ArchiMateLoss> losses(Map<String, ArchiMateProperty> props) {
        List<ArchiMateLoss> losses = new ArrayList<>();
        for (int index = 0; props.containsKey("taxonomy.loss." + index + ".scope"); index++) {
            String prefix = "taxonomy.loss." + index + ".";
            losses.add(new ArchiMateLoss(value(props, prefix + "scope"), value(props, prefix + "id"),
                    value(props, prefix + "field"), value(props, prefix + "kind"), value(props, prefix + "rationale")));
            for (String field : List.of("scope", "id", "field", "kind", "rationale")) props.remove(prefix + field);
        }
        if (props.keySet().stream().anyMatch(key -> key.startsWith("taxonomy.loss."))) {
            throw new IllegalArgumentException("Incomplete or non-contiguous loss report");
        }
        return losses;
    }

    private static String value(Map<String, ArchiMateProperty> properties, String key) {
        return typed(properties, key, "string");
    }

    private static String optional(Map<String, ArchiMateProperty> properties, String key) {
        return properties.containsKey(key) ? value(properties, key) : null;
    }

    private static String typed(Map<String, ArchiMateProperty> properties, String key, String type) {
        ArchiMateProperty property = properties.get(key);
        if (property == null || !type.equals(property.type())) throw new IllegalArgumentException("Missing or wrongly typed property: " + key);
        return property.value();
    }

    private static double number(Map<String, ArchiMateProperty> properties, String key) {
        double result = Double.parseDouble(typed(properties, key, "number"));
        if (!Double.isFinite(result) || result < 0 || result > 1) throw new IllegalArgumentException("Invalid relevance property");
        return result;
    }

    private static int integer(Map<String, ArchiMateProperty> properties, String key) {
        return new java.math.BigDecimal(typed(properties, key, "number")).intValueExact();
    }

    private static boolean flag(Map<String, ArchiMateProperty> properties, String key) {
        return Boolean.parseBoolean(typed(properties, key, "boolean"));
    }

    private static int integer(Element element, String attribute) {
        return Integer.parseInt(element.getAttribute(attribute));
    }

    public static String type(Element element) {
        String value = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
        int colon = value.indexOf(':');
        String prefix = colon < 0 ? null : value.substring(0, colon);
        if (!ArchiMateSchema.NAMESPACE.equals(element.lookupNamespaceURI(prefix))) {
            throw new IllegalArgumentException("Unexpected ArchiMate type namespace");
        }
        return colon < 0 ? value : value.substring(colon + 1);
    }

    public static String content(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : child.getTextContent();
    }

    public static Element child(Element parent, String name) {
        List<Element> matches = children(parent, name);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    public static List<Element> children(Element parent, String name) {
        if (parent == null) return List.of();
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && ArchiMateSchema.NAMESPACE.equals(element.getNamespaceURI())
                    && name.equals(element.getLocalName())) result.add(element);
        }
        return result;
    }

    /** Fail closed on schema-valid constructs that this profile cannot reconstruct. */
    private static void requireSupportedStructure(Element element) {
        String name = element.getLocalName();
        if (element.hasAttributeNS(XMLConstants.XML_NS_URI, "lang")
                && !"en".equals(element.getAttributeNS(XMLConstants.XML_NS_URI, "lang"))) {
            throw new IllegalArgumentException("Unsupported language-tag projection");
        }
        if ("view".equals(name) && element.hasAttribute("viewpoint")
                && !"Layered".equals(element.getAttribute("viewpoint"))) {
            throw new IllegalArgumentException("Unsupported viewpoint projection");
        }
        Set<String> allowedChildren = switch (name) {
            case "model" -> Set.of("name", "properties", "elements", "relationships", "organizations", "propertyDefinitions", "views");
            case "elements" -> Set.of("element");
            case "element" -> Set.of("name", "documentation", "properties");
            case "relationships" -> Set.of("relationship");
            case "relationship" -> Set.of("name", "properties");
            case "organizations", "item" -> Set.of("item", "label");
            case "propertyDefinitions" -> Set.of("propertyDefinition");
            case "propertyDefinition" -> Set.of("name");
            case "properties" -> Set.of("property");
            case "property" -> Set.of("value");
            case "views" -> Set.of("diagrams");
            case "diagrams" -> Set.of("view");
            case "view" -> Set.of("name", "properties", "node", "connection");
            case "node" -> Set.of("label", "style");
            case "style" -> Set.of("fillColor");
            default -> Set.of();
        };
        Set<String> repeated = switch (name) {
            case "elements" -> Set.of("element");
            case "relationships" -> Set.of("relationship");
            case "organizations", "item" -> Set.of("item");
            case "propertyDefinitions" -> Set.of("propertyDefinition");
            case "properties" -> Set.of("property");
            case "diagrams" -> Set.of("view");
            case "view" -> Set.of("node", "connection");
            default -> Set.of();
        };
        Set<String> seen = new HashSet<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element child)) continue;
            if (!ArchiMateSchema.NAMESPACE.equals(child.getNamespaceURI()) || !allowedChildren.contains(child.getLocalName())
                    || !repeated.contains(child.getLocalName()) && !seen.add(child.getLocalName())) {
                throw new IllegalArgumentException("Unsupported exchange structure below " + name + ": " + child.getLocalName());
            }
            requireSupportedStructure(child);
        }
        Set<String> attributes = switch (name) {
            case "model", "element", "view", "propertyDefinition" -> Set.of("identifier", "type", "viewpoint");
            case "relationship" -> Set.of("identifier", "source", "target", "accessType");
            case "node" -> Set.of("identifier", "elementRef", "x", "y", "w", "h");
            case "connection" -> Set.of("identifier", "relationshipRef", "source", "target");
            case "style" -> Set.of("lineWidth");
            case "fillColor" -> Set.of("r", "g", "b");
            case "property" -> Set.of("propertyDefinitionRef");
            case "item" -> Set.of("identifierRef");
            default -> Set.of();
        };
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            var attribute = (org.w3c.dom.Attr) element.getAttributes().item(i);
            // XSD defaults are semantically normalized by the reader (for example opacity and Access).
            if (!attribute.getSpecified()) continue;
            String namespace = attribute.getNamespaceURI();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)
                    || XMLConstants.XML_NS_URI.equals(namespace) && "lang".equals(attribute.getLocalName())
                    || XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(namespace)
                    && Set.of("type", "schemaLocation").contains(attribute.getLocalName())) continue;
            if (namespace != null || !attributes.contains(attribute.getName())) {
                throw new IllegalArgumentException("Unsupported exchange attribute on " + name + ": " + attribute.getName());
            }
        }
    }

    private record Definition(String name, String type) { }
}
