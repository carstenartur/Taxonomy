package com.taxonomy.templates;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed policy for executable, externally resolving and privacy-sensitive OOXML.
 *
 * <p>The generic OPC validator deliberately preserves ordinary Word features. This guard
 * rejects constructs that can execute or load external content, retain unresolved review
 * state, hide document content or distribute workstation and author metadata.</p>
 */
@Component
public final class OoxmlActiveContentValidator {

    private static final String WORD_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String CORE_PROPERTIES_NS =
            "http://schemas.openxmlformats.org/package/2006/metadata/core-properties";
    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";
    private static final String EXTENDED_PROPERTIES_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties";

    private static final Set<String> ALLOWED_HYPERLINK_SCHEMES =
            Set.of("https", "mailto");
    private static final Set<String> CONTROLLED_IDENTITY_VALUES = Set.of(
            "taxonomy",
            "taxonomy architecture analyzer",
            "taxonomy-bootstrap",
            "taxonomy report service");
    private static final Set<String> CONTROLLED_COMPANY_VALUES = Set.of(
            "organisation",
            "organization",
            "taxonomy",
            "taxonomy architecture analyzer");
    private static final Set<String> PROHIBITED_REVISION_ELEMENTS = Set.of(
            "commentRangeStart",
            "commentRangeEnd",
            "commentReference",
            "ins",
            "del",
            "moveFrom",
            "moveTo",
            "moveFromRangeStart",
            "moveFromRangeEnd",
            "moveToRangeStart",
            "moveToRangeEnd",
            "customXml",
            "customXmlPr",
            "customXmlInsRangeStart",
            "customXmlInsRangeEnd",
            "customXmlDelRangeStart",
            "customXmlDelRangeEnd",
            "customXmlMoveFromRangeStart",
            "customXmlMoveFromRangeEnd",
            "customXmlMoveToRangeStart",
            "customXmlMoveToRangeEnd",
            "cellIns",
            "cellDel",
            "cellMerge",
            "delText",
            "delInstrText");

    private static final Pattern URI_SCHEME = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Pattern UNSAFE_FIELD = Pattern.compile(
            "(?i)(?<![A-Z0-9_])"
                    + "(DDEAUTO|DDE|INCLUDETEXT|INCLUDEPICTURE|LINK|DATABASE|"
                    + "HYPERLINK|AUTOTEXTLIST|AUTOTEXT)"
                    + "(?![A-Z0-9_])");

