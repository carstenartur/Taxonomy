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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed policy for active constructs that remain possible in macro-free OOXML.
 *
 * <p>The generic OPC validator deliberately preserves ordinary Word features. This
 * guard rejects constructs that can load or execute external content when a generated
 * report is opened, including DDE fields, external include fields, alternative-format
 * imports and non-approved hyperlink schemes.</p>
 */
@Component
public final class OoxmlActiveContentValidator {

    private static final String WORD_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final Set<String> ALLOWED_HYPERLINK_SCHEMES =
            Set.of("https", "mailto");
    private static final Pattern URI_SCHEME = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final int MAX_PERCENT_DECODE_ROUNDS = 8;
    private static final Pattern UNSAFE_FIELD = Pattern.compile(
            "(?i)(?<![A-Z0-9_])"
                    + "(DDEAUTO|DDE|INCLUDETEXT|INCLUDEPICTURE|LINK|DATABASE|"
                    + "HYPERLINK|AUTOTEXTLIST|AUTOTEXT)"
                    + "(?![A-Z0-9_])");

    public void validate(Map<String, byte[]> parts) {
        if (parts == null) {
            throw invalid("OOXML package is missing");
        }
        validateContentTypes(parts.get("[Content_Types].xml"));
        for (Map.Entry<String, byte[]> part : parts.entrySet()) {
            String lower = part.getKey().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".rels")) {
                validateRelationships(part.getKey(), part.getValue());
            }
            if (lower.startsWith("word/") && lower.endsWith(".xml")) {
                validateWordFields(part.getKey(), part.getValue());
            }
            if (lower.endsWith(".htm") || lower.endsWith(".html")
                    || lower.endsWith(".mht") || lower.endsWith(".mhtml")
                    || lower.endsWith(".rtf")) {
                throw invalid("alternative-format part is not permitted: " + part.getKey());
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
                || containsControlCharacter(target)
                || containsUnsafePercentEncoding(target)) {
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

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    /**
     * Decode valid percent-octet runs as strict UTF-8 and inspect every resulting layer.
     * The bounded loop handles intentionally nested encodings without allowing an
     * attacker-controlled quadratic decode chain. A ninth layer is rejected fail-closed.
     */
    private static boolean containsUnsafePercentEncoding(String value) {
        String normalized = value;
        for (int round = 0; round < MAX_PERCENT_DECODE_ROUNDS; round++) {
            PercentDecodeResult decoded = decodePercentEscapes(normalized);
            if (!decoded.valid()) {
                return true;
            }
            if (!decoded.changed()) {
                return false;
            }
            normalized = decoded.value();
            if (containsControlCharacter(normalized)) {
                return true;
            }
        }
        return decodePercentEscapes(normalized).changed();
    }

    private static PercentDecodeResult decodePercentEscapes(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        boolean changed = false;
        for (int index = 0; index < value.length();) {
            if (!isEncodedOctet(value, index)) {
                decoded.append(value.charAt(index));
                index++;
                continue;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (isEncodedOctet(value, index)) {
                bytes.write((hex(value.charAt(index + 1)) << 4)
                        | hex(value.charAt(index + 2)));
                index += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException exception) {
                return new PercentDecodeResult(value, changed, false);
            }
            changed = true;
        }
        return new PercentDecodeResult(decoded.toString(), changed, true);
    }

    private static boolean isEncodedOctet(String value, int index) {
        return index + 2 < value.length()
                && value.charAt(index) == '%'
                && hex(value.charAt(index + 1)) >= 0
                && hex(value.charAt(index + 2)) >= 0;
    }

    private static int hex(char value) {
        return Character.digit(value, 16);
    }

    private record PercentDecodeResult(String value, boolean changed, boolean valid) {
    }

    private static void validateWordFields(String path, byte[] content) {
        Document document = parse(path, content);
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

    private static Document parse(String path, byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw invalid("invalid XML in " + path, exception);
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Unsafe OOXML active content: " + detail);
    }

    private static IllegalArgumentException invalid(String detail, Exception cause) {
        return new IllegalArgumentException("Unsafe OOXML active content: " + detail, cause);
    }
}
