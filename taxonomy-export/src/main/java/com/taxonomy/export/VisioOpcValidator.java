package com.taxonomy.export;

import com.microsoft.schemas.office.visio.x2012.main.PageContentsDocument;
import com.microsoft.schemas.office.visio.x2012.main.PagesDocument;
import com.microsoft.schemas.office.visio.x2012.main.VisioDocumentDocument1;
import org.apache.xmlbeans.XmlError;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the generated OPC graph and the pinned XMLBeans Visio schema before ZIP creation. */
final class VisioOpcValidator {
    static final String VISIO = "http://schemas.microsoft.com/office/visio/2012/main";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final Map<String, String> RELATION_CONTENT_TYPES = Map.of(
            "http://schemas.microsoft.com/visio/2010/relationships/document", "application/vnd.ms-visio.drawing.main+xml",
            "http://schemas.microsoft.com/visio/2010/relationships/pages", "application/vnd.ms-visio.pages+xml",
            "http://schemas.microsoft.com/visio/2010/relationships/page", "application/vnd.ms-visio.page+xml",
            "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties", "application/vnd.openxmlformats-package.core-properties+xml",
            OFFICE_REL + "/extended-properties", "application/vnd.openxmlformats-officedocument.extended-properties+xml",
            OFFICE_REL + "/custom-properties", "application/vnd.openxmlformats-officedocument.custom-properties+xml",
            "https://github.com/carstenartur/Taxonomy/relationships/export-manifest", "application/json",
            "https://github.com/carstenartur/Taxonomy/relationships/mapping-profile", "application/json");

    private VisioOpcValidator() { }

