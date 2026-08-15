package com.taxonomy.tooling;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class XmlSupport {

    static final String MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0";

    private XmlSupport() {
    }

    static Document parse(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input);
        }
    }

    static Document parse(InputStream input) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(input);
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Cannot parse XML: " + error.getMessage(), error);
        }
    }

    static String childText(Element parent, String localName) {
        if (parent == null) {
            return "";
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())) {
                return text(element);
            }
        }
        return "";
    }

    static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    static List<Element> descendants(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        collect(parent, localName, result);
        return result;
    }

    private static void collect(
            Element parent,
            String localName,
            List<Element> result) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element) {
                if (localName.equals(element.getLocalName())) {
                    result.add(element);
                }
                collect(element, localName, result);
            }
        }
    }

    static String text(Element element) {
        return element == null ? "" : element.getTextContent().strip();
    }

    static String rootProjectVersion(Path pom) throws IOException {
        Element project = parse(pom).getDocumentElement();
        String version = childText(project, "version");
        if (version.isBlank()) {
            throw new IllegalArgumentException("root pom.xml has no project version");
        }
        return version;
    }
}
