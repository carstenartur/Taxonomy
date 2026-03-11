package com.nato.taxonomy.dsl.model;

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

    /**
     * Get the DSL element type name for a taxonomy root code.
     * Returns the root code itself if no mapping is defined.
     */
    public static String typeFor(String taxonomyRoot) {
        if (taxonomyRoot == null) return "Unknown";
        return ROOT_TO_TYPE.getOrDefault(taxonomyRoot, taxonomyRoot);
    }
}
