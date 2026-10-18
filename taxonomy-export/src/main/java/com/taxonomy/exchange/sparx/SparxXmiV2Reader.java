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
    private final String profileVersion;
    private final Map<String,Element> details = new TreeMap<>();
    private final List<Resource> roots = new ArrayList<>();
    private final List<Feature> features = new ArrayList<>();
    private final List<Connector> connectors = new ArrayList<>();
    private final List<MappingLoss> losses = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    SparxXmiV2Reader() { this("2"); }
    SparxXmiV2Reader(String profileVersion) { this.profileVersion = profileVersion; }
    ExchangeDocument read(byte[] bytes, String version, boolean complete) {
        var document = ExchangeXml.parse(bytes); Element root = document.getDocumentElement();
        if (!XMI.equals(root.getNamespaceURI()) || !"XMI".equals(root.getLocalName()) || !"2.1".equals(root.getAttributeNS(XMI,"version")))
            throw ExchangeXml.invalid("SPARX_XMI_VERSION", "Select the EA XMI 2.1 profile");
        var models = ExchangeXml.children(root).stream().filter(e -> UML.equals(e.getNamespaceURI()) && "Model".equals(e.getLocalName())).toList();
        if (models.size() != 1) throw ExchangeXml.invalid("SPARX_MODEL_SCOPE", "Exactly one model is required");
        Map<String, String> metadata = new TreeMap<>();
        boolean metadataPresent = false;
        if (v3()) for (Element declaration : ExchangeXml.children(root)) {
            if ("Documentation".equals(declaration.getLocalName()) && "Taxonomy".equals(declaration.getAttribute("exporter")))
                SparxRelationProfileV3.require(PROFILE + "@3", declaration.getAttribute("exporterVersion"), "The XMI declaration differs from selected profile 3");
        }
        for (Element extension : ExchangeXml.children(root)) if ("Extension".equals(extension.getLocalName())) {
            if (!"Enterprise Architect".equals(extension.getAttribute("extender"))) {
                losses.add(loss(null, "extension", "SPARX_EXTENSION_EXCLUDED", LossDisposition.UNSUPPORTED)); continue;
            }
            for (Element section : ExchangeXml.children(extension)) {
                if (v3() && "modelMetadata".equals(section.getLocalName())) {
                    if (metadataPresent) throw ExchangeXml.invalid("SPARX_MODEL_EVIDENCE", "Duplicate model metadata envelope");
                    metadata.putAll(SparxMetadataV3.read(section)); metadataPresent = true; continue;
                }
                if (!Set.of("elements", "connectors", "features").contains(section.getLocalName())) {
                    losses.add(loss(null, section.getLocalName(), "SPARX_EXTENSION_EXCLUDED", LossDisposition.UNSUPPORTED)); continue;
                }
                for (Element detail : ExchangeXml.children(section)) if (details.putIfAbsent(guid(detail.getAttributeNS(XMI,"idref")), detail) != null)
                    throw ExchangeXml.invalid("DUPLICATE_IDENTITY", "Duplicate detail identity");
            }
        }
        Element model = models.getFirst(); String modelId = guid(model.getAttributeNS(XMI,"id"));
        if (v3()) {
            if (metadata.containsKey("identifier")) SparxRelationProfileV3.require(modelId, metadata.get("identifier"), "Model identity contradicts preserved metadata");
            if (metadata.containsKey("title")) SparxRelationProfileV3.require(model.getAttribute("name"), metadata.get("title"), "Model title contradicts preserved metadata");
            for (Element comment : ExchangeXml.children(model)) if ("ownedComment".equals(comment.getLocalName())) {
                if (metadata.containsKey("taxonomy.requirementText")) SparxRelationProfileV3.require(metadata.get("taxonomy.requirementText"),
                        ExchangeXml.text(comment, "body"), "Visible requirement notes contradict their source evidence");
                else losses.add(loss(modelId, "ownedComment", "SPARX_CONSTRUCT_EXCLUDED", LossDisposition.UNSUPPORTED));
            }
        }
        metadata.put("identifier", modelId); metadata.put("title", model.getAttribute("name"));
        walk(model, null);
        for (String id : details.keySet()) if (!visited.contains(id)) losses.add(loss(id, "extension", "SPARX_ORPHAN_EXTENSION", LossDisposition.UNSUPPORTED));
        return assemble(PROFILE, profileVersion, version == null ? ReqifExchangeCodec.digest(bytes) : version, complete,
                new String(ExchangeXml.write(document), StandardCharsets.UTF_8), metadata, roots,features,connectors,losses);
    }
    private void walk(Element parent, String owner) {
        int position = 0;
        for (Element node : ExchangeXml.children(parent)) {
            if (v3() && "ownedComment".equals(node.getLocalName())) continue;
            if (!"packagedElement".equals(node.getLocalName())) { losses.add(loss(owner,node.getLocalName(),"SPARX_CONSTRUCT_EXCLUDED",LossDisposition.UNSUPPORTED)); continue; }
            String id = identity(node);
            String type = type(node);
            Element detail = details.get(id);
            Element properties = ExchangeXml.child(detail, "properties");
            Map<String, String> attributes = new TreeMap<>();
            Map<String, String> extensions = new TreeMap<>();
            readEvidence(detail, attributes, extensions);
            if (v3() && !isRelation(type) && (attributes.containsKey(SparxRelationProfileV3.TYPE_TAG)
                    || ExchangeXml.children(ExchangeXml.child(detail, "tags")).stream()
                            .anyMatch(t -> "taxonomy.relationType".equals(t.getAttribute("name")))))
                throw ExchangeXml.invalid("SPARX_RELATION_UNMAPPED", "A declared Taxonomy relation has an unsupported UML connector transport");
            rootProperties(properties, attributes);
            String stereotype = properties == null ? "" : properties.getAttribute("stereotype");
            if (!stereotype.isBlank()) {
                if (v3()) sameField(extensions, "stereotype", stereotype);
                else extensions.put("stereotype",stereotype);
            }
            unknownAttributes(node, v3() ? Set.of("name", "client", "supplier", "memberEnd", "informationSource", "informationTarget", "navigableOwnedEnd")
                    : Set.of("name", "client", "supplier", "memberEnd"), attributes);
            String text = text(node,properties);
            if (isRelation(type)) {
                boolean flow = v3() && type.equals("InformationFlow");
                String source = reference(detail, "source", node.getAttribute(flow ? "informationSource" : "client"));
                String target = reference(detail, "target", node.getAttribute(flow ? "informationTarget" : "supplier"));
                boolean usesOwnedEnds = source.isEmpty() && type.equals("Association");
                if (usesOwnedEnds) {
                    var ends = ExchangeXml.children(node).stream().filter(e -> "ownedEnd".equals(e.getLocalName())).toList();
                    if (ends.size() != 2) {
                        throw ExchangeXml.invalid("SPARX_ENDPOINT_REQUIRED", "Association needs two endpoints");
                    }
                    source = ends.get(0).getAttribute("type");
                    target = ends.get(1).getAttribute("type");
                }
                String eaType = properties != null && properties.hasAttribute("ea_type") ? properties.getAttribute("ea_type") : type;
                String direction = type.equals("Association") ? "Unspecified" : "Source -> Destination";
                if (properties != null && properties.hasAttribute("direction")) {
                    direction = properties.getAttribute("direction");
                }
                if (v3()) validateEndpoints(node, type, eaType, direction, source, target);
                if (!node.getAttribute("name").isEmpty()) {
                    if (v3()) sameField(attributes, "name", node.getAttribute("name"));
                    else attributes.put("name", node.getAttribute("name"));
                }
                if (v3() && (properties != null && properties.hasAttribute("documentation") || ExchangeXml.child(node, "ownedComment") != null))
                    sameField(attributes, "description", text);
                else if (!text.isEmpty()) attributes.put("description", text);
                if (v3()) projectionTags(detail, attributes, extensions);
                else legacyTags(detail, attributes);
                if (v3()) SparxRelationProfileV3.require(SparxRelationProfileV3.stereotype(attributes.get(SparxRelationProfileV3.TYPE_TAG)),
                        stereotype, "The visible EA stereotype must declare the same exact Taxonomy relation type");
                unsupportedConnectorEnds(node, detail, id, v3() || usesOwnedEnds);
                preserved(id, attributes, extensions);
                connectors.add(new Connector(id, guid(source), guid(target), eaType, direction, attributes, extensions));
                for (Element child : ExchangeXml.children(node)) {
                    if (!Set.of("ownedComment", "ownedEnd").contains(child.getLocalName())) {
                        excluded(id, child.getLocalName());
                    }
                }
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
    private void feature(Element node, String owner, String type) {
        String id = identity(node);
        Element detail = details.get(id);
        Element properties = ExchangeXml.child(detail, "properties");
        Map<String, String> attributes = new TreeMap<>();
        Map<String, String> extensions = new TreeMap<>();
        readEvidence(detail, attributes, extensions);
        featureProperties(properties, id, attributes);
        for (int i = 0; i < node.getAttributes().getLength(); i++) {
            var field = node.getAttributes().item(i);
            if (XMI.equals(field.getNamespaceURI())
                    || javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(field.getNamespaceURI())
                    || Set.of("name", "position", "classifier").contains(field.getNodeName())) {
                continue;
            }
            String scalar = featureScalar(field.getNodeName());
            String key = scalar == null ? "xml:" + field.getNodeName() : "ea:" + scalar;
            attributes.put(key, field.getNodeValue());
        }
        if (node.hasAttribute("classifier")) {
            extensions.put("classifier", guid(node.getAttribute("classifier")));
        }
        Integer position = null;
        if (node.hasAttribute("position")) {
            String value = node.getAttribute("position");
            if (!value.matches("0|[1-9][0-9]{0,3}")) {
                throw ExchangeXml.invalid("SPARX_FEATURE_POSITION", "Invalid feature position");
            }
            position = Integer.valueOf(value);
        }
        features.add(new Feature(id, owner, type, node.getAttribute("name"), text(node, properties),
                position, attributes, extensions));
        tags(detail, id, attributes, extensions);
        preserved(id, attributes, extensions);
        for (Element child : ExchangeXml.children(node)) {
            if (type.equals("operation") && child.getLocalName().equals("ownedParameter")) {
                feature(child, id, "parameter");
            } else if (!child.getLocalName().equals("ownedComment")) {
                excluded(id, child.getLocalName());
            }
        }
        unsupportedDetailChildren(detail, id, Set.of("properties", "tags", "evidence"));
    }

    private void featureProperties(Element properties, String id, Map<String, String> attributes) {
        if (properties == null) {
            return;
        }
        for (int i = 0; i < properties.getAttributes().getLength(); i++) {
            var field = properties.getAttributes().item(i);
            // Documentation is consumed as feature text; namespace declarations are XML syntax.
            if ("documentation".equals(field.getNodeName())
                    || javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(field.getNamespaceURI())) {
                continue;
            }
            String scalar = featureScalar(field.getNodeName());
            // Only documented feature scalars are authored. Other literals remain reviewable evidence.
            String key = scalar == null ? "xml:properties." + field.getNodeName() : "ea:" + scalar;
            sameField(attributes, key, field.getNodeValue());
        }
        for (Element child : ExchangeXml.children(properties)) {
            excluded(id, "properties." + child.getLocalName());
        }
    }

    private static String featureScalar(String name) {
        return FEATURE_SCALARS.stream().filter(scalar -> scalar.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private void unsupportedConnectorEnds(Element node, Element detail, String id, boolean usesOwnedEnds) {
        for (String side : List.of("source", "target")) {
            unsupportedEndFields(ExchangeXml.child(detail, side), id, side, Set.of("xmi:idref"));
        }
        int position = 0;
        for (Element child : ExchangeXml.children(node)) {
            if ("ownedEnd".equals(child.getLocalName())) {
                // Only the fallback association path consumes UML endpoint types. End IDs are structural.
                Set<String> consumed = v3() ? Set.of("xmi:id", "type", "aggregation")
                        : usesOwnedEnds ? Set.of("xmi:id", "type") : Set.of("xmi:id");
                unsupportedEndFields(child, id, "ownedEnd[" + position++ + "]", consumed);
            }
        }
    }

    private void unsupportedEndFields(Element end, String id, String path, Set<String> consumed) {
        if (end == null) {
            return;
        }
        for (int i = 0; i < end.getAttributes().getLength(); i++) {
            var field = end.getAttributes().item(i);
            String name = XMI.equals(field.getNamespaceURI()) ? "xmi:" + field.getLocalName() : field.getNodeName();
            if (!consumed.contains(name)
                    && !javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(field.getNamespaceURI())) {
                losses.add(loss(id, path + "." + name, "SPARX_PROPERTY_EXCLUDED", LossDisposition.UNSUPPORTED));
            }
        }
        // A child loss covers its complete unsupported subtree, including role and multiplicity attributes.
        for (Element child : ExchangeXml.children(end)) {
            excluded(id, path + "." + child.getLocalName());
        }
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
                if (v3() && ownerAttributes.containsKey("tag:" + name)) sameField(ownerAttributes, "tag:" + name, value);
                else if (name.startsWith("taxonomy.") && !Set.of("taxonomy.id", "taxonomy.mappingProfile", "taxonomy.elementType", "taxonomy.relationType", "taxonomy.lastSyncRevision").contains(name))
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
    private void projectionTags(Element detail, Map<String, String> attributes, Map<String, String> extensions) {
        Set<String> names = new HashSet<>();
        for (Element tag : ExchangeXml.children(ExchangeXml.child(detail, "tags"))) {
            String name = tag.getAttribute("name"), value = tag.getAttribute("value");
            if (name.isBlank() || name.length() > 240 || value.length() > 100000 || !names.add(name))
                throw ExchangeXml.invalid("SPARX_TAG_INVALID", "Invalid or duplicate connector tag projection");
            String extension = name.startsWith("taxonomy.") ? "taxonomy:" + name.substring(9) : null;
            if (!attributes.containsKey("tag:" + name) && extension != null && extensions.containsKey(extension)) sameField(extensions, extension, value);
            else sameField(attributes, "tag:" + name, value);
        }
    }

    private void validateEndpoints(Element node, String umlType, String eaType, String direction, String source, String target) {
        String expectedUml = Set.of("Association", "Composition").contains(eaType) ? "Association" : eaType;
        SparxRelationProfileV3.require(expectedUml, umlType, "UML and EA connector transport types disagree");
        boolean reverse = direction.equals("Destination -> Source");
        String semanticSource = reverse ? target : source, semanticTarget = reverse ? source : target;
        if (umlType.equals("Association")) {
            if (node.hasAttribute("client") || node.hasAttribute("supplier") || node.hasAttribute("informationSource") || node.hasAttribute("informationTarget"))
                throw ExchangeXml.invalid("SPARX_SEMANTIC_CONFLICT", "Association transport cannot also declare dependency or flow endpoints");
            var ends = ExchangeXml.children(node).stream().filter(e -> "ownedEnd".equals(e.getLocalName())).toList();
            if (ends.size() != 2) throw ExchangeXml.invalid("SPARX_ENDPOINT_REQUIRED", "Profile 3 associations need exactly two ordered owned ends");
            SparxRelationProfileV3.require(guid(source), guid(ends.get(0).getAttribute("type")), "UML and EA source endpoints disagree");
            SparxRelationProfileV3.require(guid(target), guid(ends.get(1).getAttribute("type")), "UML and EA target endpoints disagree");
            String membership = ends.get(0).getAttributeNS(XMI, "id") + " " + ends.get(1).getAttributeNS(XMI, "id");
            SparxRelationProfileV3.require(membership, node.getAttribute("memberEnd"), "Association member ends contradict their ordering");
            String navigable = direction.equals("Unspecified") ? "" : ends.get(reverse ? 0 : 1).getAttributeNS(XMI, "id");
            SparxRelationProfileV3.require(navigable, node.getAttribute("navigableOwnedEnd"), "Association navigation contradicts its declared direction");
            for (int i = 0; i < ends.size(); i++) {
                String expected = eaType.equals("Composition") && i == (reverse ? 0 : 1) ? "composite" : "";
                SparxRelationProfileV3.require(expected, ends.get(i).getAttribute("aggregation"), "Aggregation contradicts the declared transport");
            }
        } else {
            boolean flow = umlType.equals("InformationFlow");
            if (node.hasAttribute(flow ? "client" : "informationSource") || node.hasAttribute(flow ? "supplier" : "informationTarget"))
                throw ExchangeXml.invalid("SPARX_SEMANTIC_CONFLICT", "A connector cannot declare endpoints belonging to a different UML relation kind");
            SparxRelationProfileV3.require(guid(semanticSource), guid(node.getAttribute(flow ? "informationSource" : "client")), "UML and EA source endpoints disagree");
            SparxRelationProfileV3.require(guid(semanticTarget), guid(node.getAttribute(flow ? "informationTarget" : "supplier")), "UML and EA target endpoints disagree");
        }
    }

    private boolean v3() { return "3".equals(profileVersion); }
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
    private String text(Element node,Element properties) {
        var comments = ExchangeXml.children(node).stream().filter(e -> "ownedComment".equals(e.getLocalName())).toList();
        if (v3() && comments.size() > 1) throw ExchangeXml.invalid("SPARX_SEMANTIC_CONFLICT", "Profile 3 supports one unambiguous documentation comment per object");
        Element comment=ExchangeXml.child(node,"ownedComment");
        String body = comment==null ? "" : comment.hasAttribute("body") ? comment.getAttribute("body") : ExchangeXml.text(comment,"body");
        if(properties!=null && properties.hasAttribute("documentation")) {
            String documentation = properties.getAttribute("documentation");
            if (v3() && comment != null) SparxRelationProfileV3.require(documentation, body, "UML comments contradict EA documentation");
            return documentation;
        }
        return body;
    }
    private static String reference(Element detail, String side, String fallback) {
        Element end = ExchangeXml.child(detail, side);
        return end == null ? fallback : end.getAttributeNS(XMI, "idref");
    }

    private static void rootProperties(Element properties, Map<String, String> attributes) {
        if (properties == null) {
            return;
        }
        for (int i = 0; i < properties.getAttributes().getLength(); i++) {
            var field = properties.getAttributes().item(i);
            if (!Set.of("documentation", "stereotype", "sType", "ea_type", "direction").contains(field.getNodeName())) {
                attributes.put("ea:" + field.getNodeName(), field.getNodeValue());
            }
        }
    }

    private void excluded(String id,String field) { losses.add(loss(id,field,"SPARX_FEATURE_EXCLUDED",LossDisposition.UNSUPPORTED)); }
}
