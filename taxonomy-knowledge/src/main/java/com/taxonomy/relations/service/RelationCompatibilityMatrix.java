package com.taxonomy.relations.service;

import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.catalog.snapshot.FrozenCatalogueContext;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.validation.RelationMetamodel;
import com.taxonomy.model.RelationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** Service adapter for the one versioned, framework-free legality contract. */
@Component
public class RelationCompatibilityMatrix {
    private final Supplier<CatalogueDefinition> definitions;

    public RelationCompatibilityMatrix() {
        this(() -> FrozenCatalogueContext.definition().orElseGet(CatalogueDefinition::standard));
    }

    @Autowired
    public RelationCompatibilityMatrix(RetainedCatalogueDefinitions definitions) {
        this(definitions::current);
    }

    public RelationCompatibilityMatrix(Supplier<CatalogueDefinition> definitions) {
        this.definitions = Objects.requireNonNull(definitions);
    }

    public RelationCompatibilityMatrix(CatalogueDefinition definition) {
        this(() -> definition);
    }

    public CatalogueDefinition definition() { return definitions.get(); }

    public RelationMetamodel.TargetDomain targetDomain(String sourceRoot, RelationType type) {
        return definition().targetDomain(sourceRoot, type == null ? null : type.name());
    }
    public boolean isCompatible(String sourceRoot, String targetRoot, RelationType type) {
        return definition().allows(sourceRoot, type == null ? null : type.name(), targetRoot);
    }
}
