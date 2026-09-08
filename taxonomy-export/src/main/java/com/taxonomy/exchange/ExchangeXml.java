package com.taxonomy.exchange;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shared bounded XML policy for exchange adapters. Uploaded schema locations never control validation. */
public final class ExchangeXml {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ARTIFACTS = 10_000;
    private static final int MAX_ELEMENTS = 250_000;
    private static final int MAX_DEPTH = 96;
    private static final ConcurrentHashMap<String, Schema> SCHEMAS = new ConcurrentHashMap<>();
    private static final Set<String> ARCHIMATE = Set.of("archimate3_Diagram.xsd", "archimate3_View.xsd", "archimate3_Model.xsd", "dc.xsd", "xml.xsd");
    private static final Set<String> REQIF = Set.of("reqif.xsd", "driver.xsd", "xml.xsd", "xhtml-attribs-1.xsd",
            "xhtml-blkphras-1.xsd", "xhtml-blkpres-1.xsd", "xhtml-blkstruct-1.xsd", "xhtml-datatypes-1.xsd",
            "xhtml-edit-1.xsd", "xhtml-framework-1.xsd", "xhtml-hypertext-1.xsd", "xhtml-inlphras-1.xsd",
            "xhtml-inlpres-1.xsd", "xhtml-inlstruct-1.xsd", "xhtml-inlstyle-1.xsd", "xhtml-list-1.xsd",
            "xhtml-object-1.xsd", "xhtml-param-1.xsd", "xhtml-pres-1.xsd", "xhtml-table-1.xsd", "xhtml-text-1.xsd");
    private ExchangeXml() {}

