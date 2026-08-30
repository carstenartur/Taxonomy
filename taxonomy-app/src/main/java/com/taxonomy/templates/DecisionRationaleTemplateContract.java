package com.taxonomy.templates;

import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract for the editable decision-rationale report template.
 *
 * <p>The cover page remains ordinary Word content. Taxonomy replaces stable text tokens,
 * removes the body marker, and appends the generated executive summary, decision chapters,
 * diagrams, and appendix. Keeping the marker as the final non-empty body block makes the
 * operation deterministic while preserving arbitrary branding, images, headers, footers,
 * styles, and page settings in the supported Word stories.</p>
 */
@Component
public final class DecisionRationaleTemplateContract implements DocumentTemplateContract {

    public static final String TEMPLATE_ID = "decision-rationale-report";
    public static final String DISPLAY_NAME = "Taxonomy decision rationale report";
    public static final String DEFAULT_RESOURCE =
            "document-templates/decision-rationale-report.dotx";

    public static final String BODY_MARKER = "{{taxonomy.report.body}}";
    public static final String TITLE_TOKEN = "{{taxonomy.report.title}}";
    public static final String REQUIREMENT_TOKEN = "{{taxonomy.report.requirement}}";

    private static final String WORD_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "\\{\\{taxonomy\\.[A-Za-z0-9.]+}}");
    private static final Set<String> SUPPORTED_TOKENS = Set.of(
            BODY_MARKER,
            TITLE_TOKEN,
            REQUIREMENT_TOKEN,
            "{{taxonomy.report.subtitle}}",
            "{{taxonomy.report.status}}",
            "{{taxonomy.report.generatedAt}}",
            "{{taxonomy.report.generatedBy}}",
            "{{taxonomy.report.taxonomyVersion}}",
            "{{taxonomy.report.applicationVersion}}",
            "{{taxonomy.report.commit}}",
            "{{taxonomy.report.repository}}",
            "{{taxonomy.report.workspace}}",
            "{{taxonomy.report.branch}}",
            "{{taxonomy.report.basedOnCommit}}",
            "{{taxonomy.report.analysisProvider}}",
            "{{taxonomy.template.id}}",
            "{{taxonomy.template.commit}}",
            "{{taxonomy.template.sha256}}");
    private static final Set<String> UNSUPPORTED_TOKEN_CONTAINERS = Set.of(
            "txbxContent",
            "sdt",
            "altChunk",
            "customXml");

    @Override
    public String templateId() {
        return TEMPLATE_ID;
    }

    @Override
    public void validate(Map<String, byte[]> packageParts) {
        if (packageParts == null) {
            throw invalid("OOXML package is missing");
        }
        byte[] documentXml = packageParts.get("word/document.xml");
        if (documentXml == null) {
            throw invalid("word/document.xml is missing");
        }
        Document document = parse("word/document.xml", documentXml);
        NodeList bodies = document.getElementsByTagNameNS(WORD_NS, "body");
        if (bodies.getLength() != 1) {
            throw invalid("word/document.xml must contain exactly one body");
        }
        validateBody((Element) bodies.item(0));
        validateTokenPlacement(packageParts);
    }

    /**
     * Validate the materialized Word document immediately before report rendering.
     *
     * <p>This second check also protects deployments where an expert modified the JGit
     * repository directly and bypassed the web upload path.</p>
     */
    public void validateDocument(XWPFDocument document) {
        if (document == null) {
            throw invalid("Word package could not be opened");
        }
        boolean title = false;
        boolean requirement = false;
        int markerCount = 0;
        boolean markerSeen = false;

        List<IBodyElement> elements = document.getBodyElements();
        for (IBodyElement element : elements) {
            String text = bodyElementText(element);
            if (!markerSeen) {
                title |= text.contains(TITLE_TOKEN);
                requirement |= text.contains(REQUIREMENT_TOKEN);
            }
            boolean marker = element.getElementType() == BodyElementType.PARAGRAPH
                    && text.strip().equals(BODY_MARKER);
            if (marker) {
                markerCount++;
                markerSeen = true;
                continue;
            }
            if (markerSeen && !isPermittedEmptyTrailingParagraph(element, text)) {
                throw invalid("the body marker must be the final non-empty body block");
            }
        }
        requireTokens(title, requirement, markerCount);
    }

    private static void validateTokenPlacement(Map<String, byte[]> packageParts) {
        for (Map.Entry<String, byte[]> entry : packageParts.entrySet()) {
            String path = entry.getKey();
            String lower = path.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".xml") || lower.endsWith(".rels"))) {
                continue;
            }
            String raw = new String(entry.getValue(), StandardCharsets.UTF_8);
            if (!raw.contains("{{taxonomy.")) {
                continue;
            }
            if (!lower.startsWith("word/") || !lower.endsWith(".xml")) {
                throw invalid("Taxonomy tokens are not supported in OOXML part " + path);
            }

            Document document = parse(path, entry.getValue());
            NodeList paragraphs = document.getElementsByTagNameNS(WORD_NS, "p");
            int foundTokens = 0;
            for (int index = 0; index < paragraphs.getLength(); index++) {
                Element paragraph = (Element) paragraphs.item(index);
                String text = elementText(paragraph);
                if (!text.contains("{{taxonomy.")) {
                    continue;
                }
                Matcher matcher = TOKEN_PATTERN.matcher(text);
                int paragraphTokens = 0;
                while (matcher.find()) {
                    paragraphTokens++;
                    foundTokens++;
                    validateToken(path, paragraph, matcher.group());
                }
                String residual = TOKEN_PATTERN.matcher(text).replaceAll("");
                if (paragraphTokens == 0 || residual.contains("{{taxonomy.")) {
                    throw invalid("malformed Taxonomy token in " + path);
                }
            }
            if (foundTokens == 0) {
                throw invalid("Taxonomy token is outside a supported Word paragraph in "
                        + path);
            }
        }
    }

    private static void validateToken(
            String path,
            Element paragraph,
            String token) {
        if (!isSupportedStory(path)) {
            throw invalid("Taxonomy token " + token
                    + " is not supported in Word story " + path);
        }
        if (!SUPPORTED_TOKENS.contains(token)) {
            throw invalid("unknown Taxonomy template token " + token + " in " + path);
        }
        if (containsUnsupportedContainer(paragraph)) {
            throw invalid("Taxonomy token " + token
                    + " is inside an unsupported Word container in " + path);
        }
        if (BODY_MARKER.equals(token) && !"word/document.xml".equals(path)) {
            throw invalid("the body marker is permitted only in word/document.xml");
        }
    }

    private static boolean isSupportedStory(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return "word/document.xml".equals(lower)
                || lower.matches("word/header[0-9]+\\.xml")
                || lower.matches("word/footer[0-9]+\\.xml");
    }

    private static boolean containsUnsupportedContainer(Element paragraph) {
        for (String localName : UNSUPPORTED_TOKEN_CONTAINERS) {
            if (paragraph.getElementsByTagNameNS(WORD_NS, localName).getLength() > 0) {
                return true;
            }
        }
        Node current = paragraph.getParentNode();
        while (current instanceof Element element) {
            if (UNSUPPORTED_TOKEN_CONTAINERS.contains(element.getLocalName())) {
                return true;
            }
            current = current.getParentNode();
        }
        return false;
    }

    private void validateBody(Element body) {
        boolean title = false;
        boolean requirement = false;
        int markerCount = 0;
        boolean markerSeen = false;

        NodeList children = body.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (!(node instanceof Element element)) {
                continue;
            }
            String localName = element.getLocalName();
            if ("sectPr".equals(localName)) {
                continue;
            }
            String text = elementText(element);
            if (!markerSeen) {
                title |= text.contains(TITLE_TOKEN);
                requirement |= text.contains(REQUIREMENT_TOKEN);
            }
            boolean marker = "p".equals(localName) && text.strip().equals(BODY_MARKER);
            if (marker) {
                markerCount++;
                markerSeen = true;
                continue;
            }
            if (markerSeen && !("p".equals(localName) && text.isBlank())) {
                throw invalid("the body marker must be the final non-empty body block");
            }
        }
        requireTokens(title, requirement, markerCount);
    }

    private static void requireTokens(
            boolean title,
            boolean requirement,
            int markerCount) {
        if (!title) {
            throw invalid("required token " + TITLE_TOKEN + " is missing");
        }
        if (!requirement) {
            throw invalid("required token " + REQUIREMENT_TOKEN + " is missing");
        }
        if (markerCount != 1) {
            throw invalid("required marker " + BODY_MARKER
                    + " must occur exactly once as a body paragraph");
        }
    }

    private static boolean isPermittedEmptyTrailingParagraph(
            IBodyElement element,
            String text) {
        return element.getElementType() == BodyElementType.PARAGRAPH && text.isBlank();
    }

    private static String bodyElementText(IBodyElement element) {
        if (element instanceof XWPFParagraph paragraph) {
            return paragraph.getText() == null ? "" : paragraph.getText();
        }
        if (element instanceof XWPFTable table) {
            return table.getText() == null ? "" : table.getText();
        }
        return "";
    }

    private static String elementText(Element element) {
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getElementsByTagNameNS(WORD_NS, "t");
        for (int index = 0; index < nodes.getLength(); index++) {
            text.append(nodes.item(index).getTextContent());
        }
        return text.toString();
    }

    private static Document parse(String path, byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw invalid(path + " is invalid or unsafe", exception);
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(
                "Decision report template '" + TEMPLATE_ID + "' is invalid: " + detail);
    }

    private static IllegalArgumentException invalid(String detail, Exception cause) {
        return new IllegalArgumentException(
                "Decision report template '" + TEMPLATE_ID + "' is invalid: " + detail,
                cause);
    }
}
