package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.w3c.dom.Element;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.taxonomy.exchange.sparx.SparxMappingProfile.*;
import static com.taxonomy.exchange.sparx.SparxSemanticExchangeAssembler.*;

/** Syntax reader for the declared bounded UML feature subset; semantics belong to the shared assembler. */
final class SparxXmiV2Reader {
    private final Map<String,Element> details = new TreeMap<>();
    private final List<Resource> roots = new ArrayList<>();
    private final List<Feature> features = new ArrayList<>();
    private final List<Connector> connectors = new ArrayList<>();
    private final List<MappingLoss> losses = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    ExchangeDocument read(byte[] bytes, String version, boolean complete) {
        var document = ExchangeXml.parse(bytes); Element root = document.getDocumentElement();
        if (!XMI.equals(root.getNamespaceURI()) || !"XMI".equals(root.getLocalName()) || !"2.1".equals(root.getAttributeNS(XMI,"version")))
            throw ExchangeXml.invalid("SPARX_XMI_VERSION", "Select the EA XMI 2.1 profile");
        var models = ExchangeXml.children(root).stream().filter(e -> UML.equals(e.getNamespaceURI()) && "Model".equals(e.getLocalName())).toList();
        if (models.size() != 1) throw ExchangeXml.invalid("SPARX_MODEL_SCOPE", "Exactly one model is required");
        for (Element extension : ExchangeXml.children(root)) if ("Extension".equals(extension.getLocalName())) {
            if (!"Enterprise Architect".equals(extension.getAttribute("extender"))) {
                losses.add(loss(null, "extension", "SPARX_EXTENSION_EXCLUDED", LossDisposition.UNSUPPORTED)); continue;
            }
            for (Element section : ExchangeXml.children(extension)) {
                if (!Set.of("elements", "connectors", "features").contains(section.getLocalName())) {
                    losses.add(loss(null, section.getLocalName(), "SPARX_EXTENSION_EXCLUDED", LossDisposition.UNSUPPORTED)); continue;
                }
                for (Element detail : ExchangeXml.children(section)) if (details.putIfAbsent(guid(detail.getAttributeNS(XMI,"idref")), detail) != null)
                    throw ExchangeXml.invalid("DUPLICATE_IDENTITY", "Duplicate detail identity");
            }
        }
        Element model = models.getFirst(); String modelId = guid(model.getAttributeNS(XMI,"id"));
        walk(model, null);
        for (String id : details.keySet()) if (!visited.contains(id)) losses.add(loss(id, "extension", "SPARX_ORPHAN_EXTENSION", LossDisposition.UNSUPPORTED));
        return assemble(PROFILE, version == null ? ReqifExchangeCodec.digest(bytes) : version, complete,
                new String(ExchangeXml.write(document), StandardCharsets.UTF_8), Map.of("identifier",modelId,"title",model.getAttribute("name")), roots,features,connectors,losses);
    }
    private void walk(Element parent, String owner) {
        int position = 0;
        for (Element node : ExchangeXml.children(parent)) {
            if (!"packagedElement".equals(node.getLocalName())) { losses.add(loss(owner,node.getLocalName(),"SPARX_CONSTRUCT_EXCLUDED",LossDisposition.UNSUPPORTED)); continue; }
            String id = identity(node), type = type(node); Element detail = details.get(id), properties = ExchangeXml.child(detail,"properties");
            Map<String,String> attributes = new TreeMap<>(), extensions = new TreeMap<>(); readEvidence(detail, attributes, extensions); properties(properties,attributes);
            String stereotype = properties == null ? "" : properties.getAttribute("stereotype");
            if (!stereotype.isBlank()) extensions.put("stereotype",stereotype);
            unknownAttributes(node, Set.of("name", "client", "supplier", "memberEnd"), attributes);
            String text = text(node,properties);
            if (isRelation(type)) {
                String source = reference(detail,"source",node.getAttribute("client")), target = reference(detail,"target",node.getAttribute("supplier"));
                if (source.isEmpty() && type.equals("Association")) {
                    var ends = ExchangeXml.children(node).stream().filter(e -> "ownedEnd".equals(e.getLocalName())).toList();
                    if (ends.size() != 2) throw ExchangeXml.invalid("SPARX_ENDPOINT_REQUIRED","Association needs two endpoints");
                    source = ends.get(0).getAttribute("type"); target = ends.get(1).getAttribute("type");
                }
                String eaType = properties != null && properties.hasAttribute("ea_type") ? properties.getAttribute("ea_type") : type;
                String direction = properties != null && properties.hasAttribute("direction") ? properties.getAttribute("direction") : type.equals("Association") ? "Unspecified" : "Source -> Destination";
                if (!node.getAttribute("name").isEmpty()) attributes.put("name",node.getAttribute("name"));
                if (!text.isEmpty()) attributes.put("description",text);
                legacyTags(detail,attributes);
                preserved(id,attributes,extensions);
                connectors.add(new Connector(id,guid(source),guid(target),eaType,direction,attributes,extensions));
                for (Element child : ExchangeXml.children(node)) if (!Set.of("ownedComment","ownedEnd").contains(child.getLocalName())) excluded(id,child.getLocalName());
            } else {
                boolean pkg = type.equals("Package"), requirement = stereotype.equalsIgnoreCase("requirement") || properties != null && "Requirement".equals(properties.getAttribute("sType"));
                roots.add(new Resource(id,pkg ? ArtifactKind.SPECIFICATION : requirement ? ArtifactKind.REQUIREMENT : ArtifactKind.ELEMENT,type,node.getAttribute("name"),text,owner,position++,attributes,extensions));
                tags(detail,id,attributes,extensions);
                preserved(id,attributes,extensions);
                if (pkg) walk(node,id);
                else for (Element child : ExchangeXml.children(node)) switch(child.getLocalName()) {
                    case "ownedAttribute" -> feature(child,id,"attribute"); case "ownedOperation" -> feature(child,id,"operation");
                    case "ownedComment" -> { } default -> excluded(id,child.getLocalName());
                }
            }
            unsupportedDetailChildren(detail, id, Set.of("properties", "tags", "evidence", "source", "target"));
        }
    }
    private void feature(Element node,String owner,String type) {
        String id = identity(node); Element detail = details.get(id), properties = ExchangeXml.child(detail,"properties");
        Map<String,String> attributes = new TreeMap<>(), extensions = new TreeMap<>(); readEvidence(detail,attributes,extensions); properties(properties,attributes);
        for (int i=0;i<node.getAttributes().getLength();i++) {
            var a=node.getAttributes().item(i); if (XMI.equals(a.getNamespaceURI()) || Set.of("name","position","classifier").contains(a.getNodeName())) continue;
            String scalar = FEATURE_SCALARS.stream().filter(k -> k.equalsIgnoreCase(a.getNodeName())).findFirst().orElse(null);
            attributes.put(scalar == null ? "xml:" + a.getNodeName() : "ea:" + scalar, a.getNodeValue());
        }
        if (node.hasAttribute("classifier")) extensions.put("classifier",guid(node.getAttribute("classifier")));
        Integer position=null;
        if (node.hasAttribute("position")) {
            String value=node.getAttribute("position"); if (!value.matches("0|[1-9][0-9]{0,3}")) throw ExchangeXml.invalid("SPARX_FEATURE_POSITION","Invalid feature position"); position=Integer.valueOf(value);
        }
        features.add(new Feature(id,owner,type,node.getAttribute("name"),text(node,properties),position,attributes,extensions)); tags(detail,id,attributes,extensions);
        preserved(id,attributes,extensions);
        for (Element child : ExchangeXml.children(node)) {
            if (type.equals("operation") && child.getLocalName().equals("ownedParameter")) {
                feature(child, id, "parameter");
            } else if (!child.getLocalName().equals("ownedComment")) {
                excluded(id, child.getLocalName());
            }
        }
        unsupportedDetailChildren(detail, id, Set.of("properties", "tags", "evidence"));
    }
    private void unsupportedDetailChildren(Element detail, String id, Set<String> supportedChildren) {
        for (Element child : ExchangeXml.children(detail)) {
            if (!supportedChildren.contains(child.getLocalName())) {
                excluded(id, child.getLocalName());
            }
        }
    }
    private void tags(Element detail, String owner, Map<String,String> ownerAttributes, Map<String,String> ownerExtensions) {
        Set<String> projections = new HashSet<>();
        for (Element tag : ExchangeXml.children(ExchangeXml.child(detail,"tags"))) {
            String name = tag.getAttribute("name"), value = tag.getAttribute("value");
            if (name.isBlank() || name.length() > 240 || value.length() > 100000)
                throw ExchangeXml.invalid("SPARX_TAG_INVALID", "Tagged values need bounded names and values");
            // Writer projections have no independent feature GUID, but their reserved meanings still require validation.
            if ("true".equals(tag.getAttribute("projection"))) {
                if (!projections.add(name)) throw ExchangeXml.invalid("SPARX_TAG_INVALID", "Duplicate scalar tag projection");
                if (name.startsWith("taxonomy.") && !Set.of("taxonomy.id", "taxonomy.mappingProfile", "taxonomy.elementType", "taxonomy.relationType", "taxonomy.lastSyncRevision").contains(name))
                    sameField(ownerExtensions, "taxonomy:" + name.substring(9), value);
                else sameField(ownerAttributes, "tag:" + name, value);
                continue;
            }
            String id = identity(tag);
            Map<String,String> attributes = new TreeMap<>(), extensions = new TreeMap<>();
            readEvidence(tag,attributes,extensions);
            unknownAttributes(tag, Set.of("name", "value", "position", "notes", "projection"), attributes);
            if (tag.hasAttribute("notes")) attributes.put("attribute:http://purl.org/dc/terms/description", tag.getAttribute("notes"));
            Integer position = null;
            if (tag.hasAttribute("position")) {
                String order = tag.getAttribute("position");
                if (!order.matches("0|[1-9][0-9]{0,3}")) throw ExchangeXml.invalid("SPARX_FEATURE_POSITION", "Invalid tag position");
                position = Integer.valueOf(order);
            }
            for (Element child : ExchangeXml.children(tag)) if (!child.getLocalName().equals("evidence")) excluded(id, child.getLocalName());
            features.add(new Feature(id,owner,"tagged-value",name,value,position,attributes,extensions));
            preserved(id,attributes,extensions);
        }
    }
    private void legacyTags(Element detail, Map<String,String> attributes) {
        Set<String> names = new HashSet<>();
        for (Element tag : ExchangeXml.children(ExchangeXml.child(detail,"tags"))) {
            String key = "tag:" + tag.getAttribute("name");
            if (!names.add(key)) throw ExchangeXml.invalid("SPARX_TAG_INVALID", "Duplicate connector tag projection");
            sameField(attributes, key, tag.getAttribute("value"));
        }
    }
    private static void sameField(Map<String,String> target, String key, String value) {
        String before = target.putIfAbsent(key, value);
        if (before != null && !before.equals(value)) throw ExchangeXml.invalid("SPARX_FEATURE_EVIDENCE", "Conflicting preserved field and scalar projection");
    }
    private static void unknownAttributes(Element node, Set<String> known, Map<String,String> attributes) {
        for (int i = 0; i < node.getAttributes().getLength(); i++) {
            var field = node.getAttributes().item(i);
            if (XMI.equals(field.getNamespaceURI()) || javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(field.getNamespaceURI()) || known.contains(field.getNodeName())) continue;
            attributes.put("xml:" + field.getNodeName(), field.getNodeValue());
        }
    }
    private void preserved(String id, Map<String,String> attributes, Map<String,String> extensions) {
        attributes.keySet().stream().filter(k -> k.startsWith("xml:") || k.startsWith("attribute:"))
                .forEach(k -> losses.add(loss(id,k,"SPARX_PROPERTY_PRESERVED",LossDisposition.PRESERVED_EXTENSION)));
        extensions.keySet().stream().filter(k -> k.startsWith("rdf:"))
                .forEach(k -> losses.add(loss(id,k,"SPARX_PROPERTY_PRESERVED",LossDisposition.PRESERVED_EXTENSION)));
    }
    static void readEvidence(Element detail,Map<String,String> attributes,Map<String,String> extensions) {
        for (Element field : ExchangeXml.children(ExchangeXml.child(detail,"evidence"))) {
            Map<String,String> target = switch(field.getLocalName()) { case "attribute" -> attributes; case "extension" -> extensions; default -> throw ExchangeXml.invalid("SPARX_FEATURE_EVIDENCE","Unknown preserved field"); };
            if (target.putIfAbsent(field.getAttribute("key"),field.getAttribute("value")) != null) throw ExchangeXml.invalid("SPARX_FEATURE_EVIDENCE","Duplicate preserved field");
        }
    }
    private String identity(Element node) {
        String id=guid(node.getAttributeNS(XMI,"id")); if (!visited.add(id)) throw ExchangeXml.invalid("DUPLICATE_IDENTITY","Duplicate semantic identity"); return id;
    }
    private static String type(Element node) {
        String qualified=node.getAttributeNS(XMI,"type"); int colon=qualified.indexOf(':');
        if(colon<1 || !UML.equals(node.lookupNamespaceURI(qualified.substring(0,colon)))) throw ExchangeXml.invalid("SPARX_UML_TYPE","Unsupported UML type namespace"); return qualified.substring(colon+1);
    }
    private static String text(Element node,Element properties) {
        if(properties!=null && properties.hasAttribute("documentation")) return properties.getAttribute("documentation");
        Element comment=ExchangeXml.child(node,"ownedComment"); return comment==null ? "" : comment.hasAttribute("body") ? comment.getAttribute("body") : ExchangeXml.text(comment,"body");
    }
    private static String reference(Element detail,String side,String fallback) { Element value=ExchangeXml.child(detail,side); return value==null ? fallback : value.getAttributeNS(XMI,"idref"); }
    private static void properties(Element p,Map<String,String> attributes) {
        if(p!=null) for(int i=0;i<p.getAttributes().getLength();i++) {var a=p.getAttributes().item(i); if(!Set.of("documentation","stereotype","sType","ea_type","direction").contains(a.getNodeName())) attributes.put("ea:"+a.getNodeName(),a.getNodeValue());}
    }
    private void excluded(String id,String field) { losses.add(loss(id,field,"SPARX_FEATURE_EXCLUDED",LossDisposition.UNSUPPORTED)); }
}
