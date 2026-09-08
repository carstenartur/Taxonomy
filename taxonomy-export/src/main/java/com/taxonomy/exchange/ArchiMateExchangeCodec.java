package com.taxonomy.exchange;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.taxonomy.exchange.ExchangeXml.*;

/** Versioned semantic exchange profile. Names are never used as identities or fuzzy-matched to catalogue nodes. */
public final class ArchiMateExchangeCodec {
    public static final String PROFILE = "archimate-3.1";
    public static final String VERSION = "1";
    public static final String NS = "http://www.opengroup.org/xsd/archimate/3.0/";
    private static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    public static final Map<String, String> ELEMENT_TYPES = Map.ofEntries(
            Map.entry("Capability", "Capability"), Map.entry("BusinessProcess", "Process"),
            Map.entry("BusinessRole", "BusinessRole"), Map.entry("ApplicationService", "CoreService"),
            Map.entry("BusinessService", "COIService"), Map.entry("CommunicationNetwork", "CommunicationsService"),
            Map.entry("ApplicationComponent", "UserApplication"), Map.entry("DataObject", "InformationProduct"),
            Map.entry("BusinessObject", "InformationProduct"), Map.entry("TechnologyService", "CoreService"));
    public static final Map<String, String> RELATION_TYPES = Map.ofEntries(
            Map.entry("Realization", "REALIZES"), Map.entry("Serving", "SUPPORTS"),
            Map.entry("Assignment", "ASSIGNED_TO"), Map.entry("Flow", "COMMUNICATES_WITH"),
            Map.entry("Composition", "CONTAINS"), Map.entry("Association", "RELATED_TO"), Map.entry("Access", "CONSUMES"));

    public ExchangeDocument read(byte[] bytes, String externalVersion, boolean complete) {
        Document doc = parse(bytes);
        if (!NS.equals(doc.getDocumentElement().getNamespaceURI()) || !"model".equals(doc.getDocumentElement().getLocalName()))
            throw invalid("WRONG_FORMAT", "Expected an ArchiMate Model Exchange document");
        validate(doc, PROFILE); checkReferences(doc);
        Map<String, String> propertyNames = new LinkedHashMap<>();
        for (Element definition : all(doc, NS, "propertyDefinition")) propertyNames.put(required(definition, "identifier"), text(definition, "name"));
        List<Artifact> artifacts = new ArrayList<>(); List<MappingLoss> losses = new ArrayList<>();
        for (Element element : children(child(doc.getDocumentElement(), "elements"))) {
            String id = required(element, "identifier"), type = type(element);
            Map<String, String> properties = properties(element); Map<String, String> extensions = extensions(element, propertyNames);
            String mapped = canonicalType(properties, propertyNames, "Taxonomy.ElementType", ELEMENT_TYPES.get(type));
            if (mapped == null) losses.add(loss(id, "type", "UNSUPPORTED_ELEMENT_TYPE", LossDisposition.UNSUPPORTED,
                    "The source element type has no declared canonical mapping; source evidence is retained"));
            else extensions.put("canonicalType", mapped);
            artifacts.add(new Artifact(id, ArtifactKind.ELEMENT, type, text(element, "name"), text(element, "documentation"), properties, extensions));
        }
        List<Relation> relations = new ArrayList<>();
        for (Element element : children(child(doc.getDocumentElement(), "relationships"))) {
            String id = required(element, "identifier"), type = type(element);
            Map<String, String> properties = properties(element); Map<String, String> extensions = extensions(element, propertyNames);
            String mapped = canonicalType(properties, propertyNames, "Taxonomy.RelationType", RELATION_TYPES.get(type));
            if (type.equals("Access") && element.getAttribute("accessType").equals("Write")) mapped = "PRODUCES";
            if (mapped == null) losses.add(loss(id, "type", "UNSUPPORTED_RELATION_TYPE", LossDisposition.UNSUPPORTED,
                    "The source relationship has no declared canonical mapping; no Association fallback is applied"));
            else extensions.put("canonicalType", mapped);
            for (String attribute : List.of("accessType", "isDirected", "influenceStrength")) if (element.hasAttribute(attribute)) extensions.put(attribute, element.getAttribute(attribute));
            relations.add(new Relation(id, type, required(element, "source"), required(element, "target"), properties, extensions));
        }
        List<Placement> placements = new ArrayList<>();
        for (Element view : children(child(child(doc.getDocumentElement(), "views"), "diagrams"))) {
            String id = required(view, "identifier");
            Map<String, String> viewExtensions = extensions(view, propertyNames);
            Document evidence = parse(("<connections xmlns=\"" + NS + "\"/>").getBytes(StandardCharsets.UTF_8));
            for (Element connection : children(view)) if (connection.getLocalName().equals("connection")) evidence.getDocumentElement().appendChild(evidence.importNode(connection, true));
            viewExtensions.put("connectionsXml", xml(evidence));
            artifacts.add(new Artifact(id, ArtifactKind.VIEW, "Diagram", text(view, "name"), text(view, "documentation"), properties(view), viewExtensions));
            readViewNodes(view, id, null, placements, losses);
            if (view.getElementsByTagNameNS(NS, "style").getLength() > 0) losses.add(loss(id, "style", "PRESENTATION_PRESERVED", LossDisposition.PRESERVED_EXTENSION,
                    "Layout and styling are retained separately from canonical architecture semantics"));
        }
        readOrganizations(child(doc.getDocumentElement(), "organizations"), null, "org", placements);
        if (artifacts.size() > MAX_ARTIFACTS || relations.size() > 4 * MAX_ARTIFACTS || placements.size() > 8 * MAX_ARTIFACTS)
            throw invalid("ITEM_LIMIT", "ArchiMate exceeds the supported item count");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("identifier", required(doc.getDocumentElement(), "identifier")); metadata.put("title", text(doc.getDocumentElement(), "name"));
        for (String name : List.of("propertyDefinitions", "organizations", "metadata")) {
            Element node = child(doc.getDocumentElement(), name); if (node != null) metadata.put(name, xml(node));
        }
        return new ExchangeDocument(PROFILE, VERSION, externalVersion == null ? ReqifExchangeCodec.digest(bytes) : externalVersion,
                complete, xml(doc), artifacts, relations, placements, metadata, losses);
    }

