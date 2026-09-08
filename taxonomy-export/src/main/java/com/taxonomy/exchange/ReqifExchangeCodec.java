package com.taxonomy.exchange;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.taxonomy.exchange.ExchangeXml.*;

/** ReqIF 1.2's normative 2011 XML vocabulary. Original type systems and safe extensions remain exchange evidence. */
public final class ReqifExchangeCodec {
    public static final String PROFILE = "reqif-1.2";
    public static final String VERSION = "1";
    public static final String NS = "http://www.omg.org/spec/ReqIF/20110401/reqif.xsd";
    public static final String XHTML = "http://www.w3.org/1999/xhtml";
    private static final String FIXED_TIME = "2000-01-01T00:00:00Z";

    public ExchangeDocument read(byte[] content, String externalVersion, boolean completeScope) {
        Document doc = parse(content);
        if (!NS.equals(doc.getDocumentElement().getNamespaceURI()) || !"REQ-IF".equals(doc.getDocumentElement().getLocalName()))
            throw invalid("WRONG_FORMAT", "Expected a ReqIF document");
        validate(doc, PROFILE);
        List<MappingLoss> losses = new ArrayList<>();
        Map<String, Element> definitions = identities(doc);
        checkReferences(doc, definitions);
        List<Artifact> artifacts = new ArrayList<>();
        for (Element object : all(doc, NS, "SPEC-OBJECT")) {
            Fields fields = fields(object, definitions);
            String id = required(object, "IDENTIFIER");
            String titleKey = findField(fields, List.of("ReqIF.ChapterName", "ReqIF.Name", "Title", "Name"));
            String textKey = findField(fields, List.of("ReqIF.Text", "ReqIF.Description", "Text", "Description"));
            if (textKey == null) {
                List<String> rich = fields.extensions.entrySet().stream().filter(e -> e.getKey().startsWith("kind:") && e.getValue().equals("XHTML"))
                        .map(e -> e.getKey().substring(5)).sorted().toList();
                if (rich.size() == 1) textKey = rich.getFirst();
                else if (rich.size() > 1) losses.add(loss(id, "text", "AMBIGUOUS_TEXT_ATTRIBUTE", LossDisposition.UNSUPPORTED,
                        "Several XHTML attributes have no recognized text mapping; select a mapping profile before applying"));
            }
            if (textKey != null && textKey.equals(titleKey)) {
                titleKey = null;
                losses.add(loss(id, "title", "TITLE_USES_LONG_NAME", LossDisposition.TRANSFORMED,
                        "The sole rich-text attribute supplies the body; LONG-NAME independently supplies the title"));
            }
            String title = titleKey == null ? object.getAttribute("LONG-NAME") : plain(fields.values.get(titleKey), fields.extensions.get("kind:" + titleKey));
            String body = textKey == null ? "" : plain(fields.values.get(textKey), fields.extensions.get("kind:" + textKey));
            if (title.isBlank()) title = id;
            if (body.isBlank()) losses.add(loss(id, "text", "EMPTY_REQUIREMENT_TEXT", LossDisposition.UNSUPPORTED,
                    "This object has no mapped requirement text; its source and attributes remain available for review"));
            if (titleKey != null) fields.extensions.put("titleAttribute", titleKey);
            if (textKey != null) fields.extensions.put("textAttribute", textKey);
            fields.extensions.put("lastChange", object.getAttribute("LAST-CHANGE"));
            fields.extensions.put("longName", object.getAttribute("LONG-NAME"));
            fields.extensions.put("xml", xml(object));
            artifacts.add(new Artifact(id, ArtifactKind.REQUIREMENT, ref(object, "TYPE"), title, body, fields.values, fields.extensions));
        }
        for (Element specification : all(doc, NS, "SPECIFICATION")) {
            Fields fields = fields(specification, definitions);
            fields.extensions.put("xml", xml(specification));
            artifacts.add(new Artifact(required(specification, "IDENTIFIER"), ArtifactKind.SPECIFICATION, ref(specification, "TYPE"),
                    specification.getAttribute("LONG-NAME"), specification.getAttribute("DESC"), fields.values, fields.extensions));
        }
        if (artifacts.size() > MAX_ARTIFACTS) throw invalid("ITEM_LIMIT", "ReqIF exceeds the supported item count");
        List<Relation> relations = new ArrayList<>();
        for (Element relation : all(doc, NS, "SPEC-RELATION")) {
            Fields fields = fields(relation, definitions);
            fields.extensions.put("xml", xml(relation));
            fields.extensions.put("longName", relation.getAttribute("LONG-NAME"));
            relations.add(new Relation(required(relation, "IDENTIFIER"), ref(relation, "TYPE"), ref(relation, "SOURCE"), ref(relation, "TARGET"), fields.values, fields.extensions));
        }
        List<Placement> placements = new ArrayList<>();
        for (Element spec : all(doc, NS, "SPECIFICATION")) hierarchy(child(spec, "CHILDREN"), required(spec, "IDENTIFIER"), null, placements);
        for (Element extension : all(doc, NS, "REQ-IF-TOOL-EXTENSION"))
            losses.add(loss(null, "toolExtensions", "TOOL_EXTENSION_PRESERVED", LossDisposition.PRESERVED_EXTENSION,
                    "Safe tool-specific XML is retained without interpreting vendor behavior"));
        if (!all(doc, NS, "RELATION-GROUP").isEmpty())
            losses.add(loss(null, "relationGroups", "RELATION_GROUP_PRESERVED", LossDisposition.PRESERVED_EXTENSION,
                    "Relation groups are preserved in exchange evidence; no portfolio group authoring is implied"));
        for (Element object : all(doc, XHTML, "object"))
            losses.add(loss(null, "xhtml.object", "ATTACHMENT_NOT_FETCHED", LossDisposition.UNSUPPORTED,
                    "Embedded-object references are retained as inert source; binary payloads are not fetched or executed"));
        String safe = xml(doc);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("sourceTool", text(all(doc, NS, "REQ-IF-HEADER").getFirst(), "SOURCE-TOOL-ID"));
        Element core = all(doc, NS, "REQ-IF-CONTENT").getFirst();
        for (String name : List.of("DATATYPES", "SPEC-TYPES", "SPEC-RELATION-GROUPS")) {
            Element node = child(core, name); if (node != null) metadata.put(name, xml(node));
        }
        return new ExchangeDocument(PROFILE, VERSION, externalVersion == null ? digest(content) : externalVersion, completeScope, safe,
                artifacts, relations, placements, metadata, losses);
    }

