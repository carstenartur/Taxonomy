package com.taxonomy.relations.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.dsl.validation.RelationMetamodel;
import org.springframework.stereotype.Component;

/** Service adapter for the one versioned, framework-free legality contract. */
@Component
public class RelationCompatibilityMatrix {
    public RelationMetamodel.TargetDomain targetDomain(String sourceRoot, RelationType type) {
        return RelationMetamodel.targetDomain(sourceRoot, type == null ? null : type.name());
    }
    public boolean isCompatible(String sourceRoot, String targetRoot, RelationType type) {
        return targetDomain(sourceRoot, type).allows(targetRoot);
    }
}