    public byte[] write(ExchangeDocument source) {
        Document doc = source.source() == null || source.source().isBlank()
                ? parse(("<model xmlns=\"" + NS + "\" xmlns:xsi=\"" + XSI + "\" identifier=\"" + safeId(source.metadata().getOrDefault("identifier", "taxonomy-model")) + "\"/>").getBytes(StandardCharsets.UTF_8))
                : parse(source.source().getBytes(StandardCharsets.UTF_8));
        Element root = doc.getDocumentElement();
        for (String tag : List.of("elements", "relationships", "views")) { Element old = child(root, tag); if (old != null) root.removeChild(old); }
        if (child(root, "name") == null) text(root, NS, "name", source.metadata().getOrDefault("title", "Architecture"));
        // The schema orders top-level element groups; keep evidence metadata, definitions and organizations after relationships.
        List<Node> tail = children(root).stream().filter(e -> !List.of("name", "documentation", "properties").contains(e.getLocalName())).map(e -> (Node) e).toList();
        tail.forEach(root::removeChild);
        Element elements = append(root, NS, "elements");
        for (Artifact artifact : source.artifacts().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT).sorted(Comparator.comparing(Artifact::id)).toList()) {
            Element node = object(elements, "element", artifact.id(), artifact.type(), artifact.extensions());
            replaceText(node, "name", artifact.title()); replaceText(node, "documentation", artifact.text());
            replaceProperties(node, artifact.attributes());
        }
        Element relations = append(root, NS, "relationships");
        for (Relation relation : source.relations().stream().sorted(Comparator.comparing(Relation::id)).toList()) {
            Element node = object(relations, "relationship", relation.id(), relation.type(), relation.extensions());
            node.setAttribute("source", relation.source()); node.setAttribute("target", relation.target());
            for (String name : List.of("accessType", "isDirected", "influenceStrength")) if (relation.extensions().containsKey(name)) node.setAttribute(name, relation.extensions().get(name));
            replaceProperties(node, relation.attributes());
        }
        tail.forEach(root::appendChild);
        for (String name : List.of("propertyDefinitions", "metadata")) if (source.metadata().containsKey(name)) {
            Element previous = child(root, name); if (previous != null) root.removeChild(previous);
            root.appendChild(doc.importNode(parse(source.metadata().get(name).getBytes(StandardCharsets.UTF_8)).getDocumentElement(), true));
        }
        for (Artifact artifact : source.artifacts()) if (artifact.kind() == ArtifactKind.ELEMENT)
            taxonomyProperties(root, find(elements, artifact.id()), artifact.extensions(), "ElementType");
        for (Relation relation : source.relations()) taxonomyProperties(root, find(relations, relation.id()), relation.extensions(), "RelationType");
        Element organizations = child(root, "organizations"); if (organizations != null) root.removeChild(organizations);
        List<Placement> folders = source.placements().stream().filter(p -> p.containerId().equals("organizations")).toList();
        if (!folders.isEmpty()) writePlacements(append(root, NS, "organizations"), null, folders, true, new HashSet<>());
        List<Artifact> views = source.artifacts().stream().filter(a -> a.kind() == ArtifactKind.VIEW).toList();
        if (!views.isEmpty()) {
            Element diagrams = append(append(root, NS, "views"), NS, "diagrams");
            for (Artifact view : views) {
                Element node = object(diagrams, "view", view.id(), null, view.extensions());
                if (!node.hasAttributeNS(XSI, "type")) node.setAttributeNS(XSI, "xsi:type", "Diagram");
                replaceText(node, "name", view.title()); replaceText(node, "documentation", view.text()); replaceProperties(node, view.attributes());
                for (Element old : children(node)) if (Set.of("node", "connection").contains(old.getLocalName())) node.removeChild(old);
                writePlacements(node, null, source.placements().stream().filter(p -> p.containerId().equals(view.id())).toList(), false, new HashSet<>());
                if (view.extensions().containsKey("connectionsXml")) for (Element connection : children(parse(view.extensions().get("connectionsXml").getBytes(StandardCharsets.UTF_8)).getDocumentElement()))
                    node.appendChild(doc.importNode(connection, true));
            }
        }
        // Canonical group order required by the Model and Diagram schemas.
        for (String name : List.of("name", "documentation", "properties", "metadata", "elements", "relationships", "organizations", "propertyDefinitions", "views")) {
            List<Element> nodes = children(root).stream().filter(n -> n.getLocalName().equals(name)).toList(); nodes.forEach(root::appendChild);
        }
        if (children(elements).isEmpty()) root.removeChild(elements);
        if (children(relations).isEmpty()) root.removeChild(relations);
        validate(doc, PROFILE); checkReferences(doc); return ExchangeXml.write(doc);
    }

    private static Element find(Element group, String id) { return children(group).stream().filter(e -> id.equals(e.getAttribute("identifier"))).findFirst().orElseThrow(); }
    private static void taxonomyProperties(Element root, Element node, Map<String, String> extensions, String kind) {
        Map<String, String> mapped = new java.util.TreeMap<>();
        if (extensions.containsKey("canonicalType")) mapped.put(kind, extensions.get("canonicalType"));
        extensions.forEach((key, value) -> { if (key.startsWith("taxonomy:")) mapped.put(key.substring(9), value); });
        if (mapped.isEmpty()) return;
        Element definitions = child(root, "propertyDefinitions"); if (definitions == null) definitions = append(root, NS, "propertyDefinitions");
        Element properties = child(node, "properties"); if (properties == null) properties = append(node, NS, "properties");
        for (var entry : mapped.entrySet()) {
            String name = "Taxonomy." + entry.getKey();
            Element definition = children(definitions).stream().filter(d -> name.equals(text(d, "name"))).findFirst().orElse(null);
            String id;
            if (definition != null) id = definition.getAttribute("identifier");
            else {
                id = "taxonomy-property-" + entry.getKey();
                final String candidate = id;
                if (all(root.getOwnerDocument(), NS, "*").stream().anyMatch(e -> candidate.equals(e.getAttribute("identifier"))))
                    throw invalid("TYPE_IDENTITY_COLLISION", "An exchange identity collides with a Taxonomy provenance property");
                definition = append(definitions, NS, "propertyDefinition"); definition.setAttribute("identifier", id); definition.setAttribute("type", "string"); text(definition, NS, "name", name);
            }
            for (Element old : children(properties)) if (id.equals(old.getAttribute("propertyDefinitionRef"))) properties.removeChild(old);
            Element property = append(properties, NS, "property"); property.setAttribute("propertyDefinitionRef", id); text(property, NS, "value", entry.getValue());
        }
    }

    private static void writePlacements(Element parent, String parentId, List<Placement> placements, boolean folders, Set<String> written) {
        for (Placement placement : placements.stream().filter(p -> java.util.Objects.equals(parentId, p.parentId()))
                .sorted(Comparator.comparingInt(Placement::position).thenComparing(Placement::id)).toList()) {
            if (!written.add(placement.id()) || written.size() > 80000) throw invalid("HIERARCHY_CYCLE", "Repeated exchange placement");
            Element node;
            if (placement.attributes().containsKey("nodeXml")) {
                node = (Element) parent.getOwnerDocument().importNode(parse(placement.attributes().get("nodeXml").getBytes(StandardCharsets.UTF_8)).getDocumentElement(), true);
                if (!NS.equals(node.getNamespaceURI()) || !(folders ? "item" : "node").equals(node.getLocalName())) throw invalid("EVIDENCE_TYPE", "Incompatible placement evidence");
                parent.appendChild(node);
            } else node = append(parent, NS, folders ? "item" : "node");
            if (folders) {
                node.removeAttribute("identifierRef");
                if (placement.artifactId() != null && !placement.artifactId().isEmpty()) node.setAttribute("identifierRef", placement.artifactId());
                replaceText(node, "label", placement.attributes().getOrDefault("label", ""));
            } else {
                node.setAttribute("identifier", placement.id()); node.removeAttribute("elementRef");
                if (placement.artifactId() != null && !placement.artifactId().isEmpty()) node.setAttribute("elementRef", placement.artifactId());
                if (!node.hasAttributeNS(XSI, "type")) node.setAttributeNS(XSI, "xsi:type", placement.artifactId() == null || placement.artifactId().isEmpty() ? "Container" : "Element");
                for (String coordinate : List.of("x", "y", "w", "h")) node.setAttribute(coordinate, placement.attributes().getOrDefault(coordinate, "0"));
            }
            writePlacements(node, placement.id(), placements, folders, written);
        }
        if (parentId == null && written.size() != placements.size()) throw invalid("HIERARCHY_CYCLE", "A placement has a missing parent or a cycle");
    }

    private static Element object(Element parent, String name, String id, String type, Map<String, String> extensions) {
        Element node;
        if (extensions.containsKey("xml")) {
            node = (Element) parent.getOwnerDocument().importNode(parse(extensions.get("xml").getBytes(StandardCharsets.UTF_8)).getDocumentElement(), true);
            if (!name.equals(node.getLocalName()) || !NS.equals(node.getNamespaceURI())) throw invalid("EVIDENCE_TYPE", "Exchange evidence has an incompatible type");
            parent.appendChild(node);
        } else node = append(parent, NS, name);
        node.setAttribute("identifier", safeId(id)); if (type != null) node.setAttributeNS(XSI, "xsi:type", type);
        return node;
    }
    private static void replaceText(Element node, String name, String value) {
        List<Element> old = children(node).stream().filter(e -> name.equals(e.getLocalName())).toList();
        if (old.size() == 1 && old.getFirst().getTextContent().equals(value)) return;
        // Other language variants remain untouched; the first supported display value is the mapped field.
        if (!old.isEmpty()) old.getFirst().setTextContent(value);
        else if (value != null && !value.isEmpty()) {
            Element created = node.getOwnerDocument().createElementNS(NS, name); created.setTextContent(value);
            Node before = name.equals("name") ? node.getFirstChild() : child(node, "properties");
            node.insertBefore(created, before);
        }
    }
    private static void replaceProperties(Element node, Map<String, String> properties) {
        Element old = child(node, "properties"); if (old != null) node.removeChild(old);
        if (!properties.isEmpty()) {
            Element group = node.getOwnerDocument().createElementNS(NS, "properties");
            Node before = children(node).stream().filter(e -> List.of("node", "connection").contains(e.getLocalName())).findFirst().orElse(null);
            node.insertBefore(group, before);
            properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                Element property = append(group, NS, "property"); property.setAttribute("propertyDefinitionRef", entry.getKey());
                // Values are retained as XML to preserve language and multiple-value semantics.
                Document values = parse(entry.getValue().getBytes(StandardCharsets.UTF_8));
                for (Element value : children(values.getDocumentElement())) property.appendChild(node.getOwnerDocument().importNode(value, true));
            });
        }
    }
    private static Map<String, String> properties(Element node) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element property : children(child(node, "properties"))) {
            String id = required(property, "propertyDefinitionRef");
            if (result.putIfAbsent(id, xml(property)) != null) throw invalid("DUPLICATE_PROPERTY", "Repeated property definition on one exchange object");
        }
        return result;
    }
    private static Map<String, String> extensions(Element element, Map<String, String> names) {
        Map<String, String> extensions = new LinkedHashMap<>(); extensions.put("xml", xml(element));
        properties(element).keySet().forEach(id -> { if (names.containsKey(id)) extensions.put("definition:" + id, names.get(id)); }); return extensions;
    }
    private static String canonicalType(Map<String, String> properties, Map<String, String> names, String label, String fallback) {
        for (var entry : names.entrySet()) if (entry.getValue().equals(label) && properties.containsKey(entry.getKey()))
            return text(parse(properties.get(entry.getKey()).getBytes(StandardCharsets.UTF_8)).getDocumentElement(), "value");
        return fallback;
    }
    private static String type(Element node) {
        String type = node.getAttributeNS(XSI, "type"); return type.substring(type.indexOf(':') + 1);
    }
    private static void readViewNodes(Element parent, String view, String parentId, List<Placement> placements, List<MappingLoss> losses) {
        int position = 0;
        for (Element node : children(parent)) if (node.getLocalName().equals("node")) {
            String id = required(node, "identifier"); Map<String, String> attributes = new LinkedHashMap<>();
            for (String name : List.of("x", "y", "w", "h")) attributes.put(name, node.getAttribute(name));
            attributes.put("nodeXml", shallowEvidence(node, "node"));
            placements.add(new Placement(id, view, parentId, node.getAttribute("elementRef"), position++, attributes));
            if (!node.hasAttribute("elementRef")) losses.add(loss(id, "viewNode", "VISUAL_NODE_PRESERVED", LossDisposition.PRESERVED_EXTENSION, "Visual-only node is retained without inventing an architecture element"));
            readViewNodes(node, view, id, placements, losses);
        }
    }
    private static void readOrganizations(Element parent, String parentId, String path, List<Placement> placements) {
        int position = 0;
        for (Element item : children(parent)) if (item.getLocalName().equals("item")) {
            String id = path + "-" + position;
            placements.add(new Placement(id, "organizations", parentId, item.getAttribute("identifierRef"), position++, Map.of("label", text(item, "label"), "nodeXml", shallowEvidence(item, "item"))));
            readOrganizations(item, id, id, placements);
        }
    }
    private static String shallowEvidence(Element original, String childKind) {
        Element copy = (Element) original.cloneNode(true);
        for (Element child : children(copy)) if (child.getLocalName().equals(childKind)) copy.removeChild(child);
        return xml(copy);
    }
    private static List<Element> descendants(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Element child : children(parent)) { result.add(child); result.addAll(descendants(child)); }
        return result;
    }
    private static void checkReferences(Document doc) {
        Set<String> ids = new HashSet<>(); List<Element> elements = all(doc, NS, "*");
        for (Element element : elements) if (element.hasAttribute("identifier") && !ids.add(required(element, "identifier")))
            throw invalid("DUPLICATE_IDENTITY", "ArchiMate reuses a stable identity");
        for (Element element : elements) for (String attribute : List.of("source", "target", "elementRef", "relationshipRef", "identifierRef", "propertyDefinitionRef"))
            if (element.hasAttribute(attribute) && !ids.contains(element.getAttribute(attribute))) throw invalid("DANGLING_REFERENCE", "ArchiMate contains a missing reference");
    }
    private static String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_.-]{0,255}")) throw invalid("INVALID_IDENTITY", "Exchange identifier is outside the supported XML identity profile");
        return id;
    }
    private static MappingLoss loss(String id, String field, String code, LossDisposition disposition, String detail) { return new MappingLoss(id, field, code, disposition, detail); }
}
