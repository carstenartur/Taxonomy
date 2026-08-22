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
import java.util.List;
import java.util.Map;

/**
 * Contract for the editable decision-rationale report template.
 *
 * <p>The cover page remains ordinary Word content. Taxonomy replaces stable text tokens,
 * removes the body marker, and appends the generated executive summary, decision chapters,
 * diagrams, and appendix. Keeping the marker as the final non-empty body block makes the
 * operation deterministic while preserving arbitrary branding, images, headers, footers,
 * styles, and page settings in the template.</p>
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
        Document document = parse(documentXml);
        NodeList bodies = document.getElementsByTagNameNS(WORD_NS, "body");
        if (bodies.getLength() != 1) {
            throw invalid("word/document.xml must contain exactly one body");
        }
        validateBody((Element) bodies.item(0));
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
            if (markerSeen
                    && !("p".equals(localName) && text.isBlank())) {
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

    private static Document parse(byte[] content) {
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
            throw invalid("word/document.xml is invalid or unsafe", exception);
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
