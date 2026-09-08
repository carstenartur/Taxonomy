package com.taxonomy.export;

import org.w3c.dom.Document;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Set;

/** Offline normative validation at both exchange boundaries, using the pinned upstream XSDs. */
public final class ArchiMateSchema {
    public static final String NAMESPACE = "http://www.opengroup.org/xsd/archimate/3.0/";
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_ELEMENTS = 10_000;
    public static final int MAX_RELATIONSHIPS = 30_000;
    private static final Set<String> FILES = Set.of("archimate3_Diagram.xsd", "archimate3_View.xsd",
            "archimate3_Model.xsd", "dc.xsd", "xml.xsd");
    private static final Schema SCHEMA = load();

    private ArchiMateSchema() { }

    public static Document parse(byte[] xml) {
        if (xml == null || xml.length == 0 || xml.length > MAX_BYTES) {
            throw new IllegalArgumentException("ArchiMate document must contain 1.." + MAX_BYTES + " bytes");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setSchema(SCHEMA);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) throws SAXException { throw e; }
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            Document document = builder.parse(new ByteArrayInputStream(xml));
            if (!NAMESPACE.equals(document.getDocumentElement().getNamespaceURI())
                    || !"model".equals(document.getDocumentElement().getLocalName())) {
                throw new IllegalArgumentException("Expected an ArchiMate model");
            }
            if (document.getElementsByTagNameNS(NAMESPACE, "element").getLength() > MAX_ELEMENTS
                    || document.getElementsByTagNameNS(NAMESPACE, "relationship").getLength() > MAX_RELATIONSHIPS
                    || document.getElementsByTagNameNS(NAMESPACE, "view").getLength() > 32
                    || document.getElementsByTagNameNS(NAMESPACE, "node").getLength() > 50_000
                    || document.getElementsByTagNameNS(NAMESPACE, "connection").getLength() > 150_000) {
                throw new IllegalArgumentException("ArchiMate model exceeds the bounded exchange profile");
            }
            return document;
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException | IOException exception) {
            throw new IllegalArgumentException("Invalid ArchiMate 3.1 Exchange XML: " + exception.getMessage(), exception);
        }
    }

    private static Schema load() {
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DOMImplementationLS implementation = (DOMImplementationLS) DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().getDOMImplementation().getFeature("LS", "3.0");
            factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
                String name = systemId == null ? "" : systemId.substring(systemId.lastIndexOf('/') + 1);
                if (!FILES.contains(name)) throw new IllegalArgumentException("Unpinned schema reference");
                URL url = resource(name);
                LSInput input = implementation.createLSInput();
                input.setSystemId(url.toExternalForm());
                input.setPublicId(publicId);
                try (InputStream stream = url.openStream()) {
                    input.setByteStream(new ByteArrayInputStream(stream.readAllBytes()));
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
                return input;
            });
            URL root = resource("archimate3_Diagram.xsd");
            try (InputStream stream = root.openStream()) {
                return factory.newSchema(new StreamSource(stream, root.toExternalForm()));
            }
        } catch (SAXException | IOException | javax.xml.parsers.ParserConfigurationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static URL resource(String name) {
        return java.util.Objects.requireNonNull(ArchiMateSchema.class.getResource("/archimate-3.1/" + name),
                "Missing pinned ArchiMate schema: " + name);
    }
}
