package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.exchange.ExchangeXml;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure canonical field mapping shared by inbound selection and publication planning. No authority is granted here. */
public final class IntegrationMappingFields {
    private IntegrationMappingFields() {}
    public static Artifact remap(Artifact artifact, MappingOverride mapping) {
        if (mapping == null) return artifact;
        Map<String, String> extensions = new TreeMap<>(artifact.extensions()); String title = artifact.title(), text = artifact.text();
        if (mapping.canonicalType() != null && !mapping.canonicalType().isBlank()) {
            Set<String> supported = artifact.kind() == ArtifactKind.ELEMENT
                    ? Set.of("Capability", "Process", "CoreService", "COIService", "CommunicationsService", "UserApplication", "InformationProduct", "BusinessRole", "System", "Component")
                    : artifact.kind() == ArtifactKind.RELATION ? com.taxonomy.dsl.validation.DslValidator.relationTypes() : Set.of();
            if (!supported.contains(mapping.canonicalType())) throw new IllegalArgumentException("Unsupported canonical type mapping");
            extensions.put("canonicalType", mapping.canonicalType());
            extensions.put("taxonomy:" + (artifact.kind() == ArtifactKind.ELEMENT ? "ElementType" : "RelationType"), mapping.canonicalType());
        }
        if (mapping.titleAttribute() != null && !mapping.titleAttribute().isBlank()) {
            title = mappedText(artifact, mapping.titleAttribute()); extensions.put("titleAttribute", mapping.titleAttribute());
        }
        if (mapping.textAttribute() != null && !mapping.textAttribute().isBlank()) {
            text = mappedText(artifact, mapping.textAttribute()); extensions.put("textAttribute", mapping.textAttribute());
        }
        if (extensions.get("titleAttribute") != null && extensions.get("titleAttribute").equals(extensions.get("textAttribute")))
            throw new IllegalArgumentException("Title and body require independent mappings");
        return new Artifact(artifact.id(), artifact.kind(), artifact.type(), title, text, artifact.attributes(), extensions);
    }
    private static String mappedText(Artifact artifact, String attribute) {
        if (artifact.kind() != ArtifactKind.REQUIREMENT || !artifact.attributes().containsKey(attribute)) throw new IllegalArgumentException("Unknown requirement attribute mapping");
        String value = artifact.attributes().get(attribute);
        return "XHTML".equals(artifact.extensions().get("kind:" + attribute)) ? ExchangeXml.parse(value.getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTextContent() : value;
    }
}