    public void validate(Map<String, byte[]> parts) {
        if (parts == null) {
            throw invalid("OOXML package is missing");
        }
        validateForbiddenParts(parts);
        validateContentTypes(parts.get("[Content_Types].xml"));
        validateCoreProperties(parts.get("docProps/core.xml"));
        validateExtendedProperties(parts.get("docProps/app.xml"));

        for (Map.Entry<String, byte[]> part : parts.entrySet()) {
            String lower = part.getKey().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".rels")) {
                validateRelationships(part.getKey(), part.getValue());
            }
            if (lower.startsWith("word/") && lower.endsWith(".xml")) {
                Document document = parse(part.getKey(), part.getValue());
                validateWordFields(part.getKey(), document);
                validatePassiveWordContent(part.getKey(), document);
            }
            if (lower.endsWith(".htm") || lower.endsWith(".html")
                    || lower.endsWith(".mht") || lower.endsWith(".mhtml")
                    || lower.endsWith(".rtf")) {
                throw invalid("alternative-format part is not permitted: " + part.getKey());
            }
        }
    }

    private static void validateForbiddenParts(Map<String, byte[]> parts) {
        for (String path : parts.keySet()) {
            String lower = path.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("word/comments") && lower.endsWith(".xml"))
                    || "word/people.xml".equals(lower)) {
                throw invalid("comments and reviewer identity are not permitted: " + path);
            }
            if (lower.startsWith("customxml/")
                    || "docprops/custom.xml".equals(lower)) {
                throw invalid("custom XML metadata is not permitted: " + path);
            }
            if (lower.startsWith("word/printersettings/")) {
                throw invalid("printer-specific settings are not permitted: " + path);
            }
            if (lower.startsWith("docprops/thumbnail.")) {
                throw invalid("stale document thumbnails are not permitted: " + path);
            }
        }
    }

    private static void validateContentTypes(byte[] content) {
        if (content == null) {
            return;
        }
        Document document = parse("[Content_Types].xml", content);
        for (String elementName : Set.of("Default", "Override")) {
            NodeList elements = document.getElementsByTagNameNS("*", elementName);
            for (int index = 0; index < elements.getLength(); index++) {
                String value = ((Element) elements.item(index))
                        .getAttribute("ContentType").toLowerCase(Locale.ROOT);
                if (value.contains("html") || value.contains("xhtml")
                        || value.contains("mhtml") || value.contains("rfc822")
                        || value.contains("rtf")) {
                    throw invalid("active alternative-format content type is not permitted");
                }
            }
        }
    }

    private static void validateRelationships(String path, byte[] content) {
        Document document = parse(path, content);
        NodeList relationships = document.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < relationships.getLength(); index++) {
            Element relationship = (Element) relationships.item(index);
            String type = relationship.getAttribute("Type");
            String target = relationship.getAttribute("Target");
            boolean external = "External".equalsIgnoreCase(
                    relationship.getAttribute("TargetMode"));
            if (type.endsWith("/aFChunk") || type.endsWith("/afChunk")) {
                throw invalid("alternative-format relationship is not permitted in " + path);
            }
            if (external && type.endsWith("/hyperlink")) {
                validateExternalHyperlink(path, target);
            }
        }
    }

    private static void validateExternalHyperlink(String path, String target) {
        if (target == null || target.isBlank()
                || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0
                || target.indexOf('\0') >= 0) {
            throw invalid("external hyperlink target is invalid in " + path);
        }

        Matcher schemeMatcher = URI_SCHEME.matcher(target);
        if (!schemeMatcher.find()) {
            throw invalid("external hyperlink target is invalid in " + path);
        }
        String declaredScheme = schemeMatcher.group(1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_HYPERLINK_SCHEMES.contains(declaredScheme)) {
            throw invalid("external hyperlink scheme is not permitted in " + path);
        }

        try {
            URI uri = URI.create(target);
            String scheme = uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!declaredScheme.equals(scheme)) {
                throw invalid("external hyperlink target is invalid in " + path);
            }
            if ("https".equals(scheme)
                    && (uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null)) {
                throw invalid("external HTTPS hyperlink is invalid in " + path);
            }
            if ("mailto".equals(scheme)
                    && (uri.getSchemeSpecificPart() == null
                    || uri.getSchemeSpecificPart().isBlank())) {
                throw invalid("external mailto hyperlink is invalid in " + path);
            }
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("Unsafe OOXML")) {
                throw exception;
            }
            throw invalid("external hyperlink target is invalid in " + path, exception);
        }
    }

    private static void validateWordFields(String path, Document document) {
        StringBuilder combined = new StringBuilder();
        NodeList instructionText = document.getElementsByTagNameNS(WORD_NS, "instrText");
        for (int index = 0; index < instructionText.getLength(); index++) {
            String instruction = instructionText.item(index).getTextContent();
            validateInstruction(path, instruction);
            combined.append(instruction == null ? "" : instruction);
        }
        validateInstruction(path, combined.toString());

        NodeList simpleFields = document.getElementsByTagNameNS(WORD_NS, "fldSimple");
        for (int index = 0; index < simpleFields.getLength(); index++) {
            Element field = (Element) simpleFields.item(index);
            String instruction = field.getAttributeNS(WORD_NS, "instr");
            if (instruction == null || instruction.isBlank()) {
                instruction = field.getAttribute("w:instr");
            }
            validateInstruction(path, instruction);
        }
    }

    private static void validateInstruction(String path, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return;
        }
        Matcher matcher = UNSAFE_FIELD.matcher(instruction);
        if (matcher.find()) {
            throw invalid("Word field instruction "
                    + matcher.group(1).toUpperCase(Locale.ROOT)
                    + " is not permitted in " + path);
        }
    }

    private static void validatePassiveWordContent(String path, Document document) {
        for (String elementName : PROHIBITED_REVISION_ELEMENTS) {
            if (document.getElementsByTagNameNS(WORD_NS, elementName).getLength() > 0) {
                throw invalid("unresolved review or revision markup "
                        + elementName + " is not permitted in " + path);
            }
        }
        if (document.getElementsByTagNameNS(WORD_NS, "trackRevisions").getLength() > 0) {
            throw invalid("tracked-revision mode is not permitted in " + path);
        }
        if (document.getElementsByTagNameNS(WORD_NS, "vanish").getLength() > 0
                || document.getElementsByTagNameNS(WORD_NS, "webHidden").getLength() > 0) {
            throw invalid("hidden Word text is not permitted in " + path);
        }
    }

    private static void validateCoreProperties(byte[] content) {
        if (content == null) {
            return;
        }
        Document document = parse("docProps/core.xml", content);
        validateControlledIdentity(document, DC_NS, "creator", "creator");
        validateControlledIdentity(
                document, CORE_PROPERTIES_NS, "lastModifiedBy", "last-modified-by");
        rejectNonBlankProperty(
                document, CORE_PROPERTIES_NS, "lastPrinted", "last-printed timestamp");
    }

    private static void validateExtendedProperties(byte[] content) {
        if (content == null) {
            return;
        }
        Document document = parse("docProps/app.xml", content);
        rejectNonBlankProperty(document, EXTENDED_PROPERTIES_NS, "Manager", "manager");
        rejectNonBlankProperty(
                document, EXTENDED_PROPERTIES_NS, "HyperlinkBase", "hyperlink base");

        NodeList companies = document.getElementsByTagNameNS(
                EXTENDED_PROPERTIES_NS, "Company");
        for (int index = 0; index < companies.getLength(); index++) {
            String value = normalizedText(companies.item(index).getTextContent());
            if (!value.isEmpty() && !CONTROLLED_COMPANY_VALUES.contains(value)) {
                throw invalid("personal or organization-specific company metadata "
                        + "is not permitted in docProps/app.xml");
            }
        }

        NodeList templates = document.getElementsByTagNameNS(
                EXTENDED_PROPERTIES_NS, "Template");
        for (int index = 0; index < templates.getLength(); index++) {
            String value = templates.item(index).getTextContent();
            if (value != null && (value.contains("/") || value.contains("\\")
                    || value.contains(":"))) {
                throw invalid("workstation template paths are not permitted "
                        + "in docProps/app.xml");
            }
        }
    }

    private static void validateControlledIdentity(
            Document document,
            String namespace,
            String localName,
            String description) {
        NodeList values = document.getElementsByTagNameNS(namespace, localName);
        for (int index = 0; index < values.getLength(); index++) {
            String value = normalizedText(values.item(index).getTextContent());
            if (!value.isEmpty() && !CONTROLLED_IDENTITY_VALUES.contains(value)) {
                throw invalid("personal " + description
                        + " metadata is not permitted in docProps/core.xml");
            }
        }
    }

    private static void rejectNonBlankProperty(
            Document document,
            String namespace,
            String localName,
            String description) {
        NodeList values = document.getElementsByTagNameNS(namespace, localName);
        for (int index = 0; index < values.getLength(); index++) {
            String value = values.item(index).getTextContent();
            if (value != null && !value.isBlank()) {
                throw invalid(description + " metadata is not permitted");
            }
        }
    }

    private static String normalizedText(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static Document parse(String path, byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw invalid("invalid XML in " + path, exception);
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Unsafe OOXML template content: " + detail);
    }

    private static IllegalArgumentException invalid(String detail, Exception cause) {
        return new IllegalArgumentException(
                "Unsafe OOXML template content: " + detail, cause);
    }
}
