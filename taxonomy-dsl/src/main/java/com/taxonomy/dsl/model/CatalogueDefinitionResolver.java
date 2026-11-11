package com.taxonomy.dsl.model;

/** Resolve the immutable workbook definition that a canonical model pins. */
@FunctionalInterface
public interface CatalogueDefinitionResolver {
    CatalogueDefinition forModel(CanonicalArchitectureModel model);

    static CatalogueDefinitionResolver standard() { return model -> CatalogueDefinition.standard(); }
}
