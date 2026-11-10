package com.taxonomy.analysis.relations;

import com.taxonomy.model.RelationType;

/** Automatic discovery seeks typed structural meaning; generic links remain available to explicit callers. */
public final class RelationDiscoveryPolicy {
    private RelationDiscoveryPolicy() {}
    public static boolean automatic(RelationType type) { return type != null && type != RelationType.RELATED_TO; }
}
