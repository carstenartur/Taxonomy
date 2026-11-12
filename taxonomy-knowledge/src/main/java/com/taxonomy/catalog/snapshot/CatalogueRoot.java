package com.taxonomy.catalog.snapshot;

/** Supported catalogue partitions. Node membership is validated from recorded data, never prefixes. */
public enum CatalogueRoot {
    BP, BR, CP, CI, CO, CR, IP, UA;

    public static String validateIdentifier(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid catalogue root: " + code);
        return code;
    }

    public static CatalogueRoot require(String code) {
        if (code == null) throw new IllegalArgumentException("Catalogue root is required");
        try {
            return valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown catalogue root: " + code, exception);
        }
    }
}
