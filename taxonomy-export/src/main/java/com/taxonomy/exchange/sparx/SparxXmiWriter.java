package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.taxonomy.exchange.sparx.SparxMappingProfile.*;

/** Writes only frozen, reviewed semantic values; raw source/layout XML is never replayed. */
final class SparxXmiWriter {
    private final String version;
    SparxXmiWriter() { this("1"); }
    SparxXmiWriter(String version) { this.version = version; }
    byte[] write(ExchangeDocument source) {
        if (!PROFILE.equals(source.profile()) || !version.equals(source.profileVersion()))
            throw ExchangeXml.invalid("PROFILE_VERSION_CHANGED", "Sparx output needs the exact supported mapping profile");
        var validated = SparxModelValidator.model(source);
        var objects = validated.objects();
        if (version.equals("2")) for (MappingLoss loss : source.losses()) {
            Artifact feature = loss.artifactId() == null ? null : objects.get(loss.artifactId());
            if (feature != null && feature.kind() == ArtifactKind.FEATURE && loss.disposition() == LossDisposition.UNSUPPORTED)
                throw ExchangeXml.invalid("SPARX_FEATURE_EXPORT_UNSUPPORTED", "Reviewed feature contains unsupported content; reject it before lossy delivery");
        }
        var modelId = validated.modelId();
        var byId = validated.byId();
        var byObject = validated.byObject();
        var document = ExchangeXml.parse(("<xmi:XMI xmlns:xmi=\"" + XMI + "\" xmlns:uml=\"" + UML + "\" xmi:version=\"2.1\"/>").getBytes(StandardCharsets.UTF_8));
        Element root = document.getDocumentElement();
        Element documentation = ExchangeXml.append(root, XMI, "xmi:Documentation");
        documentation.setAttribute("exporter", "Taxonomy"); documentation.setAttribute("exporterVersion", PROFILE + "@" + version);
        Element model = ExchangeXml.append(root, UML, "uml:Model"); model.setAttributeNS(XMI, "xmi:id", xmiId(modelId, true));
        model.setAttribute("name", source.metadata().getOrDefault("title", "Taxonomy"));
        Element extension = ExchangeXml.append(root, XMI, "xmi:Extension"); extension.setAttribute("extender", "Enterprise Architect");
        Element elements = ExchangeXml.append(extension, null, "elements"), connectors = ExchangeXml.append(extension, null, "connectors");
        Map<String, Element> nodes = new HashMap<>();
        for (Artifact artifact : objects.values()) {
            if (artifact.kind() == ArtifactKind.FEATURE) continue;
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
            if (version.equals("2")) evidence(detail, artifact.attributes(), artifact.extensions());
        }
        // Create every node before attaching children, so parent order cannot affect the result.
        objects.values().stream().filter(a -> a.kind() != ArtifactKind.FEATURE).sorted(Comparator.comparingInt((Artifact a) -> byObject.containsKey(a.id()) ? byObject.get(a.id()).position() : Integer.MAX_VALUE)
                .thenComparing(Artifact::id)).forEach(artifact -> {
            Placement p = byObject.get(artifact.id());
            Element parent = p == null || p.parentId() == null ? model : nodes.get(byId.get(p.parentId()).artifactId());
            parent.appendChild(nodes.get(artifact.id()));
        });
        if (version.equals("2")) writeFeatures(source, elements, nodes);
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
            if (version.equals("2")) evidence(detail, relation.attributes(), relation.extensions());
        }
        byte[] output = ExchangeXml.write(document);
        // Reparse with the same security and identity checks before any file can be delivered.
        new SparxXmiCodec(version).read(output, source.externalVersion(), source.completeScope());
        return output;
    }

    private static void evidence(Element parent, Map<String,String> attributes, Map<String,String> extensions) {
        Element evidence = ExchangeXml.append(parent, null, "evidence");
        new TreeMap<>(attributes).forEach((key,value) -> field(evidence,"attribute",key,value));
        new TreeMap<>(extensions).forEach((key,value) -> field(evidence,"extension",key,value));
    }
    private static void field(Element parent,String type,String key,String value) {
        Element field=ExchangeXml.append(parent,null,type); field.setAttribute("key",key); field.setAttribute("value",value);
    }
    private static void writeFeatures(ExchangeDocument source, Element elements, Map<String,Element> nodes) {
        Map<String,Element> details = new HashMap<>();
        for (Element d : ExchangeXml.children(elements)) details.put(guid(d.getAttributeNS(XMI,"idref")),d);
        var features=source.artifacts().stream().filter(a -> a.kind()==ArtifactKind.FEATURE).sorted(Comparator.comparingInt((Artifact a) -> Integer.parseInt(a.extensions().getOrDefault("position","9999"))).thenComparing(Artifact::id)).toList();
        for(Artifact a:features) {
            if (a.type().equals("tagged-value")) continue;
            if (a.type().equals("external-connector"))
                throw ExchangeXml.invalid("SPARX_FEATURE_EXPORT_UNSUPPORTED","External connectors are preserved-only evidence; exclude from XMI delivery");
            Element node=elements.getOwnerDocument().createElement(switch(a.type()) {case "attribute" -> "ownedAttribute"; case "operation" -> "ownedOperation"; case "parameter" -> "ownedParameter"; default -> throw ExchangeXml.invalid("SPARX_FEATURE_EXPORT_UNSUPPORTED","Unsupported feature output");});
            node.setAttributeNS(XMI,"xmi:id",xmiId(a.id(),false)); node.setAttribute("name",a.title());
            if(a.extensions().containsKey("position")) node.setAttribute("position",a.extensions().get("position"));
            if(a.extensions().containsKey("classifier")) node.setAttribute("classifier",a.extensions().get("classifier"));
            a.attributes().forEach((key,value)->{if(key.startsWith("ea:")) node.setAttribute(key.substring(3),value);});
            Element detail=detail(elements,"feature",a.id(),false); details.put(a.id(),detail);
            properties(detail,a.attributes()).setAttribute("documentation",a.text()); evidence(detail,a.attributes(),a.extensions()); nodes.put(a.id(),node);
        }
        for(Artifact a:features) {
            Element owner=details.get(a.extensions().get("owner"));
            if(a.type().equals("tagged-value")) {
                Element tags=ExchangeXml.child(owner,"tags"); if(tags==null) tags=ExchangeXml.append(owner,null,"tags");
                Element tag=ExchangeXml.append(tags,null,"tag"); tag.setAttributeNS(XMI,"xmi:id",xmiId(a.id(),false)); tag.setAttribute("name",a.title()); tag.setAttribute("value",a.text()); evidence(tag,a.attributes(),a.extensions());
            } else nodes.get(a.extensions().get("owner")).appendChild(nodes.get(a.id()));
        }
    }

    private static Element detail(Element parent, String name, String id, boolean pkg) {
        Element result = ExchangeXml.append(parent, null, name); result.setAttributeNS(XMI, "xmi:idref", xmiId(id, pkg)); return result;
    }
    private static Element properties(Element parent, Map<String, String> attributes) {
        Element result = ExchangeXml.append(parent, null, "properties");
        new TreeMap<>(attributes).forEach((key, value) -> { if (key.startsWith("ea:")) result.setAttribute(key.substring(3), value); });
        return result;
    }
    private void tags(Element parent, Map<String, String> attributes, Map<String, String> extensions) {
        Element tags = ExchangeXml.append(parent, null, "tags");
        Map<String, String> values = new TreeMap<>(attributes);
        extensions.forEach((key, value) -> { if (key.startsWith("taxonomy:")) values.put("tag:taxonomy." + key.substring(9), value); });
        values.forEach((key, value) -> {
            if (key.startsWith("tag:")) { Element tag = ExchangeXml.append(tags, null, "tag"); if (version.equals("2")) tag.setAttribute("projection", "true"); tag.setAttribute("name", key.substring(4)); tag.setAttribute("value", value); }
        });
    }
}
