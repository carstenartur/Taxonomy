package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.taxonomy.exchange.sparx.SparxMappingProfile.*;

/** Bounded parser for the documented EA XMI 2.1 profile, not arbitrary UML/MDG semantics. */
final class SparxXmiReader {
    private final Map<String, Element> details = new HashMap<>();
    private final Set<String> identities = new HashSet<>(), taxonomyIds = new HashSet<>();
    private final List<Artifact> artifacts = new ArrayList<>();
    private final List<Relation> relations = new ArrayList<>();
    private final List<Placement> placements = new ArrayList<>();
    private final List<MappingLoss> losses = new ArrayList<>();
    private String modelId;

    ExchangeDocument read(byte[] content, String version, boolean complete) {
        var document = ExchangeXml.parse(content);
        Element root = document.getDocumentElement();
        if (!XMI.equals(root.getNamespaceURI()) || !"XMI".equals(root.getLocalName())
                || !"2.1".equals(root.getAttributeNS(XMI, "version")))
            throw ExchangeXml.invalid("SPARX_XMI_VERSION", "Select the documented EA XMI 2.1 export profile");
        List<Element> models = ExchangeXml.children(root).stream().filter(e -> UML.equals(e.getNamespaceURI()) && "Model".equals(e.getLocalName())).toList();
        if (models.size() != 1) throw ExchangeXml.invalid("SPARX_MODEL_SCOPE", "Exactly one UML model scope is required");
        for (Element extension : ExchangeXml.children(root)) if (XMI.equals(extension.getNamespaceURI()) && "Extension".equals(extension.getLocalName())) {
            if (!"Enterprise Architect".equals(extension.getAttribute("extender"))) {
                loss(null, "extension", "SPARX_EXTENSION_EXCLUDED", "Unrecognized producer extension is retained only in source evidence");
                continue;
            }
            for (Element section : ExchangeXml.children(extension)) {
                if (Set.of("elements", "connectors").contains(section.getLocalName())) {
                    for (Element item : ExchangeXml.children(section)) {
                        String id = guid(item.getAttributeNS(XMI, "idref"));
                        if (details.putIfAbsent(id, item) != null) throw duplicate();
                    }
                } else loss(null, section.getLocalName(), section.getLocalName().equals("diagrams") ? "SPARX_LAYOUT_EXCLUDED" : "SPARX_EXTENSION_EXCLUDED",
                        "Presentation or unsupported extension is retained only in source evidence and is not exported");
            }
        }
        Element model = models.getFirst(); modelId = guid(model.getAttributeNS(XMI, "id")); identities.add(modelId);
        walk(model, null);
        if (artifacts.size() + relations.size() > ExchangeXml.MAX_ARTIFACTS) throw ExchangeXml.invalid("ITEM_LIMIT", "Sparx scope exceeds the supported item limit");
        Set<String> objects = new HashSet<>(); artifacts.forEach(a -> objects.add(a.id()));
        for (Relation relation : relations) if (!objects.contains(relation.source()) || !objects.contains(relation.target()))
            throw ExchangeXml.invalid("SPARX_ENDPOINT_REQUIRED", "A connector endpoint is outside the supplied model scope");
        for (String id : details.keySet()) if (!identities.contains(id))
            loss(id, "extension", "SPARX_ORPHAN_EXTENSION", "An extension has no matching semantic object in this scope");
        artifacts.sort(Comparator.comparing(Artifact::id)); relations.sort(Comparator.comparing(Relation::id)); placements.sort(Comparator.comparing(Placement::id));
        return new ExchangeDocument(PROFILE, VERSION, version == null ? ReqifExchangeCodec.digest(content) : version, complete,
                new String(ExchangeXml.write(document), StandardCharsets.UTF_8), artifacts, relations, placements,
                Map.of("identifier", modelId, "title", model.getAttribute("name")), losses);
    }