    /** Namespace-aware semantic comparison; formatting and XML prefix choices are not model changes. */
    public static String semantic(String source) {
        StringBuilder result = new StringBuilder(); semantic(parse(source.getBytes(StandardCharsets.UTF_8)).getDocumentElement(), result); return result.toString();
    }
    private static void semantic(Element node, StringBuilder result) {
        part(result, "{" + node.getNamespaceURI() + "}" + node.getLocalName());
        var attributes = new java.util.TreeMap<String, String>();
        for (int i = 0; i < node.getAttributes().getLength(); i++) {
            Node attribute = node.getAttributes().item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()) && !"LAST-CHANGE".equals(attribute.getNodeName())) {
                String value = attribute.getNodeValue();
                if (XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(attribute.getNamespaceURI()) && "type".equals(attribute.getLocalName())) {
                    int colon = value.indexOf(':'); String prefix = colon < 0 ? null : value.substring(0, colon);
                    value = "{" + node.lookupNamespaceURI(prefix) + "}" + value.substring(colon + 1);
                }
                attributes.put("{" + attribute.getNamespaceURI() + "}" + attribute.getLocalName(), value);
            }
        }
        attributes.forEach((key, value) -> { part(result, key); part(result, value); }); result.append('|');
        boolean rich = "http://www.w3.org/1999/xhtml".equals(node.getNamespaceURI());
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) semantic(element, result);
            else if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && (rich || !child.getNodeValue().isBlank())) part(result, child.getNodeValue());
        }
        result.append(';');
    }
    private static void part(StringBuilder target, String value) { target.append(value.length()).append(':').append(value); }

    public static Document parse(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_BYTES)
            throw invalid("PACKAGE_SIZE", "Exchange file must contain between 1 byte and 16 MiB");
        try {
            SAXParserFactory sax = SAXParserFactory.newInstance();
            sax.setNamespaceAware(true);
            sax.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            sax.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            sax.setFeature("http://xml.org/sax/features/external-general-entities", false);
            sax.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var reader = sax.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var bounds = new DefaultHandler() {
                int depth, elements;
                @Override public void startElement(String uri, String local, String name, org.xml.sax.Attributes attributes) throws SAXException {
                    if (++depth > MAX_DEPTH || ++elements > MAX_ELEMENTS || attributes.getLength() > 128)
                        throw new SAXException("XML complexity limit");
                }
                @Override public void endElement(String uri, String local, String name) { depth--; }
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            };
            reader.setContentHandler(bounds); reader.setErrorHandler(bounds);
            reader.parse(new org.xml.sax.InputSource(new ByteArrayInputStream(content)));

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder(); builder.setErrorHandler(bounds);
            Document document = builder.parse(new ByteArrayInputStream(content));
            passiveXhtml(document); return document;
        } catch (ExchangeFormatException rejected) { throw rejected; }
        catch (Exception rejected) { throw invalid("INVALID_XML", "Malformed, unsafe or over-complex exchange XML"); }
    }

    private static void passiveXhtml(Document document) {
        for (Element element : all(document, "http://www.w3.org/1999/xhtml", "*")) {
            if (Set.of("script", "iframe", "embed", "link", "style", "form", "input", "button").contains(element.getLocalName().toLowerCase(java.util.Locale.ROOT)))
                throw invalid("ACTIVE_XHTML_REJECTED", "Active rich-text content is outside the supported exchange profile");
            for (int i = 0; i < element.getAttributes().getLength(); i++) {
                Node attribute = element.getAttributes().item(i);
                String name = attribute.getLocalName().toLowerCase(java.util.Locale.ROOT), value = attribute.getNodeValue().strip();
                if (name.startsWith("on")) throw invalid("ACTIVE_XHTML_REJECTED", "Rich-text event handlers are not supported");
                if (name.equals("style") && (value.contains("\\") || value.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(url\\s*\\(|expression|@import|behavior|-moz-binding).*")))
                    throw invalid("ACTIVE_XHTML_REJECTED", "External or executable CSS is not supported");
                if (Set.of("href", "src", "data", "action", "background", "codebase").contains(name)) {
                    java.net.URI uri;
                    try { uri = java.net.URI.create(value); }
                    catch (IllegalArgumentException failure) { throw invalid("ACTIVE_XHTML_REJECTED", "Invalid rich-text resource URI"); }
                    if (value.startsWith("//") || uri.getUserInfo() != null || uri.getScheme() != null
                            && !(name.equals("href") && Set.of("https", "http", "mailto").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))))
                        throw invalid("ACTIVE_XHTML_REJECTED", "Only passive links and package-relative rich-text assets are supported");
                }
            }
        }
    }

    public static void validate(Document document, String profile) {
        try {
            var validator = SCHEMAS.computeIfAbsent(profile, ExchangeXml::schema).newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new DOMSource(document));
        } catch (Exception rejected) { throw invalid("SCHEMA_INVALID", "Exchange document does not satisfy the pinned supported schema"); }
    }

    private static Schema schema(String profile) {
        String root = switch (profile) { case "reqif-1.2" -> "/reqif-1.2/"; case "archimate-3.1" -> "/archimate-3.1/"; default -> throw invalid("UNKNOWN_PROFILE", "Unknown exchange profile"); };
        Set<String> files = profile.equals("reqif-1.2") ? REQIF : ARCHIMATE;
        String entry = profile.equals("reqif-1.2") ? "reqif.xsd" : "archimate3_Diagram.xsd";
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var implementation = (DOMImplementationLS) DOMImplementationRegistry.newInstance().getDOMImplementation("LS");
            factory.setResourceResolver((type, namespace, publicId, systemId, base) -> {
                String name = systemId == null ? "" : systemId.substring(systemId.lastIndexOf('/') + 1);
                if (!files.contains(name)) throw invalid("SCHEMA_REFERENCE", "Unpinned schema reference");
                var input = implementation.createLSInput();
                input.setPublicId(publicId); input.setSystemId(root + name);
                input.setByteStream(ExchangeXml.class.getResourceAsStream(root + name));
                if (input.getByteStream() == null) throw invalid("SCHEMA_MISSING", "Pinned schema is missing");
                return input;
            });
            try (var stream = ExchangeXml.class.getResourceAsStream(root + entry)) {
                if (stream == null) throw invalid("SCHEMA_MISSING", "Pinned schema is missing");
                return factory.newSchema(new StreamSource(stream, root + entry));
            }
        } catch (Exception failure) { throw new IllegalStateException("Cannot load pinned exchange schema", failure); }
    }

    public static byte[] write(Node node) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, node instanceof Document ? "no" : "yes");
            var output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(node), new StreamResult(output));
            if (output.size() > MAX_BYTES) throw invalid("PACKAGE_SIZE", "Generated exchange exceeds 16 MiB");
            return output.toByteArray();
        } catch (ExchangeFormatException e) { throw e; }
        catch (Exception e) { throw invalid("XML_WRITE", "Cannot serialize exchange document"); }
    }

    public static String xml(Node node) { return new String(write(node), StandardCharsets.UTF_8); }
    public static List<Element> children(Node node) {
        List<Element> result = new ArrayList<>();
        if (node != null) for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element element) result.add(element);
        return result;
    }
    public static Element child(Node parent, String local) {
        return children(parent).stream().filter(e -> local.equals(e.getLocalName())).findFirst().orElse(null);
    }
    public static List<Element> all(Document doc, String namespace, String local) {
        var nodes = doc.getElementsByTagNameNS(namespace, local); List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        return result;
    }
    public static String text(Node parent, String local) {
        Element element = child(parent, local); return element == null ? "" : element.getTextContent();
    }
    public static Element append(Node parent, String ns, String name) {
        Document doc = parent instanceof Document d ? d : parent.getOwnerDocument();
        Element result = doc.createElementNS(ns, name); parent.appendChild(result); return result;
    }
    public static Element text(Node parent, String ns, String name, String value) {
        Element result = append(parent, ns, name); result.setTextContent(value); return result;
    }
    public static String required(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        if (value.isBlank() || value.length() > 2048) throw invalid("INVALID_IDENTITY", "Missing or oversized external identity");
        return value;
    }
    public static ExchangeFormatException invalid(String code, String message) { return new ExchangeFormatException(code, message); }
}
