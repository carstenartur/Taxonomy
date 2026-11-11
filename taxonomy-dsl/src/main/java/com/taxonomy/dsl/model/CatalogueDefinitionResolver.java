package com.taxonomy.dsl.model;

/** Resolve the immutable workbook definition that a canonical model pins. */
@FunctionalInterface
public interface CatalogueDefinitionResolver {
    CatalogueDefinition forModel(CanonicalArchitectureModel model);

    /** Reads the applied workbook for an exact historical generation. */
    default CatalogueDefinition forRevision(String revision) {
        throw new IllegalStateException("Historical catalogue definitions are unavailable");
    }

    static CatalogueDefinitionResolver standard() { return model -> CatalogueDefinition.standard(); }
}
