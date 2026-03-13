package com.taxonomy.dsl.model;

import java.util.Map;

/**
 * Shared mapping between taxonomy root codes and human-readable element type names.
 *
 * <p>This mapping is used across the DSL subsystem (export, import, validation)
 * to ensure consistent type names in DSL files.
 */
public final class TaxonomyRootTypes {

    private TaxonomyRootTypes() {} // utility class

    /** Maps taxonomy root codes to DSL element type names. */
    public static final Map<String, String> ROOT_TO_TYPE = Map.of(
            "CP", "Capability",
            "BP", "Process",
            "CR", "CoreService",
            "CI", "COIService",
            "CO", "CommunicationsService",
            "UA", "UserApplication",
            "IP", "InformationProduct",
            "BR", "BusinessRole"
    );

    /** Reverse mapping: DSL element type name → taxonomy root code. */
    public static final Map<String, String> TYPE_TO_ROOT;

    static {
        var reverse = new java.util.LinkedHashMap<String, String>();
        ROOT_TO_TYPE.forEach((root, type) -> reverse.put(type, root));
        TYPE_TO_ROOT = java.util.Collections.unmodifiableMap(reverse);
    }

    /**
     * Get the DSL element type name for a taxonomy root code.
     * Returns the root code itself if no mapping is defined.
     */
    public static String typeFor(String taxonomyRoot) {
        if (taxonomyRoot == null) return "Unknown";
        return ROOT_TO_TYPE.getOrDefault(taxonomyRoot, taxonomyRoot);
    }

    /**
     * Get the taxonomy root code for a DSL element type name.
     * Returns {@code null} if no mapping is defined.
     */
    public static String rootFor(String typeName) {
        if (typeName == null) return null;
        // Check if it's already a root code
        if (ROOT_TO_TYPE.containsKey(typeName)) return typeName;
        return TYPE_TO_ROOT.get(typeName);
    }

    /**
     * Extract the root code prefix from an element ID (e.g., "CP" from "CP-1023").
     * Returns {@code null} if the ID doesn't match the expected pattern.
     */
    public static String rootFromId(String elementId) {
        if (elementId == null || elementId.length() < 2) return null;
        int dash = elementId.indexOf('-');
        if (dash < 2) return null;
        String prefix = elementId.substring(0, dash);
        return ROOT_TO_TYPE.containsKey(prefix) ? prefix : null;
    }
}
