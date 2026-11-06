package com.taxonomy.plugins.sparx;

import com.taxonomy.sparx.mapping.SparxSemanticTypes;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Redundant visible declarations distinguish a Taxonomy meaning from its UML transport. */
final class QeaSemanticProfile {
    static final String PROFILE = "sparx-qea@2";
    private QeaSemanticProfile() { }

    static boolean declared(Map<String, String> attributes, Map<String, String> extensions) {
        return attributes.containsKey("tag:taxonomy.elementType") || attributes.containsKey("tag:taxonomy.relationType")
                || attributes.containsKey("tag:taxonomy.mappingProfile") || extensions.getOrDefault("stereotype", "").startsWith("Taxonomy::");
    }
    static String readElement(String transport, Map<String, String> attributes, Map<String, String> extensions) {
        String canonical = attributes.get("tag:taxonomy.elementType");
        if (!SparxSemanticTypes.elementTypes().containsKey(canonical == null ? "" : canonical)) invalid();
        require(SparxSemanticTypes.umlType(canonical), transport);
        declarations(canonical, attributes, extensions);
        projection(attributes, extensions);
        return canonical;
    }
    static String readRelation(String transport, Map<String, String> attributes, Map<String, String> extensions) {
        String canonical = attributes.get("tag:taxonomy.relationType");
        if (!SparxSemanticTypes.relationTypes().containsKey(canonical == null ? "" : canonical)) invalid();
        require(SparxSemanticTypes.relationTransport(canonical), transport);
        declarations(canonical, attributes, extensions);
        String direction = attributes.get("tag:taxonomy.relationDirection");
        direction(canonical, direction);
        require(direction, extensions.get("direction"));
        projection(attributes, extensions);
        return canonical;
    }
    private static void declarations(String canonical, Map<String, String> attributes, Map<String, String> extensions) {
        require(PROFILE, attributes.get("tag:taxonomy.mappingProfile"));
        require(SparxSemanticTypes.taxonomyStereotype(canonical), extensions.get("stereotype"));
    }
    private static void projection(Map<String, String> attributes, Map<String, String> extensions) {
        attributes.forEach((key, value) -> {
            if (key.startsWith("tag:taxonomy.") && !Set.of("elementType", "relationType", "mappingProfile", "relationDirection").contains(key.substring(13)))
                extensions.put("taxonomy:" + key.substring(13), value);
        });
    }
    static Map<String, String> tags(Map<String, String> attributes, Map<String, String> extensions, String canonical, boolean relation, String direction) {
        var tags = new TreeMap<String, String>();
        attributes.forEach((key, value) -> { if (key.startsWith("tag:")) tags.put(key.substring(4), value); });
        extensions.forEach((key, value) -> {
            if (!key.startsWith("taxonomy:")) return;
            String field = switch (key.substring(9)) {
                case "ElementType" -> "elementType"; case "RelationType" -> "relationType";
                case "id" -> "sourceId"; default -> key.substring(9);
            };
            put(tags, "taxonomy." + field, value);
        });
        if (canonical != null) {
            put(tags, "taxonomy." + (relation ? "relationType" : "elementType"), canonical);
            put(tags, "taxonomy.mappingProfile", PROFILE);
            if (relation) put(tags, "taxonomy.relationDirection", direction);
        }
        for (String name : tags.keySet()) if (name.isBlank() || name.length() > 2048 || name.chars().anyMatch(Character::isISOControl))
            throw fail("QEA_TAG_UNSUPPORTED", "Tagged-value names must be bounded plain text");
        return tags;
    }
    private static void put(Map<String, String> tags, String name, String value) {
        String previous = tags.putIfAbsent(name, value);
        if (previous != null && !previous.equals(value)) invalid();
    }
    static void direction(String canonical, String direction) {
        if (!Set.of("Source -> Destination", "Destination -> Source").contains(Objects.toString(direction, ""))
                && !("RELATED_TO".equals(canonical) && "Unspecified".equals(direction)))
            throw fail("QEA_RELATION_DIRECTION", "A directed Taxonomy relation requires an explicit unambiguous connector direction");
    }
    private static void require(String expected, String actual) { if (expected == null || !expected.equals(actual)) invalid(); }
    private static void invalid() { throw fail("QEA_TAXONOMY_DECLARATION_INVALID", "Taxonomy type, transport, qualified stereotype, profile and direction must agree"); }
}
