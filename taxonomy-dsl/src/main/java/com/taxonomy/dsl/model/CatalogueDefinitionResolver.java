package com.taxonomy.dsl.model;

/** Resolve the immutable workbook definition that a canonical model pins. */
@FunctionalInterface
public interface CatalogueDefinitionResolver {
    CatalogueDefinition forModel(CanonicalArchitectureModel model);

    /** Parent codes for catalogue elements in this model, including intermediate catalogue entries. */
    default java.util.Map<String, String> parentsForModel(CanonicalArchitectureModel model) {
        return java.util.Map.of();
    }

    /** Reads the applied workbook for an exact historical generation. */
    default CatalogueDefinition forRevision(String revision) {
        throw new IllegalStateException("Historical catalogue definitions are unavailable");
    }

    static CatalogueDefinitionResolver standard() { return model -> CatalogueDefinition.standard(); }
}