    static void validate(Map<String, String> parts) {
        try {
            check(parts.size() <= 64, "Too many OPC parts");
            Map<String, Document> xml = new HashMap<>();
            long bytes = 0;
            for (var entry : parts.entrySet()) {
                safePart(entry.getKey());
                byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
                bytes += value.length;
                check(bytes <= VisioHandoffProfile.MAX_XML_BYTES, "Visio package exceeds 32 MiB uncompressed");
                if (entry.getKey().endsWith(".xml") || entry.getKey().endsWith(".rels")) xml.put(entry.getKey(), parse(value));
            }
            for (String required : List.of("[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "docProps/app.xml",
                    "docProps/custom.xml", "visio/document.xml", "visio/_rels/document.xml.rels", "visio/pages/pages.xml",
                    "visio/pages/_rels/pages.xml.rels", "taxonomy/manifest.json", "taxonomy/mapping-profile.json")) {
                check(parts.containsKey(required), "Missing required OPC part " + required);
            }
            Map<String, String> types = contentTypes(xml.get("[Content_Types].xml"), parts);
            Map<String, Map<String, String>> references = new HashMap<>();
            Map<String, Set<String>> graph = new HashMap<>();
            for (var entry : xml.entrySet()) {
                if (!entry.getKey().endsWith(".rels")) continue;
                String source = sourcePart(entry.getKey());
                check(source.isEmpty() || parts.containsKey(source), "Relationship source missing: " + source);
                root(entry.getValue(), REL, "Relationships");
                Map<String, String> ids = new HashMap<>();
                for (Element relation : children(entry.getValue().getDocumentElement())) {
                    check(REL.equals(relation.getNamespaceURI()) && relation.getLocalName().equals("Relationship"), "Invalid relationship element");
                    String id = relation.getAttribute("Id");
                    check(id.matches("[A-Za-z_][A-Za-z0-9_.-]*"), "Unsafe relationship ID");
                    check(!relation.hasAttribute("TargetMode") || relation.getAttribute("TargetMode").equals("Internal"), "External relationship forbidden");
                    String target = resolve(source, relation.getAttribute("Target"));
                    check(parts.containsKey(target), "Unresolved relationship target " + target);
                    check(ids.putIfAbsent(id, target) == null, "Duplicate relationship ID " + id);
                    String expectedType = RELATION_CONTENT_TYPES.get(relation.getAttribute("Type"));
                    check(expectedType != null && expectedType.equals(types.get(target)), "Relationship/content type mismatch for " + target);
                    graph.computeIfAbsent(source, ignored -> new HashSet<>()).add(target);
                }
                check(references.putIfAbsent(source, ids) == null, "Duplicate relationship source");
            }
            Set<String> reachable = new HashSet<>();
            visit("", graph, reachable);
            for (String part : parts.keySet()) {
                if (!part.endsWith(".rels") && !part.equals("[Content_Types].xml")) check(reachable.contains(part), "Orphan OPC part " + part);
            }
            for (var entry : xml.entrySet()) {
                var elements = entry.getValue().getElementsByTagName("*");
                for (int i = 0; i < elements.getLength(); i++) {
                    Element element = (Element) elements.item(i);
                    if (element.hasAttributeNS(OFFICE_REL, "id")) {
                        check(references.getOrDefault(entry.getKey(), Map.of()).containsKey(element.getAttributeNS(OFFICE_REL, "id")),
                                "Unresolved XML relationship reference in " + entry.getKey());
                    }
                }
            }
            validateSchema(VisioDocumentDocument1.Factory.parse(xml.get("visio/document.xml")));
            validateSchema(PagesDocument.Factory.parse(xml.get("visio/pages/pages.xml")));
            validateSchema(org.openxmlformats.schemas.officeDocument.x2006.customProperties.PropertiesDocument.Factory.parse(xml.get("docProps/custom.xml")));
            validateSchema(org.openxmlformats.schemas.officeDocument.x2006.extendedProperties.PropertiesDocument.Factory.parse(xml.get("docProps/app.xml")));
            root(xml.get("docProps/core.xml"), "http://schemas.openxmlformats.org/package/2006/metadata/core-properties", "coreProperties");

            Set<String> styleIds = new HashSet<>();
            var styles = xml.get("visio/document.xml").getElementsByTagNameNS(VISIO, "StyleSheet");
            for (int i = 0; i < styles.getLength(); i++) check(styleIds.add(((Element) styles.item(i)).getAttribute("ID")), "Duplicate style ID");
            var settings = xml.get("visio/document.xml").getElementsByTagNameNS(VISIO, "DocumentSettings");
            check(settings.getLength() == 1, "Missing default document styles");
            for (String name : List.of("DefaultLineStyle", "DefaultFillStyle", "DefaultTextStyle")) {
                check(styleIds.contains(((Element) settings.item(0)).getAttribute(name)), "Unresolved default style reference");
            }

            Set<String> pageIds = new HashSet<>(), pageTargets = new HashSet<>();
            for (Element page : children(xml.get("visio/pages/pages.xml").getDocumentElement())) {
                check(pageIds.add(page.getAttribute("ID")), "Duplicate page ID");
                var rels = page.getElementsByTagNameNS(VISIO, "Rel");
                check(rels.getLength() == 1, "Page must reference exactly one contents part");
                String rid = ((Element) rels.item(0)).getAttributeNS(OFFICE_REL, "id");
                String target = references.get("visio/pages/pages.xml").get(rid);
                check(pageTargets.add(target) && "application/vnd.ms-visio.page+xml".equals(types.get(target)), "Invalid or duplicate page contents reference");
                Document contents = xml.get(target);
                validateSchema(PageContentsDocument.Factory.parse(contents));
                validateShapes(contents, styleIds);
            }
            check(!pageIds.isEmpty() && pageIds.size() <= 32, "Visio profile requires 1 to 32 pages");
            check(pageTargets.size() == types.values().stream().filter("application/vnd.ms-visio.page+xml"::equals).count(), "Unindexed page contents");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid generated Visio OPC/schema profile", e);
        }
    }

    private static Map<String, String> contentTypes(Document document, Map<String, String> parts) {
        root(document, CONTENT_TYPES, "Types");
        Map<String, String> defaults = new HashMap<>(), overrides = new HashMap<>(), result = new HashMap<>();
        for (Element child : children(document.getDocumentElement())) {
            check(CONTENT_TYPES.equals(child.getNamespaceURI()), "Invalid content type namespace");
            if (child.getLocalName().equals("Default")) {
                check(defaults.putIfAbsent(child.getAttribute("Extension"), child.getAttribute("ContentType")) == null, "Duplicate default content type");
            } else {
                check(child.getLocalName().equals("Override"), "Invalid content type declaration");
                String name = child.getAttribute("PartName");
                check(name.startsWith("/") && parts.containsKey(name.substring(1)), "Content type references missing part");
                check(overrides.putIfAbsent(name.substring(1), child.getAttribute("ContentType")) == null, "Duplicate content type override");
            }
        }
        for (String part : parts.keySet()) {
            if (part.equals("[Content_Types].xml")) continue;
            String type = overrides.getOrDefault(part, defaults.get(part.substring(part.lastIndexOf('.') + 1)));
            check(type != null && !type.isBlank(), "Missing content type for " + part);
            if (part.endsWith(".rels")) check(type.equals("application/vnd.openxmlformats-package.relationships+xml"), "Invalid relationship content type");
            result.put(part, type);
        }
        return result;
    }

