package com.taxonomy.export;

import com.microsoft.schemas.office.visio.x2012.main.PageContentsDocument;
import com.microsoft.schemas.office.visio.x2012.main.PagesDocument;
import com.microsoft.schemas.office.visio.x2012.main.VisioDocumentDocument1;
import com.microsoft.schemas.office.visio.x2012.main.MastersDocument;
import com.microsoft.schemas.office.visio.x2012.main.MasterContentsDocument;
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
    private static final Map<String, String> RELATION_CONTENT_TYPES = Map.ofEntries(
            Map.entry("http://schemas.microsoft.com/visio/2010/relationships/document", "application/vnd.ms-visio.drawing.main+xml"),
            Map.entry("http://schemas.microsoft.com/visio/2010/relationships/pages", "application/vnd.ms-visio.pages+xml"),
            Map.entry("http://schemas.microsoft.com/visio/2010/relationships/windows", "application/vnd.ms-visio.windows+xml"),
            Map.entry("http://schemas.microsoft.com/visio/2010/relationships/page", "application/vnd.ms-visio.page+xml"),
            Map.entry("http://schemas.microsoft.com/visio/2010/relationships/masters", "application/vnd.ms-visio.masters+xml"),
            Map.entry("http://schemas.microsoft.com/visio/2010/relationships/master", "application/vnd.ms-visio.master+xml"),
            Map.entry("http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties", "application/vnd.openxmlformats-package.core-properties+xml"),
            Map.entry(OFFICE_REL + "/extended-properties", "application/vnd.openxmlformats-officedocument.extended-properties+xml"),
            Map.entry(OFFICE_REL + "/custom-properties", "application/vnd.openxmlformats-officedocument.custom-properties+xml"),
            Map.entry("https://github.com/carstenartur/Taxonomy/relationships/export-manifest", "application/json"),
            Map.entry("https://github.com/carstenartur/Taxonomy/relationships/mapping-profile", "application/json"));

    private VisioOpcValidator() { }

    static void validate(Map<String, String> parts) {
        try {
            check(parts.size() <= VisioPackageValidator.MAX_OPC_PARTS, "Too many OPC parts");
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
                    "docProps/custom.xml", "visio/document.xml", "visio/windows.xml", "visio/_rels/document.xml.rels", "visio/pages/pages.xml",
                    "visio/pages/_rels/pages.xml.rels", "visio/masters/masters.xml", "visio/masters/_rels/masters.xml.rels",
                    "taxonomy/manifest.json", "taxonomy/mapping-profile.json")) {
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
            String windowsTarget = references.getOrDefault("visio/document.xml", Map.of()).values().stream()
                    .filter(target -> "visio/windows.xml".equals(target)).findFirst().orElse(null);
            check(windowsTarget != null, "Visio document must reference windows.xml");

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
            root(xml.get("visio/windows.xml"), VISIO, "Windows");
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

            Map<String, String> masters = validateMasters(xml, references, types, styleIds);

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
                validateShapes(contents, styleIds, masters, new HashSet<>(references.getOrDefault(target, Map.of()).values()));
            }
            check(!pageIds.isEmpty() && pageIds.size() <= VisioPackageValidator.MAX_PAGES,
                    "Visio profile requires 1 to " + VisioPackageValidator.MAX_PAGES + " pages");
            check(pageTargets.size() == types.values().stream().filter("application/vnd.ms-visio.page+xml"::equals).count(), "Unindexed page contents");
        } catch (Exception e) {
            // This validates server-generated parts, not an uploaded user document.
            // Preserve the reason for server diagnostics and route it to the existing
            // export-failure (HTTP 500) boundary rather than blaming client input.
            throw new java.io.UncheckedIOException("VISIO_PACKAGE_VALIDATION_FAILED",
                    new java.io.IOException("Generated Visio OPC/schema profile is invalid", e));
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

    private static Map<String, String> validateMasters(Map<String, Document> xml,
            Map<String, Map<String, String>> references, Map<String, String> types, Set<String> styleIds) throws Exception {
        validateSchema(MastersDocument.Factory.parse(xml.get("visio/masters/masters.xml")));
        Map<String, String> masters = new HashMap<>();
        Set<String> indexed = new HashSet<>();
        for (Element master : children(xml.get("visio/masters/masters.xml").getDocumentElement())) {
            String id = master.getAttribute("ID");
            var rels = master.getElementsByTagNameNS(VISIO, "Rel");
            check(id.matches("[1-9][0-9]*") && rels.getLength() == 1, "Invalid native master index");
            String target = references.get("visio/masters/masters.xml").get(((Element) rels.item(0)).getAttributeNS(OFFICE_REL, "id"));
            check("application/vnd.ms-visio.master+xml".equals(types.get(target)) && indexed.add(target)
                    && masters.putIfAbsent(id, target) == null, "Unresolved or duplicate native master");
            Document contents = xml.get(target);
            validateSchema(MasterContentsDocument.Factory.parse(contents));
            var shapes = contents.getElementsByTagNameNS(VISIO, "Shape");
            check(shapes.getLength() == 1, "Native master requires one top-level shape");
            Element shape = (Element) shapes.item(0);
            check(shape.getAttribute("ID").equals("1") && !shape.hasAttribute("Master") && !shape.hasAttribute("MasterShape"),
                    "Invalid native master definition");
            validateStyles(shape, styleIds);
        }
        check(indexed.size() == types.values().stream().filter("application/vnd.ms-visio.master+xml"::equals).count(), "Unindexed master contents");
        return masters;
    }

    private static void validateStyles(Element shape, Set<String> styleIds) {
        for (String style : List.of("LineStyle", "FillStyle", "TextStyle"))
            check(styleIds.contains(shape.getAttribute(style)), "Unresolved shape style reference");
    }

    private static void validateShapes(Document page, Set<String> styleIds, Map<String, String> masters, Set<String> pageMasters) {
        Map<String, Element> shapes = new HashMap<>();
        var entries = page.getElementsByTagNameNS(VISIO, "Shape");
        Set<String> connectors = new HashSet<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element shape = (Element) entries.item(i);
            String id = shape.getAttribute("ID");
            check(id.matches("[1-9][0-9]*") && shapes.putIfAbsent(id, shape) == null, "Invalid or duplicate shape ID");
            check(!shape.hasAttribute("MasterShape"), "Nested master-shape references are outside the native profile");
            if (shape.hasAttribute("Master")) {
                String target = masters.get(shape.getAttribute("Master"));
                check(target != null && pageMasters.contains(target), "Unresolved native master reference");
            }
            validateStyles(shape, styleIds);
            validateHyperlinks(shape);
            Element oneD = directCell(shape, "OneD");
            if (oneD != null && oneD.getAttribute("V").equals("1")) connectors.add(id);
            else if (shape.hasAttribute("Master")) validateNativePorts(shape);
        }
        Map<String, Set<String>> endpoints = new HashMap<>();
        var glue = page.getElementsByTagNameNS(VISIO, "Connect");
        for (int i = 0; i < glue.getLength(); i++) {
            Element connection = (Element) glue.item(i);
            String from = connection.getAttribute("FromSheet"), to = connection.getAttribute("ToSheet"), cell = connection.getAttribute("FromCell");
            check(connectors.contains(from) && shapes.containsKey(to) && !connectors.contains(to), "Unresolved connector endpoint");
            String pointCell = connection.getAttribute("ToCell");
            check((cell.equals("BeginX") || cell.equals("EndX")) && pointCell.matches("Connections\\.X[1-4]"), "Invalid point-glue endpoint");
            check(endpoints.computeIfAbsent(from, ignored -> new HashSet<>()).add(cell), "Duplicate connector glue endpoint");
            int point = Integer.parseInt(pointCell.substring("Connections.X".length()));
            check(connection.getAttribute("FromPart").equals(cell.equals("BeginX") ? "9" : "12")
                    && connection.getAttribute("ToPart").equals(Integer.toString(99 + point)), "Mismatched point-glue part identifiers");
            Element port = connectionPoint(shapes.get(to), point - 1);
            check(port != null, "Unresolved native connection point");
            String formula = "PAR(PNT(Sheet." + to + "!Connections.X" + point + ",Sheet." + to + "!Connections.Y" + point + "))";
            Element x = directCell(shapes.get(from), cell), y = directCell(shapes.get(from), cell.replace('X', 'Y'));
            check(x != null && y != null && formula.equals(x.getAttribute("F")) && formula.equals(y.getAttribute("F")),
                    "Missing or mismatched connector endpoint dependency");
            Element target = shapes.get(to);
            double expectedX = number(target, "PinX") - number(target, "LocPinX") + number(port, "X");
            double expectedY = number(target, "PinY") - number(target, "LocPinY") + number(port, "Y");
            check(close(Double.parseDouble(x.getAttribute("V")), expectedX) && close(Double.parseDouble(y.getAttribute("V")), expectedY),
                    "Cached connector endpoint does not match its connection point");
        }
        for (String id : connectors) check(endpoints.getOrDefault(id, Set.of()).equals(Set.of("BeginX", "EndX")), "Incomplete connector glue");
        validateContainerMembership(shapes);
    }

    private static void validateNativePorts(Element shape) {
        double width = number(shape, "Width"), height = number(shape, "Height");
        for (var side : com.taxonomy.visio.VisioConnectionPoints.Side.values()) {
            Element port = connectionPoint(shape, side.number() - 1);
            check(port != null, "Missing native connection point");
            Element x = directCell(port, "X"), y = directCell(port, "Y");
            String xFraction = java.math.BigDecimal.valueOf(side.xFraction()).stripTrailingZeros().toPlainString();
            String yFraction = java.math.BigDecimal.valueOf(side.yFraction()).stripTrailingZeros().toPlainString();
            check(x != null && y != null && x.getAttribute("F").equals("Width*" + xFraction)
                    && y.getAttribute("F").equals("Height*" + yFraction), "Missing connection-point resize dependency");
            check(close(number(port, "X"), width * side.xFraction()) && close(number(port, "Y"), height * side.yFraction()),
                    "Cached connection point does not match shape size");
        }
    }

    private static Element directCell(Element parent, String name) {
        return children(parent).stream().filter(child -> child.getLocalName().equals("Cell") && child.getAttribute("N").equals(name)).findFirst().orElse(null);
    }

    private static Element connectionPoint(Element shape, int index) {
        for (Element section : children(shape)) if (section.getLocalName().equals("Section") && section.getAttribute("N").equals("Connection"))
            for (Element row : children(section)) if (row.getAttribute("IX").equals(Integer.toString(index))) return row;
        return null;
    }

    private static double number(Element parent, String name) {
        Element cell = directCell(parent, name);
        check(cell != null, "Missing geometry cell " + name);
        double number = Double.parseDouble(cell.getAttribute("V"));
        check(Double.isFinite(number), "Non-finite geometry cell " + name);
        return number;
    }

    private static boolean close(double actual, double expected) {
        return Double.isFinite(actual) && Math.abs(actual - expected) <= Math.max(1e-9, Math.abs(expected) * 1e-12);
    }

    private static void validateHyperlinks(Element shape) {
        for (Element section : children(shape)) if (section.getLocalName().equals("Section") && section.getAttribute("N").equals("Hyperlink"))
            for (Element row : children(section)) {
                for (Element cell : children(row)) check(!cell.hasAttribute("F"), "Hyperlinks must be literal");
                Element address = directCell(row, "Address");
                if (address != null && !address.getAttribute("V").isEmpty()) {
                    check(row.getAttribute("N").equals("Requirement"), "Unrecognized external hyperlink");
                    com.taxonomy.visio.VisioHyperlinks.validateRequirementUrl(com.taxonomy.visio.VisioProperty.text(address.getAttribute("V")));
                }
            }
    }

    private static boolean isContainer(Element shape) {
        for (Element section : children(shape)) if (section.getLocalName().equals("Section") && section.getAttribute("N").equals("User"))
            for (Element row : children(section)) if (row.getAttribute("N").equals("msvStructureType")) {
                Element value = directCell(row, "Value");
                return value != null && value.getAttribute("V").equals("Container");
            }
        return false;
    }

    private static void validateContainerMembership(Map<String, Element> shapes) {
        Map<String, Set<String>> members = new HashMap<>();
        Map<String, String> owners = new HashMap<>();
        var relationship = java.util.regex.Pattern.compile("DEPENDSON\\(([14]),(Sheet\\.[1-9][0-9]*!SheetRef\\(\\)(?:,Sheet\\.[1-9][0-9]*!SheetRef\\(\\))*)\\)");
        for (var entry : shapes.entrySet()) {
            Element cell = directCell(entry.getValue(), "Relationships");
            if (cell == null) continue;
            String formula = cell.getAttribute("F");
            var matcher = relationship.matcher(formula);
            List<String> clauses = new ArrayList<>();
            Set<String> kinds = new HashSet<>();
            while (matcher.find()) {
                clauses.add(matcher.group());
                check(kinds.add(matcher.group(1)), "Duplicate container relationship kind");
                for (String reference : matcher.group(2).split(",")) {
                    String target = reference.substring(6, reference.indexOf('!'));
                    check(shapes.containsKey(target) && !target.equals(entry.getKey()), "Unresolved container member");
                    if (matcher.group(1).equals("1")) {
                        check(isContainer(entry.getValue()), "Membership declared by a non-container");
                        check(members.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()).add(target), "Duplicate container member");
                    } else {
                        check(isContainer(shapes.get(target)) && owners.putIfAbsent(entry.getKey(), target) == null, "Invalid container owner");
                    }
                }
            }
            check(!clauses.isEmpty() && formula.equals("SUM(" + String.join(",", clauses) + ")"), "Invalid container relationship expression");
        }
        for (var entry : members.entrySet()) for (String member : entry.getValue())
            check(entry.getKey().equals(owners.get(member)), "Missing reciprocal container membership");
        for (var entry : owners.entrySet())
            check(members.getOrDefault(entry.getValue(), Set.of()).contains(entry.getKey()), "Missing reciprocal container membership");
        Set<String> visited = new HashSet<>();
        for (String start : owners.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && !visited.contains(current)) {
                check(path.add(current), "Cyclic container membership");
                current = owners.get(current);
            }
            visited.addAll(path);
        }
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