    public byte[] write(ExchangeDocument source) {
        validatePlacements(source);
        if ((source.source() == null || source.source().isBlank()) && !source.metadata().containsKey("DATATYPES")) {
            ExchangeDocument initial = read(ExchangeXml.write(generated(source)), source.externalVersion(), source.completeScope());
            List<Placement> placements = source.placements();
            if (placements.isEmpty()) {
                List<Placement> flat = new ArrayList<>();
                String specification = initial.artifacts().stream().filter(a -> a.kind() == ArtifactKind.SPECIFICATION).findFirst().orElseThrow().id();
                int position = 0;
                for (Artifact artifact : initial.artifacts()) if (artifact.kind() == ArtifactKind.REQUIREMENT)
                    flat.add(new Placement("hierarchy-" + artifact.id(), specification, null, artifact.id(), position++, Map.of("LAST-CHANGE", FIXED_TIME)));
                placements = flat;
            }
            return write(new ExchangeDocument(initial.profile(), initial.profileVersion(), initial.externalVersion(), initial.completeScope(), initial.source(),
                    initial.artifacts(), initial.relations(), placements, initial.metadata(), source.losses()));
        }
        Document doc = source.source() == null || source.source().isBlank() ? generated(source) : parse(source.source().getBytes(StandardCharsets.UTF_8));
        Element core = all(doc, NS, "REQ-IF-CONTENT").getFirst();
        for (String name : List.of("DATATYPES", "SPEC-TYPES", "SPEC-RELATION-GROUPS")) if (source.metadata().containsKey(name)) {
            Element previous = child(core, name);
            Node replacement = doc.importNode(parse(source.metadata().get(name).getBytes(StandardCharsets.UTF_8)).getDocumentElement(), true);
            if (previous != null) core.replaceChild(replacement, previous); else core.appendChild(replacement);
        }
        List<Artifact> nativeRequirements = source.artifacts().stream().filter(a -> a.kind() == ArtifactKind.REQUIREMENT && !a.extensions().containsKey("xml")).toList();
        Map<String, Element> generatedObjects = Map.of();
        if (!nativeRequirements.isEmpty()) {
            Document generated = generated(new ExchangeDocument(PROFILE, VERSION, source.externalVersion(), true, "", nativeRequirements,
                    List.of(), List.of(), Map.of(), List.of()));
            Map<String, Element> present = identities(doc);
            for (String group : List.of("DATATYPES", "SPEC-TYPES")) {
                Element target = child(core, group); if (target == null) target = append(core, NS, group);
                for (Element definition : children(all(generated, NS, group).getFirst())) {
                    Element previous = present.get(required(definition, "IDENTIFIER"));
                    if (previous == null) target.appendChild(doc.importNode(definition, true));
                    else if (!ExchangeXml.semantic(xml(previous)).equals(ExchangeXml.semantic(xml(definition))))
                        throw invalid("TYPE_IDENTITY_COLLISION", "A native export type collides with an existing external identity");
                }
            }
            generatedObjects = identities(generated);
        }
        Map<String, Element> ids = identities(doc);
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        source.artifacts().forEach(a -> { if (artifacts.putIfAbsent(a.id(), a) != null) throw invalid("DUPLICATE_IDENTITY", "Duplicate exchange identity"); });
        Element objects = child(core, "SPEC-OBJECTS"); if (objects == null) objects = append(core, NS, "SPEC-OBJECTS");
        while (objects.hasChildNodes()) objects.removeChild(objects.getFirstChild());
        for (Artifact artifact : source.artifacts()) if (artifact.kind() == ArtifactKind.REQUIREMENT) {
            Element object;
            if (artifact.extensions().containsKey("xml")) {
                object = (Element) doc.importNode(parse(artifact.extensions().get("xml").getBytes(StandardCharsets.UTF_8)).getDocumentElement(), true);
                if (!NS.equals(object.getNamespaceURI()) || !"SPEC-OBJECT".equals(object.getLocalName())) throw invalid("EVIDENCE_TYPE", "Incompatible requirement evidence");
                objects.appendChild(object);
            } else {
                object = (Element) doc.importNode(generatedObjects.get(artifact.id()), true);
                objects.appendChild(object);
                continue;
            }
            updateObject(object, artifact, ids);
        }
        Element specifications = child(core, "SPECIFICATIONS");
        if (specifications == null) specifications = append(core, NS, "SPECIFICATIONS");
        while (specifications.hasChildNodes()) specifications.removeChild(specifications.getFirstChild());
        for (Artifact artifact : source.artifacts()) if (artifact.kind() == ArtifactKind.SPECIFICATION) {
            Element specification;
            if (artifact.extensions().containsKey("xml")) {
                specification = (Element) doc.importNode(parse(artifact.extensions().get("xml").getBytes(StandardCharsets.UTF_8)).getDocumentElement(), true);
                if (!NS.equals(specification.getNamespaceURI()) || !"SPECIFICATION".equals(specification.getLocalName())) throw invalid("EVIDENCE_TYPE", "Incompatible specification evidence");
                specifications.appendChild(specification);
            } else {
                specification = definition(specifications, "SPECIFICATION", artifact.id());
                setRef(specification, "TYPE", "SPECIFICATION-TYPE-REF", artifact.type());
            }
        }
        for (Element specification : all(doc, NS, "SPECIFICATION")) {
            Artifact artifact = artifacts.get(required(specification, "IDENTIFIER"));
            if (artifact != null) {
                specification.setAttribute("LONG-NAME", artifact.title());
                applyValues(specification, artifact.attributes(), artifact.extensions(), ids);
            }
            Element old = child(specification, "CHILDREN"); if (old != null) specification.removeChild(old);
            List<Placement> roots = source.placements().stream().filter(p -> p.containerId().equals(specification.getAttribute("IDENTIFIER")) && p.parentId() == null)
                    .sorted(Comparator.comparingInt(Placement::position).thenComparing(Placement::id)).toList();
            if (!roots.isEmpty()) {
                Element children = append(specification, NS, "CHILDREN");
                for (Placement root : roots) writeHierarchy(children, root, source.placements(), new HashSet<>());
            }
        }
        Map<String, Relation> relations = new LinkedHashMap<>(); source.relations().forEach(r -> relations.put(r.id(), r));
        Element relationGroup = child(core, "SPEC-RELATIONS"); if (relationGroup == null) relationGroup = append(core, NS, "SPEC-RELATIONS");
        while (relationGroup.hasChildNodes()) relationGroup.removeChild(relationGroup.getFirstChild());
        for (Relation relation : source.relations()) {
            Element node;
            if (relation.extensions().containsKey("xml")) {
                node = (Element) doc.importNode(parse(relation.extensions().get("xml").getBytes(StandardCharsets.UTF_8)).getDocumentElement(), true);
                if (!NS.equals(node.getNamespaceURI()) || !"SPEC-RELATION".equals(node.getLocalName())) throw invalid("EVIDENCE_TYPE", "Incompatible relation evidence");
                relationGroup.appendChild(node);
            } else node = definition(relationGroup, "SPEC-RELATION", relation.id());
            setRef(node, "TYPE", "SPEC-RELATION-TYPE-REF", relation.type());
            setRef(node, "SOURCE", "SPEC-OBJECT-REF", relation.source());
            setRef(node, "TARGET", "SPEC-OBJECT-REF", relation.target());
            applyValues(node, relation.attributes(), relation.extensions(), ids);
        }
        // Re-establish the normative content-group order after optional groups were inserted.
        for (String name : List.of("DATATYPES", "SPEC-TYPES", "SPEC-OBJECTS", "SPEC-RELATIONS", "SPECIFICATIONS", "SPEC-RELATION-GROUPS")) {
            Element node = child(core, name); if (node != null) core.appendChild(node);
        }
        validate(doc, PROFILE); checkReferences(doc, identities(doc));
        return ExchangeXml.write(doc);
    }