    private static void validateShapes(Document page, Set<String> styleIds) {
        Map<String, Element> shapes = new HashMap<>();
        var entries = page.getElementsByTagNameNS(VISIO, "Shape");
        Set<String> connectors = new HashSet<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element shape = (Element) entries.item(i);
            String id = shape.getAttribute("ID");
            check(id.matches("[1-9][0-9]*") && shapes.putIfAbsent(id, shape) == null, "Invalid or duplicate shape ID");
            check(!shape.hasAttribute("Master") && !shape.hasAttribute("MasterShape"), "Master references are outside the masterless profile");
            for (String style : List.of("LineStyle", "FillStyle", "TextStyle")) check(styleIds.contains(shape.getAttribute(style)), "Unresolved shape style reference");
            for (Element cell : children(shape)) if (cell.getLocalName().equals("Cell") && cell.getAttribute("N").equals("OneD") && cell.getAttribute("V").equals("1")) connectors.add(id);
        }
        Map<String, Set<String>> endpoints = new HashMap<>();
        var glue = page.getElementsByTagNameNS(VISIO, "Connect");
        for (int i = 0; i < glue.getLength(); i++) {
            Element connection = (Element) glue.item(i);
            String from = connection.getAttribute("FromSheet"), to = connection.getAttribute("ToSheet"), cell = connection.getAttribute("FromCell");
            check(connectors.contains(from) && shapes.containsKey(to) && !connectors.contains(to), "Unresolved connector endpoint");
            check((cell.equals("BeginX") || cell.equals("EndX")) && connection.getAttribute("ToCell").equals("PinX"), "Invalid dynamic glue endpoint");
            check(endpoints.computeIfAbsent(from, ignored -> new HashSet<>()).add(cell), "Duplicate connector glue endpoint");
        }
        for (String id : connectors) check(endpoints.getOrDefault(id, Set.of()).equals(Set.of("BeginX", "EndX")), "Incomplete connector glue");
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static void validateSchema(XmlObject object) {
        List<XmlError> errors = new ArrayList<>();
        check(object.validate(new XmlOptions().setErrorListener(errors)), "Visio schema errors: " + errors);
    }

    private static String sourcePart(String rels) {
        if (rels.equals("_rels/.rels")) return "";
        int marker = rels.lastIndexOf("/_rels/");
        check(marker >= 0, "Invalid relationship part path");
        return rels.substring(0, marker + 1) + rels.substring(marker + 7, rels.length() - 5);
    }

    private static String resolve(String source, String target) {
        check(!target.isBlank() && target.matches("[A-Za-z0-9_./-]+") && !target.startsWith("/"), "Unsafe relationship target");
        String resolved = URI.create("/" + source).resolve(target).normalize().getPath().substring(1);
        safePart(resolved);
        return resolved;
    }

    private static void safePart(String name) {
        check(name != null && !name.isBlank() && !name.startsWith("/") && !name.endsWith("/")
                && name.matches("[A-Za-z0-9_\\[\\]./-]+") && !name.contains("//"), "Unsafe OPC part name");
        for (String component : name.split("/")) check(!component.equals(".") && !component.equals(".."), "Unsafe OPC path segment");
    }

    private static List<Element> children(Element element) {
        List<Element> result = new ArrayList<>();
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element e) result.add(e);
        return result;
    }

    private static void visit(String part, Map<String, Set<String>> graph, Set<String> visited) {
        if (visited.add(part)) for (String target : graph.getOrDefault(part, Set.of())) visit(target, graph, visited);
    }

    private static void root(Document document, String namespace, String name) {
        check(document != null && namespace.equals(document.getDocumentElement().getNamespaceURI())
                && name.equals(document.getDocumentElement().getLocalName()), "Invalid OPC XML root " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
