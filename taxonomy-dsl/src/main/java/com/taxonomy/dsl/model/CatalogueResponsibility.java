package com.taxonomy.dsl.model;

/** A direct responsibility on a catalogue code, independent of modeled elements. */
public record CatalogueResponsibility(String code, String organizationId, String catalogueRevision) {
    public static final String KIND = "catalogueResponsibility";
    public static final String ORGANIZATION = "organization";
    public static final String REVISION = "catalogueRevision";

    public CatalogueResponsibility(String code, String organizationId) { this(code, organizationId, null); }
}
