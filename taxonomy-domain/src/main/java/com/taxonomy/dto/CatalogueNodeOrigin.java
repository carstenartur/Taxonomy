package com.taxonomy.dto;

/** Provenance class for catalogue nodes; only official source concepts may be architecture endpoints. */
public enum CatalogueNodeOrigin {
    OFFICIAL_SOURCE(true),
    VIRTUAL_ROOT(true),
    LOCAL_NAVIGATION(false);

    private final boolean architectureEndpoint;

    CatalogueNodeOrigin(boolean architectureEndpoint) {
        this.architectureEndpoint = architectureEndpoint;
    }

    public boolean mayBeArchitectureEndpoint() {
        return architectureEndpoint;
    }
}