    private void walk(Element parent, String parentPlacement) {
        int position = 0;
        for (Element node : ExchangeXml.children(parent)) {
            if (!"packagedElement".equals(node.getLocalName()) || node.getNamespaceURI() != null) {
                loss(null, node.getLocalName(), "SPARX_CONSTRUCT_EXCLUDED", "Unmapped UML construct is retained only in source evidence");
                continue;
            }
            String type = type(node), id = guid(node.getAttributeNS(XMI, "id"));
            if (!identities.add(id)) throw duplicate();
            Element detail = details.get(id), properties = ExchangeXml.child(detail, "properties");
            Map<String, String> attributes = tags(detail), extension = new TreeMap<>();
            for (String key : new ArrayList<>(attributes.keySet())) if (key.startsWith("tag:taxonomy.")
                    && !Set.of("tag:taxonomy.id", "tag:taxonomy.mappingProfile", "tag:taxonomy.elementType", "tag:taxonomy.relationType", "tag:taxonomy.lastSyncRevision").contains(key))
                extension.put("taxonomy:" + key.substring("tag:taxonomy.".length()), attributes.remove(key));
            String stereotype = properties == null ? "" : properties.getAttribute("stereotype");
            if (!stereotype.isEmpty()) extension.put("stereotype", stereotype);
            String description = properties == null ? "" : properties.getAttribute("documentation");
            if (description.isEmpty()) {
                Element comment = ExchangeXml.child(node, "ownedComment");
                if (comment != null) description = comment.hasAttribute("body") ? comment.getAttribute("body") : ExchangeXml.text(comment, "body");
            }
            copyProperties(properties, attributes);
            if (isRelation(type)) {
                String source = reference(detail, "source", node.getAttribute("client"));
                String target = reference(detail, "target", node.getAttribute("supplier"));
                if (type.equals("Association")) {
                    List<Element> ends = ExchangeXml.children(node).stream().filter(e -> "ownedEnd".equals(e.getLocalName())).toList();
                    if (source.isEmpty() && ends.size() == 2) { source = ends.get(0).getAttribute("type"); target = ends.get(1).getAttribute("type"); }
                }
                String eaType = properties != null && properties.hasAttribute("ea_type") ? properties.getAttribute("ea_type") : type;
                String canonical = attributes.getOrDefault("tag:taxonomy.relationType", relationType(eaType));
                if (canonical != null && eaRelation(canonical) != null) extension.put("canonicalType", canonical);
                else loss(id, "type", "SPARX_RELATION_UNMAPPED", "Connector needs an explicit supported mapping");
                String direction = properties == null ? "" : properties.getAttribute("direction");
                if (direction.isEmpty()) direction = type.equals("Association") ? "Unspecified" : "Source -> Destination";
                if (!Set.of("Unspecified", "Source -> Destination", "Destination -> Source", "Bi-Directional").contains(direction))
                    throw ExchangeXml.invalid("SPARX_DIRECTION", "Unsupported connector direction");
                extension.put("direction", direction);
                if (direction.equals("Bi-Directional"))
                    loss(id, "direction", "SPARX_DIRECTION_UNMAPPED", "One native relation cannot represent both directions; reject this connector before apply");
                if (!node.getAttribute("name").isEmpty()) attributes.put("name", node.getAttribute("name"));
                if (!description.isEmpty()) attributes.put("description", description);
                relations.add(new Relation(id, eaType, guid(source), guid(target), attributes, extension));
            } else {
                boolean requirement = stereotype.equalsIgnoreCase("requirement") || properties != null && "Requirement".equals(properties.getAttribute("sType"));
                ArtifactKind kind = type.equals("Package") ? ArtifactKind.SPECIFICATION : requirement ? ArtifactKind.REQUIREMENT : ArtifactKind.ELEMENT;
                String canonical = elementType(type, stereotype, attributes.get("tag:taxonomy.elementType"));
                if (kind == ArtifactKind.ELEMENT) {
                    if (canonical != null) extension.put("canonicalType", canonical);
                    else loss(id, "type", "SPARX_ELEMENT_UNMAPPED", "Element needs an explicit supported mapping");
                }
                artifacts.add(new Artifact(id, kind, type, node.getAttribute("name"), description, attributes, extension));
                String placement = "placement:" + id;
                placements.add(new Placement(placement, modelId, parentPlacement, id, position++, Map.of()));
                if (kind == ArtifactKind.SPECIFICATION) walk(node, placement);
                else for (Element child : ExchangeXml.children(node)) if (!"ownedComment".equals(child.getLocalName()))
                    loss(id, child.getLocalName(), "SPARX_FEATURE_EXCLUDED", "Feature is outside the v1 semantic subset and retained only in source evidence");
            }
            if (detail != null) for (Element child : ExchangeXml.children(detail)) {
                String feature = child.getLocalName();
                if (Set.of("source", "target").contains(feature)) {
                    for (Element endFeature : ExchangeXml.children(child))
                        loss(id, feature + "." + endFeature.getLocalName(), "SPARX_FEATURE_EXCLUDED", "Connector-end metadata is retained only in source evidence");
                    for (int i = 0; i < child.getAttributes().getLength(); i++) {
                        var attribute = child.getAttributes().item(i);
                        if (!(XMI.equals(attribute.getNamespaceURI()) && "idref".equals(attribute.getLocalName())))
                            loss(id, feature + "." + attribute.getNodeName(), "SPARX_PROPERTY_EXCLUDED", "Connector-end metadata is retained only in source evidence");
                    }
                } else if (!Set.of("properties", "tags").contains(feature))
                    loss(id, feature, "SPARX_FEATURE_EXCLUDED", "EA feature is retained only in source evidence");
            }
            for (int i = 0; i < node.getAttributes().getLength(); i++) {
                var a = node.getAttributes().item(i);
                if (!XMI.equals(a.getNamespaceURI()) && !Set.of("name", "client", "supplier").contains(a.getNodeName()))
                    loss(id, a.getNodeName(), "SPARX_PROPERTY_EXCLUDED", "Unmapped UML property is retained only in source evidence");
            }
        }
    }

