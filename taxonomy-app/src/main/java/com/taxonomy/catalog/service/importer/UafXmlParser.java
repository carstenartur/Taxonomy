package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import com.taxonomy.dsl.mapping.ExternalRelation;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses UAF / UPDM XMI (XML) files into {@link ExternalElement}s and {@link ExternalRelation}s.
 *
 * <p>UAF models are typically exported as XMI with elements as {@code <packagedElement>}
 * and relations as nested or top-level elements with source/target attributes.
 *
 * <p>Security: External entities and DTD processing are disabled to prevent XXE attacks.
 */
public class UafXmlParser implements ExternalParser {

    @Override
    public String fileFormat() {
        return "xml";
    }

    @Override
    public ParsedExternalModel parse(InputStream input) throws Exception {
        List<ExternalElement> elements = new ArrayList<>();
        List<ExternalRelation> relations = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        XMLStreamReader reader = factory.createXMLStreamReader(input);

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();

                if ("packagedElement".equals(localName) || "ownedElement".equals(localName)) {
                    parsePackagedElement(reader, localName, elements, relations);
                } else if ("ownedConnector".equals(localName) || "connector".equals(localName)) {
                    parseConnector(reader, localName, relations);
                }
            }
        }
        reader.close();

        return new ParsedExternalModel(elements, relations);
    }

    private void parsePackagedElement(XMLStreamReader reader, String startTag,
                                       List<ExternalElement> elements,
                                       List<ExternalRelation> relations) throws Exception {
        String xmiId = getXmiId(reader);
        String xmiType = getXmiType(reader);
        String name = reader.getAttributeValue(null, "name");

        if (xmiId == null) return;

        // Extract the type suffix (e.g., "uaf:Capability" → "Capability")
        String type = extractTypeSuffix(xmiType);

        // Check if this is a relation-like element (e.g., Implements, DependsOn)
        String source = reader.getAttributeValue(null, "source");
        String target = reader.getAttributeValue(null, "target");

        if (source != null && target != null) {
            relations.add(new ExternalRelation(source, target, type, Map.of()));
            skipToEndElement(reader, startTag);
            return;
        }

        // Parse child elements for name and description
        String description = null;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String childName = reader.getLocalName();
                if ("name".equals(childName) && name == null) {
                    name = reader.getElementText();
                    depth--;
                } else if ("ownedComment".equals(childName) || "description".equals(childName)) {
                    String body = reader.getAttributeValue(null, "body");
                    if (body != null) {
                        description = body;
                    } else {
                        description = reader.getElementText();
                        depth--;
                    }
                } else if (("ownedConnector".equals(childName) || "connector".equals(childName))
                        && xmiId != null) {
                    // Nested connector inside an element
                    parseNestedConnector(reader, childName, xmiId, relations);
                    depth--;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }

        elements.add(new ExternalElement(xmiId, type, name, description, Map.of()));
    }

    private void parseConnector(XMLStreamReader reader, String startTag,
                                 List<ExternalRelation> relations) throws Exception {
        String xmiType = getXmiType(reader);
        String source = reader.getAttributeValue(null, "source");
        String target = reader.getAttributeValue(null, "target");
        String type = extractTypeSuffix(xmiType);

        if (source != null && target != null) {
            relations.add(new ExternalRelation(source, target,
                    type != null ? type : "Association", Map.of()));
        }

        skipToEndElement(reader, startTag);
    }

    private void parseNestedConnector(XMLStreamReader reader, String startTag,
                                       String parentId,
                                       List<ExternalRelation> relations) throws Exception {
        String xmiType = getXmiType(reader);
        String source = reader.getAttributeValue(null, "source");
        String target = reader.getAttributeValue(null, "target");
        String type = extractTypeSuffix(xmiType);

        // If source is missing, use the parent element
        if (source == null) source = parentId;

        if (target != null) {
            relations.add(new ExternalRelation(source, target,
                    type != null ? type : "Association", Map.of()));
        }

        skipToEndElement(reader, startTag);
    }

    private String getXmiId(XMLStreamReader reader) {
        // Try common XMI id attribute patterns
        String id = reader.getAttributeValue("http://www.omg.org/spec/XMI/20131001", "id");
        if (id == null) {
            id = reader.getAttributeValue("http://www.omg.org/XMI", "id");
        }
        if (id == null) {
            id = reader.getAttributeValue(null, "xmi:id");
        }
        if (id == null) {
            id = reader.getAttributeValue(null, "id");
        }
        return id;
    }

    private String getXmiType(XMLStreamReader reader) {
        String type = reader.getAttributeValue("http://www.w3.org/2001/XMLSchema-instance", "type");
        if (type == null) {
            type = reader.getAttributeValue("http://www.omg.org/spec/XMI/20131001", "type");
        }
        if (type == null) {
            type = reader.getAttributeValue(null, "xmi:type");
        }
        if (type == null) {
            type = reader.getAttributeValue(null, "type");
        }
        return type;
    }

    private String extractTypeSuffix(String xmiType) {
        if (xmiType == null) return "Unknown";
        int colon = xmiType.lastIndexOf(':');
        return colon >= 0 ? xmiType.substring(colon + 1) : xmiType;
    }

    private void skipToEndElement(XMLStreamReader reader, String elementName) throws Exception {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }
}