    private static void updateObject(Element object, Artifact artifact, Map<String, Element> definitions) {
        String titleAttribute = artifact.extensions().get("titleAttribute");
        String textAttribute = artifact.extensions().get("textAttribute");
        Map<String, String> values = new LinkedHashMap<>(artifact.attributes());
        if (titleAttribute == null) object.setAttribute("LONG-NAME", artifact.title());
        else if (!artifact.title().equals(plain(values.get(titleAttribute), artifact.extensions().get("kind:" + titleAttribute))))
            values.put(titleAttribute, valueFor(artifact.title(), artifact.extensions().get("kind:" + titleAttribute)));
        if (textAttribute != null && !artifact.text().equals(plain(values.get(textAttribute), artifact.extensions().get("kind:" + textAttribute))))
            values.put(textAttribute, valueFor(artifact.text(), artifact.extensions().get("kind:" + textAttribute)));
        applyValues(object, values, artifact.extensions(), definitions);
    }

    private static void applyValues(Element object, Map<String, String> values, Map<String, String> extensions, Map<String, Element> definitions) {
        Element container = child(object, "VALUES");
        if (container == null && !values.isEmpty()) container = append(object, NS, "VALUES");
        Map<String, Element> existing = new LinkedHashMap<>();
        for (Element value : children(container)) existing.put(ref(value, "DEFINITION"), value);
        existing.forEach((id, value) -> { if (!values.containsKey(id)) value.getParentNode().removeChild(value); });
        for (var entry : values.entrySet()) {
            Element definition = definitions.get(entry.getKey());
            if (definition == null) throw invalid("MISSING_DEFINITION", "Attribute definition is not in the selected type system");
            String kind = definition.getLocalName().replace("ATTRIBUTE-DEFINITION-", "");
            Element value = existing.get(entry.getKey());
            if (value == null) {
                value = append(container, NS, "ATTRIBUTE-VALUE-" + kind);
                text(append(value, NS, "DEFINITION"), NS, "ATTRIBUTE-DEFINITION-" + kind + "-REF", entry.getKey());
            }
            if (kind.equals("XHTML")) {
                Element old = child(value, "THE-VALUE"); if (old != null) value.removeChild(old);
                Document fragment = parse(entry.getValue().getBytes(StandardCharsets.UTF_8));
                append(value, NS, "THE-VALUE").appendChild(value.getOwnerDocument().importNode(fragment.getDocumentElement(), true));
            } else if (kind.equals("ENUMERATION")) {
                Element old = child(value, "VALUES"); if (old != null) value.removeChild(old);
                Element selected = append(value, NS, "VALUES");
                for (String id : entry.getValue().split("\n")) if (!id.isBlank()) text(selected, NS, "ENUM-VALUE-REF", id);
            } else value.setAttribute("THE-VALUE", entry.getValue());
        }
    }

