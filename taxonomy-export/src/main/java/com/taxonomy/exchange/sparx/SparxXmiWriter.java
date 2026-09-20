package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.taxonomy.exchange.sparx.SparxMappingProfile.*;

/** Writes only frozen, reviewed semantic values; raw source/layout XML is never replayed. */
final class SparxXmiWriter {
    byte[] write(ExchangeDocument source) {
        if (!PROFILE.equals(source.profile()) || !VERSION.equals(source.profileVersion()))
            throw ExchangeXml.invalid("PROFILE_VERSION_CHANGED", "Sparx output needs the exact supported mapping profile");
        if (source.artifacts().size() + source.relations().size() > ExchangeXml.MAX_ARTIFACTS)
            throw ExchangeXml.invalid("ITEM_LIMIT", "Sparx scope exceeds the supported item limit");
        Map<String, Artifact> objects = new TreeMap<>(); Set<String> taxonomyIds = new HashSet<>();
        for (Artifact artifact : source.artifacts()) {
            if (objects.putIfAbsent(guid(artifact.id()), artifact) != null) throw duplicate();
            if (!Set.of(ArtifactKind.SPECIFICATION, ArtifactKind.ELEMENT, ArtifactKind.REQUIREMENT).contains(artifact.kind()))
                throw ExchangeXml.invalid("SPARX_KIND_UNMAPPED", "Artifact kind is outside the semantic XMI subset");
            if (artifact.kind() == ArtifactKind.ELEMENT && (elementType(artifact.type(), null, null) == null
                    || !CANONICAL_TYPES.contains(artifact.extensions().getOrDefault("canonicalType", ""))
                    || !artifact.extensions().get("canonicalType").equals(elementType(artifact.type(),
                            artifact.extensions().get("stereotype"), artifact.attributes().get("tag:taxonomy.elementType")))))
                throw ExchangeXml.invalid("SPARX_ELEMENT_UNMAPPED", "Reject or explicitly remap unsupported element types before export");
            checkTags(artifact.attributes(), taxonomyIds);
        }
        String modelId = guid(source.metadata().get("identifier"));
        if (objects.containsKey(modelId)) throw duplicate();
        Set<String> allIds = new HashSet<>(objects.keySet()); allIds.add(modelId);
        for (Relation relation : source.relations()) {
            if (!allIds.add(guid(relation.id()))) throw duplicate();
            if (!objects.containsKey(relation.source()) || !objects.containsKey(relation.target()))
                throw ExchangeXml.invalid("SPARX_ENDPOINT_REQUIRED", "A reviewed connector endpoint is missing");
            if (relationType(relation.type()) == null
                    || !relationType(relation.type()).equals(relation.extensions().get("canonicalType")))
                throw ExchangeXml.invalid("SPARX_RELATION_UNMAPPED", "Reject or remap unsupported connectors before export");
            checkTags(relation.attributes(), taxonomyIds);
        }
        Map<String, Placement> byId = new HashMap<>(), byObject = new HashMap<>();
        for (Placement p : source.placements()) {
            if (byId.putIfAbsent(p.id(), p) != null || byObject.putIfAbsent(p.artifactId(), p) != null) throw duplicate();
            if (!modelId.equals(p.containerId()) || !objects.containsKey(p.artifactId()))
                throw ExchangeXml.invalid("HIERARCHY_REFERENCE", "A reviewed occurrence has a missing model or artifact");
        }
        for (Placement p : source.placements()) {
            Set<String> visited = new HashSet<>(); Placement next = p;
            while (next.parentId() != null) {
                if (!visited.add(next.id()) || visited.size() > 80)
                    throw ExchangeXml.invalid("HIERARCHY_CYCLE", "Package hierarchy is cyclic or too deep");
                next = byId.get(next.parentId());
                if (next == null || objects.get(next.artifactId()).kind() != ArtifactKind.SPECIFICATION)
                    throw ExchangeXml.invalid("HIERARCHY_REFERENCE", "A reviewed parent package is missing");
            }
        }
        var document = ExchangeXml.parse(("<xmi:XMI xmlns:xmi=\"" + XMI + "\" xmlns:uml=\"" + UML + "\" xmi:version=\"2.1\"/>").getBytes(StandardCharsets.UTF_8));
        Element root = document.getDocumentElement();
        Element documentation = ExchangeXml.append(root, XMI, "xmi:Documentation");
        documentation.setAttribute("exporter", "Taxonomy"); documentation.setAttribute("exporterVersion", PROFILE + "@" + VERSION);
        Element model = ExchangeXml.append(root, UML, "uml:Model"); model.setAttributeNS(XMI, "xmi:id", xmiId(modelId, true));
        model.setAttribute("name", source.metadata().getOrDefault("title", "Taxonomy"));
        Element extension = ExchangeXml.append(root, XMI, "xmi:Extension"); extension.setAttribute("extender", "Enterprise Architect");
        Element elements = ExchangeXml.append(extension, null, "elements"), connectors = ExchangeXml.append(extension, null, "connectors");
        Map<String, Element> nodes = new HashMap<>();
        for (Artifact artifact : objects.values()) {
            Element node = document.createElement("packagedElement"); nodes.put(artifact.id(), node);
            boolean pkg = artifact.kind() == ArtifactKind.SPECIFICATION;
            node.setAttributeNS(XMI, "xmi:id", xmiId(artifact.id(), pkg));
            node.setAttributeNS(XMI, "xmi:type", "uml:" + (pkg ? "Package" : artifact.type()));
            node.setAttribute("name", artifact.title());
            if (!artifact.text().isEmpty()) {
                Element comment = ExchangeXml.append(node, null, "ownedComment");
                comment.setAttributeNS(XMI, "xmi:type", "uml:Comment");
                comment.setAttributeNS(XMI, "xmi:id", xmiId(artifact.id(), pkg) + "_comment");
                ExchangeXml.text(comment, null, "body", artifact.text());
            }
            Element detail = detail(elements, "element", artifact.id(), pkg);
            Element properties = properties(detail, artifact.attributes()); properties.setAttribute("documentation", artifact.text());
            if (artifact.extensions().containsKey("stereotype")) properties.setAttribute("stereotype", artifact.extensions().get("stereotype"));
            if (artifact.kind() == ArtifactKind.REQUIREMENT) properties.setAttribute("sType", "Requirement");
            tags(detail, artifact.attributes(), artifact.extensions());
        }
        // Create every node before attaching children, so parent order cannot affect the result.
        objects.values().stream().sorted(Comparator.comparingInt((Artifact a) -> byObject.containsKey(a.id()) ? byObject.get(a.id()).position() : Integer.MAX_VALUE)
                .thenComparing(Artifact::id)).forEach(artifact -> {
            Placement p = byObject.get(artifact.id());
            Element parent = p == null || p.parentId() == null ? model : nodes.get(byId.get(p.parentId()).artifactId());
            parent.appendChild(nodes.get(artifact.id()));
        });
        for (Relation relation : source.relations().stream().sorted(Comparator.comparing(Relation::id)).toList()) {
            Element node = ExchangeXml.append(model, null, "packagedElement");
            node.setAttributeNS(XMI, "xmi:id", xmiId(relation.id(), false));
            boolean association = Set.of("Association", "Composition").contains(relation.type());
            node.setAttributeNS(XMI, "xmi:type", "uml:" + (association ? "Association" : relation.type()));
            if (relation.attributes().containsKey("name")) node.setAttribute("name", relation.attributes().get("name"));
            if (association) {
                String prefix = xmiId(relation.id(), false);
                node.setAttribute("memberEnd", prefix + "_source " + prefix + "_target");
                Element sourceEnd = ExchangeXml.append(node, null, "ownedEnd"), targetEnd = ExchangeXml.append(node, null, "ownedEnd");
                sourceEnd.setAttributeNS(XMI, "xmi:id", prefix + "_source"); targetEnd.setAttributeNS(XMI, "xmi:id", prefix + "_target");
                sourceEnd.setAttribute("type", xmiId(relation.source(), objects.get(relation.source()).kind() == ArtifactKind.SPECIFICATION));
                targetEnd.setAttribute("type", xmiId(relation.target(), objects.get(relation.target()).kind() == ArtifactKind.SPECIFICATION));
                if (relation.type().equals("Composition")) targetEnd.setAttribute("aggregation", "composite");
            } else {
                node.setAttribute("client", xmiId(relation.source(), objects.get(relation.source()).kind() == ArtifactKind.SPECIFICATION));
                node.setAttribute("supplier", xmiId(relation.target(), objects.get(relation.target()).kind() == ArtifactKind.SPECIFICATION));
            }
            Element detail = detail(connectors, "connector", relation.id(), false);
            ExchangeXml.append(detail, null, "source").setAttributeNS(XMI, "xmi:idref", xmiId(relation.source(), objects.get(relation.source()).kind() == ArtifactKind.SPECIFICATION));
            ExchangeXml.append(detail, null, "target").setAttributeNS(XMI, "xmi:idref", xmiId(relation.target(), objects.get(relation.target()).kind() == ArtifactKind.SPECIFICATION));
            Element properties = properties(detail, relation.attributes()); properties.setAttribute("ea_type", relation.type());
            properties.setAttribute("direction", relation.extensions().getOrDefault("direction", "Source -> Destination"));
            if (relation.extensions().containsKey("stereotype")) properties.setAttribute("stereotype", relation.extensions().get("stereotype"));
            if (relation.attributes().containsKey("description")) properties.setAttribute("documentation", relation.attributes().get("description"));
            tags(detail, relation.attributes(), relation.extensions());
        }
        byte[] output = ExchangeXml.write(document);
        // Reparse with the same security and identity checks before any file can be delivered.
        new SparxXmiReader().read(output, source.externalVersion(), source.completeScope());
        return output;
    }

