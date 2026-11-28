package com.taxonomy.exchange;

/** Native relationship direction shared by the workbench profile and generic XML exchange. */
public final class ArchiMateEndpointOrder {
    public static final String PROFILE_V3 = "taxonomy-archimate-3.1-v3";

    private ArchiMateEndpointOrder() { }

    public static boolean reversed(String profileVersion, String taxonomyType, String nativeType) {
        if (!PROFILE_V3.equals(profileVersion)) return false;
        return ("USES".equals(taxonomyType) || "DEPENDS_ON".equals(taxonomyType)) && "Serving".equals(nativeType)
                || "PART_OF".equals(taxonomyType) && "Composition".equals(nativeType);
    }
}