    private record Fields(Map<String, String> values, Map<String, String> extensions) {}
    private static Fields fields(Element object, Map<String, Element> ids) {
        Map<String, String> values = new LinkedHashMap<>(); Map<String, String> extensions = new LinkedHashMap<>();
        Element type = ids.get(ref(object, "TYPE"));
        if (type == null) throw invalid("TYPE_REFERENCE", "ReqIF type reference is missing");
        for (Element definition : children(child(type, "SPEC-ATTRIBUTES"))) {
            String id = required(definition, "IDENTIFIER");
            extensions.put("definition:" + id, definition.getAttribute("LONG-NAME"));
            extensions.put("kind:" + id, definition.getLocalName().replace("ATTRIBUTE-DEFINITION-", ""));
            extensions.put("datatype:" + id, ref(definition, "TYPE"));
            List<Element> defaults = children(child(definition, "DEFAULT-VALUE"));
            if (!defaults.isEmpty()) values.put(id, value(defaults.getFirst()));
        }
        for (Element value : children(child(object, "VALUES"))) {
            String id = ref(value, "DEFINITION");
            if (!extensions.containsKey("kind:" + id)) throw invalid("ATTRIBUTE_TYPE", "Attribute does not belong to the object's selected type");
            values.put(id, value(value));
        }
        return new Fields(values, extensions);
    }

