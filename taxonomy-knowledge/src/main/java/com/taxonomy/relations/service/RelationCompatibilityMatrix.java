package com.taxonomy.relations.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.dsl.validation.RelationMetamodel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Service adapter for the one versioned, framework-free legality contract. */
@Component
public class RelationCompatibilityMatrix {
    public RelationMetamodel.TargetDomain targetDomain(String sourceRoot, RelationType type) {
        return RelationMetamodel.targetDomain(sourceRoot, type == null ? null : type.name());
    }
    public Map<RelationType, Set<String>> getExpectedOutgoingRelations(String sourceRoot) {
        var outgoing = new EnumMap<RelationType, Set<String>>(RelationType.class);
        for (RelationType type : RelationType.values()) {
            var domain = targetDomain(sourceRoot, type);
            if (domain.state() == RelationMetamodel.DomainState.RESTRICTED) {
                outgoing.put(type, domain.roots());
            }
        }
        return outgoing;
    }

    public boolean isCompatible(String sourceRoot, String targetRoot, RelationType type) {
        return targetDomain(sourceRoot, type).allows(targetRoot);
    }
}
