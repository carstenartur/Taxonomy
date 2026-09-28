package com.taxonomy.exchange;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.taxonomy.exchange.ExchangeXml.*;

/** Reserved, schema-valid ReqIF attribute. Does not masquerade as native planning/compliance semantics. */
final class ReqifPlanningAttributes {
    private static final String NS = ReqifExchangeCodec.NS;
    private static final String DATATYPE = "taxonomy-planning-string-v1";
    private static final String TIME = "1970-01-01T00:00:00Z";
    private ReqifPlanningAttributes() {}
    static void read(String artifact, Map<String, String> values, Map<String, String> extensions, List<MappingLoss> losses) {
        var definitions = extensions.entrySet().stream().filter(e -> e.getKey().startsWith("definition:") && PlanningEnvelope.ATTRIBUTE.equals(e.getValue())).toList();
        if (definitions.size() > 1) throw invalid("More than one planning attribute is present on the same object");
        if (definitions.isEmpty()) return;
        String id = definitions.getFirst().getKey().substring(11);
        if (!"STRING".equals(extensions.get("kind:" + id))) throw invalid("The planning attribute must be a STRING");
        String payload = values.get(id);
        if (payload != null) {
            PlanningEnvelope.read(payload);
            extensions.put(PlanningEnvelope.EXTENSION, payload);
            losses.add(PlanningEnvelope.preservation(artifact));
        }
    }
    static void write(Document document, Element object, Artifact artifact, Map<String, Element> identities) {
        String payload = artifact.extensions().get(PlanningEnvelope.EXTENSION);
        if (payload == null) return;
        PlanningEnvelope.read(payload);
        String typeId = child(object, "TYPE").getTextContent().strip();
        Element type = identities.get(typeId);
        if (type == null || !"SPEC-OBJECT-TYPE".equals(type.getLocalName())) throw invalid("Missing requirement object type");
        Element definitions = child(type, "SPEC-ATTRIBUTES");
        if (definitions == null) definitions = append(type, NS, "SPEC-ATTRIBUTES");
        var matches = children(definitions).stream().filter(e -> PlanningEnvelope.ATTRIBUTE.equals(e.getAttribute("LONG-NAME"))).toList();
        if (matches.size() > 1) throw invalid("Ambiguous planning attribute definition");
        Element definition;
        if (!matches.isEmpty()) {
            definition = matches.getFirst();
            if (!"ATTRIBUTE-DEFINITION-STRING".equals(definition.getLocalName())) throw invalid("Planning attribute is not a STRING");
            Element typeRef = child(definition, "TYPE");
            Element datatype = typeRef == null ? null : identities.get(typeRef.getTextContent().strip());
            if (datatype == null || !"DATATYPE-DEFINITION-STRING".equals(datatype.getLocalName())
                    || Integer.parseInt(datatype.getAttribute("MAX-LENGTH")) < payload.length())
                throw invalid("Target planning attribute cannot hold the complete payload; truncation is forbidden");
        } else {
            Element datatype = identities.get(DATATYPE);
            if (datatype == null) {
                Element core = all(document, NS, "REQ-IF-CONTENT").getFirst();
                Element datatypes = child(core, "DATATYPES");
                if (datatypes == null) throw invalid("Missing ReqIF datatypes");
                datatype = define(datatypes, "DATATYPE-DEFINITION-STRING", DATATYPE);
                datatype.setAttribute("MAX-LENGTH", Integer.toString(PlanningEnvelope.MAX_LENGTH));
                identities.put(DATATYPE, datatype);
            } else if (!"DATATYPE-DEFINITION-STRING".equals(datatype.getLocalName())
                    || !Integer.toString(PlanningEnvelope.MAX_LENGTH).equals(datatype.getAttribute("MAX-LENGTH")))
                throw invalid("Planning datatype identity collision");
            String id = "taxonomy-planning-" + UUID.nameUUIDFromBytes(typeId.getBytes(StandardCharsets.UTF_8));
            if (identities.containsKey(id)) throw invalid("Planning definition identity collision");
            definition = define(definitions, "ATTRIBUTE-DEFINITION-STRING", id);
            definition.setAttribute("LONG-NAME", PlanningEnvelope.ATTRIBUTE);
            text(append(definition, NS, "TYPE"), NS, "DATATYPE-DEFINITION-STRING-REF", DATATYPE);
            identities.put(id, definition);
        }
        String id = definition.getAttribute("IDENTIFIER");
        Element values = child(object, "VALUES");
        if (values == null) { values = document.createElementNS(NS, "VALUES"); object.insertBefore(values, child(object, "TYPE")); }
        for (Element value : List.copyOf(children(values))) {
            Element ref = child(value, "DEFINITION");
            if (ref != null && id.equals(ref.getTextContent().strip())) values.removeChild(value);
        }
        Element value = append(values, NS, "ATTRIBUTE-VALUE-STRING"); value.setAttribute("THE-VALUE", payload);
        text(append(value, NS, "DEFINITION"), NS, "ATTRIBUTE-DEFINITION-STRING-REF", id);
    }
    /** Native base definitions may gain our reserved field; unrelated type collisions still fail. */
    static boolean equivalentWithoutPlanning(Element left, Element right) {
        Element a = (Element) left.cloneNode(true), b = (Element) right.cloneNode(true);
        for (Element root : List.of(a, b)) {
            Element attributes = child(root, "SPEC-ATTRIBUTES");
            if (attributes != null) for (Element field : List.copyOf(children(attributes)))
                if (PlanningEnvelope.ATTRIBUTE.equals(field.getAttribute("LONG-NAME"))) attributes.removeChild(field);
        }
        return ExchangeXml.semantic(xml(a)).equals(ExchangeXml.semantic(xml(b)));
    }
    private static Element define(Element parent, String tag, String id) {
        Element node = append(parent, NS, tag); node.setAttribute("IDENTIFIER", id); node.setAttribute("LAST-CHANGE", TIME); return node;
    }
    private static ExchangeFormatException invalid(String message) { return new ExchangeFormatException("PLANNING_ATTRIBUTE_MAPPING", message); }
}