    private static Element detail(Element parent, String name, String id, boolean pkg) {
        Element result = ExchangeXml.append(parent, null, name); result.setAttributeNS(XMI, "xmi:idref", xmiId(id, pkg)); return result;
    }
    private static Element properties(Element parent, Map<String, String> attributes) {
        Element result = ExchangeXml.append(parent, null, "properties");
        new TreeMap<>(attributes).forEach((key, value) -> { if (key.startsWith("ea:")) result.setAttribute(key.substring(3), value); });
        return result;
    }
    private static void tags(Element parent, Map<String, String> attributes, Map<String, String> extensions) {
        Element tags = ExchangeXml.append(parent, null, "tags");
        Map<String, String> values = new TreeMap<>(attributes);
        extensions.forEach((key, value) -> { if (key.startsWith("taxonomy:")) values.put("tag:taxonomy." + key.substring(9), value); });
        values.forEach((key, value) -> {
            if (key.startsWith("tag:")) { Element tag = ExchangeXml.append(tags, null, "tag"); tag.setAttribute("name", key.substring(4)); tag.setAttribute("value", value); }
        });
    }
    private static void checkTags(Map<String, String> attributes, Set<String> identities) {
        String id = attributes.get("tag:taxonomy.id");
        if (id != null && !identities.add(guid(id))) throw duplicate();
        String profile = attributes.get("tag:taxonomy.mappingProfile");
        if (profile != null && !(PROFILE + "@" + VERSION).equals(profile))
            throw ExchangeXml.invalid("PROFILE_VERSION_CHANGED", "Tagged profile differs from the selected mapping profile");
    }
    private static RuntimeException duplicate() { return ExchangeXml.invalid("DUPLICATE_IDENTITY", "Duplicated EA GUID or Taxonomy identity in reviewed output"); }
}
