package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.exchange.ReqifExchangeCodec;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Explicit profile-3 evidence envelope. Its custom fields do not claim a native EA metamodel. */
final class SparxMetadataV3 {
    private SparxMetadataV3() { }

    static void validate(Map<String, String> metadata) {
        String source = metadata.get("taxonomy.requirementText"), digest = metadata.get("taxonomy.requirementSha256");
        if (source != null || digest != null) {
            if (source == null || digest == null || !digest.equals(ReqifExchangeCodec.digest(source.getBytes(StandardCharsets.UTF_8))))
                throw ExchangeXml.invalid("SPARX_SOURCE_EVIDENCE", "Requirement text and its UTF-8 SHA-256 must agree");
        }
    }

    static void write(Element extension, Element model, Map<String, String> metadata) {
        validate(metadata);
        Element values = ExchangeXml.append(extension, null, "modelMetadata");
        new TreeMap<>(metadata).forEach((key, value) -> {
            Element tag = ExchangeXml.append(values, null, "tag");
            tag.setAttribute("name", key); tag.setAttribute("value", value);
        });
        if (metadata.containsKey("taxonomy.requirementText")) {
            Element comment = ExchangeXml.append(model, null, "ownedComment");
            comment.setAttributeNS(SparxMappingProfile.XMI, "xmi:type", "uml:Comment");
            comment.setAttributeNS(SparxMappingProfile.XMI, "xmi:id", model.getAttributeNS(SparxMappingProfile.XMI, "id") + "_requirement");
            ExchangeXml.text(comment, null, "body", metadata.get("taxonomy.requirementText"));
        }
    }

    static Map<String, String> read(Element section) {
        var metadata = new TreeMap<String, String>();
        for (Element field : ExchangeXml.children(section)) {
            if (!"tag".equals(field.getLocalName()) || !field.hasAttribute("name") || !field.hasAttribute("value")
                    || metadata.putIfAbsent(field.getAttribute("name"), field.getAttribute("value")) != null)
                throw ExchangeXml.invalid("SPARX_MODEL_EVIDENCE", "Invalid or duplicated model metadata field");
        }
        validate(metadata);
        return metadata;
    }
}