    private static String value(Element value) {
        if (value.getLocalName().equals("ATTRIBUTE-VALUE-XHTML")) {
            List<Element> content = children(child(value, "THE-VALUE"));
            return content.isEmpty() ? "<div xmlns=\"" + XHTML + "\"/>" : xml(content.getFirst());
        }
        if (value.getLocalName().equals("ATTRIBUTE-VALUE-ENUMERATION"))
            return String.join("\n", children(child(value, "VALUES")).stream().map(Node::getTextContent).sorted().toList());
        return value.getAttribute("THE-VALUE");
    }

    private static String findField(Fields fields, List<String> names) {
        for (String name : names) for (var entry : fields.extensions.entrySet())
            if (entry.getKey().startsWith("definition:") && entry.getValue().equalsIgnoreCase(name)) return entry.getKey().substring(11);
        return null;
    }
    private static String plain(String value, String kind) {
        if (value == null) return "";
        return "XHTML".equals(kind) ? parse(value.getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTextContent() : value;
    }
    private static String valueFor(String value, String kind) {
        if (!"XHTML".equals(kind)) return value;
        Document doc = parse(("<div xmlns=\"" + XHTML + "\"/>").getBytes(StandardCharsets.UTF_8));
        text(doc.getDocumentElement(), XHTML, "p", value); return xml(doc.getDocumentElement());
    }
    private static String ref(Element parent, String wrapper) {
        List<Element> references = children(child(parent, wrapper));
        return references.isEmpty() ? "" : references.getFirst().getTextContent().strip();
    }
    private static void setRef(Element parent, String wrapper, String tag, String id) {
        Element node = child(parent, wrapper);
        if (node == null) node = append(parent, NS, wrapper); else while (node.hasChildNodes()) node.removeChild(node.getFirstChild());
        text(node, NS, tag, id);
    }
    private static Map<String, Element> identities(Document doc) {
        Map<String, Element> result = new LinkedHashMap<>();
        for (Element element : all(doc, NS, "*")) if (element.hasAttribute("IDENTIFIER") && !element.getLocalName().equals("ALTERNATIVE-ID")) {
            if (result.putIfAbsent(required(element, "IDENTIFIER"), element) != null)
                throw invalid("DUPLICATE_IDENTITY", "ReqIF reuses a stable identity");
        }
        return result;
    }
    private static void checkReferences(Document doc, Map<String, Element> ids) {
        for (Element element : all(doc, NS, "*")) if (element.getLocalName().endsWith("-REF")) {
            Element target = ids.get(element.getTextContent().strip());
            String type = element.getLocalName().substring(0, element.getLocalName().length() - 4);
            if (target == null || !target.getLocalName().equals(type)) throw invalid("DANGLING_REFERENCE", "ReqIF contains a missing or incompatible typed reference");
        }
    }
    private static void hierarchy(Element children, String specification, String parent, List<Placement> result) {
        int position = 0;
        for (Element node : children(children)) {
            String id = required(node, "IDENTIFIER"); Map<String, String> attributes = new LinkedHashMap<>();
            for (String name : List.of("LAST-CHANGE", "LONG-NAME", "DESC", "IS-EDITABLE", "IS-TABLE-INTERNAL"))
                if (node.hasAttribute(name)) attributes.put(name, node.getAttribute(name));
            Element editable = child(node, "EDITABLE-ATTS"); if (editable != null) attributes.put("editableXml", xml(editable));
            result.add(new Placement(id, specification, parent, ref(node, "OBJECT"), position++, attributes));
            hierarchy(child(node, "CHILDREN"), specification, id, result);
        }
    }
    private static void writeHierarchy(Element parent, Placement placement, List<Placement> all, Set<String> visiting) {
        if (!visiting.add(placement.id()) || visiting.size() > 90) throw invalid("HIERARCHY_CYCLE", "Invalid requirement hierarchy");
        Element node = append(parent, NS, "SPEC-HIERARCHY"); node.setAttribute("IDENTIFIER", placement.id());
        node.setAttribute("LAST-CHANGE", placement.attributes().getOrDefault("LAST-CHANGE", FIXED_TIME));
        for (String name : List.of("LONG-NAME", "DESC", "IS-EDITABLE", "IS-TABLE-INTERNAL"))
            if (placement.attributes().containsKey(name)) node.setAttribute(name, placement.attributes().get(name));
        setRef(node, "OBJECT", "SPEC-OBJECT-REF", placement.artifactId());
        String editable = placement.attributes().get("editableXml");
        if (editable != null) node.appendChild(node.getOwnerDocument().importNode(parse(editable.getBytes(StandardCharsets.UTF_8)).getDocumentElement(), true));
        List<Placement> descendants = all.stream().filter(p -> placement.id().equals(p.parentId()))
                .sorted(Comparator.comparingInt(Placement::position).thenComparing(Placement::id)).toList();
        if (!descendants.isEmpty()) {
            Element group = append(node, NS, "CHILDREN");
            for (Placement child : descendants) writeHierarchy(group, child, all, visiting);
        }
        visiting.remove(placement.id());
    }

    /** Deterministic initial profile for a project which has never been exchanged with this connection. */
    private static Document generated(ExchangeDocument source) {
        Document doc = parse(("<REQ-IF xmlns=\"" + NS + "\"/>").getBytes(StandardCharsets.UTF_8));
        Element root = doc.getDocumentElement();
        Element header = append(append(root, NS, "THE-HEADER"), NS, "REQ-IF-HEADER"); header.setAttribute("IDENTIFIER", "taxonomy-header");
        text(header, NS, "CREATION-TIME", FIXED_TIME); text(header, NS, "REQ-IF-TOOL-ID", "Taxonomy ReqIF 1.2 profile 1");
        text(header, NS, "REQ-IF-VERSION", "1.0"); text(header, NS, "SOURCE-TOOL-ID", "Taxonomy"); text(header, NS, "TITLE", source.metadata().getOrDefault("title", "Requirements"));
        Element content = append(append(root, NS, "CORE-CONTENT"), NS, "REQ-IF-CONTENT");
        Element datatypes = append(content, NS, "DATATYPES");
        definition(datatypes, "DATATYPE-DEFINITION-STRING", "taxonomy-string").setAttribute("MAX-LENGTH", "100000");
        definition(datatypes, "DATATYPE-DEFINITION-XHTML", "taxonomy-xhtml");
        Element types = append(content, NS, "SPEC-TYPES"); Element objectType = definition(types, "SPEC-OBJECT-TYPE", "taxonomy-object");
        Element definitions = append(objectType, NS, "SPEC-ATTRIBUTES");
        Element title = definition(definitions, "ATTRIBUTE-DEFINITION-STRING", "taxonomy-title"); title.setAttribute("LONG-NAME", "ReqIF.ChapterName");
        setRef(title, "TYPE", "DATATYPE-DEFINITION-STRING-REF", "taxonomy-string");
        Element body = definition(definitions, "ATTRIBUTE-DEFINITION-XHTML", "taxonomy-text"); body.setAttribute("LONG-NAME", "ReqIF.Text");
        setRef(body, "TYPE", "DATATYPE-DEFINITION-XHTML-REF", "taxonomy-xhtml");
        definition(types, "SPECIFICATION-TYPE", "taxonomy-specification-type");
        definition(types, "SPEC-RELATION-TYPE", "taxonomy-relation-type");
        Element objects = append(content, NS, "SPEC-OBJECTS");
        for (Artifact artifact : source.artifacts()) if (artifact.kind() == ArtifactKind.REQUIREMENT) {
            Element object = definition(objects, "SPEC-OBJECT", artifact.id()); object.setAttribute("LONG-NAME", artifact.title());
            setRef(object, "TYPE", "SPEC-OBJECT-TYPE-REF", "taxonomy-object");
            Element values = append(object, NS, "VALUES");
            Element nameValue = append(values, NS, "ATTRIBUTE-VALUE-STRING"); nameValue.setAttribute("THE-VALUE", artifact.title());
            setRef(nameValue, "DEFINITION", "ATTRIBUTE-DEFINITION-STRING-REF", "taxonomy-title");
            Element textValue = append(values, NS, "ATTRIBUTE-VALUE-XHTML");
            setRef(textValue, "DEFINITION", "ATTRIBUTE-DEFINITION-XHTML-REF", "taxonomy-text");
            Element value = append(textValue, NS, "THE-VALUE"); text(append(value, XHTML, "div"), XHTML, "p", artifact.text());
        }
        Element relations = append(content, NS, "SPEC-RELATIONS");
        for (Relation relation : source.relations()) {
            Element node = definition(relations, "SPEC-RELATION", relation.id());
            setRef(node, "TYPE", "SPEC-RELATION-TYPE-REF", "taxonomy-relation-type");
            setRef(node, "SOURCE", "SPEC-OBJECT-REF", relation.source()); setRef(node, "TARGET", "SPEC-OBJECT-REF", relation.target());
        }
        Element specs = append(content, NS, "SPECIFICATIONS");
        List<Artifact> specifications = source.artifacts().stream().filter(a -> a.kind() == ArtifactKind.SPECIFICATION).toList();
        if (specifications.isEmpty()) specifications = List.of(new Artifact("taxonomy-specification", ArtifactKind.SPECIFICATION, "taxonomy-specification-type", "Requirements", "", Map.of(), Map.of()));
        for (Artifact specification : specifications) {
            Element node = definition(specs, "SPECIFICATION", specification.id()); node.setAttribute("LONG-NAME", specification.title());
            setRef(node, "TYPE", "SPECIFICATION-TYPE-REF", "taxonomy-specification-type");
        }
        return doc;
    }
    private static Element definition(Element parent, String type, String id) {
        Element element = append(parent, NS, type); element.setAttribute("IDENTIFIER", id); element.setAttribute("LAST-CHANGE", FIXED_TIME); return element;
    }
    private static MappingLoss loss(String id, String field, String code, LossDisposition disposition, String detail) { return new MappingLoss(id, field, code, disposition, detail); }
    public static String digest(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