    private Map<String, String> tags(Element detail) {
        Map<String, String> result = new TreeMap<>();
        for (Element tag : ExchangeXml.children(ExchangeXml.child(detail, "tags"))) {
            String name = tag.getAttribute("name"), value = tag.getAttribute("value");
            if (name.isBlank() || name.length() > 240 || value.length() > 100000 || result.putIfAbsent("tag:" + name, value) != null)
                throw ExchangeXml.invalid("SPARX_TAG_INVALID", "Tagged values need unique bounded names and values");
            if ("taxonomy.mappingProfile".equals(name) && !(PROFILE + "@" + VERSION).equals(value))
                throw ExchangeXml.invalid("PROFILE_VERSION_CHANGED", "The tagged mapping profile differs from this connection profile");
            if ("taxonomy.id".equals(name) && !taxonomyIds.add(guid(value))) throw duplicate();
        }
        return result;
    }

    private static void copyProperties(Element properties, Map<String, String> result) {
        if (properties == null) return;
        for (int i = 0; i < properties.getAttributes().getLength(); i++) {
            var attribute = properties.getAttributes().item(i);
            if (!Set.of("documentation", "stereotype", "sType", "ea_type", "direction").contains(attribute.getNodeName()))
                result.put("ea:" + attribute.getNodeName(), attribute.getNodeValue());
        }
    }
    private static String type(Element node) {
        String qualified = node.getAttributeNS(XMI, "type"); int colon = qualified.indexOf(':');
        if (colon < 1 || !UML.equals(node.lookupNamespaceURI(qualified.substring(0, colon))))
            throw ExchangeXml.invalid("SPARX_UML_TYPE", "Semantic objects require a supported UML namespace");
        return qualified.substring(colon + 1);
    }
    private static String reference(Element detail, String side, String fallback) {
        Element reference = ExchangeXml.child(detail, side);
        return reference == null ? fallback : reference.getAttributeNS(XMI, "idref");
    }
    private void loss(String id, String field, String code, String detail) { losses.add(new MappingLoss(id, field, code, LossDisposition.UNSUPPORTED, detail)); }
    private static RuntimeException duplicate() { return ExchangeXml.invalid("DUPLICATE_IDENTITY", "The scope contains a duplicated EA GUID or Taxonomy identity"); }
}
