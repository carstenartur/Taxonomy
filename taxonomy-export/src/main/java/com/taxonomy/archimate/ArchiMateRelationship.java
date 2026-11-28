package com.taxonomy.archimate;

import java.util.Map;

/**
 * Native relationship projection with endpoints in canonical Taxonomy order.
 * {@code accessType} is non-null only for Access relationships ("Read" or "Write").
 */
public record ArchiMateRelationship(
        String id,
        String sourceId,
        String targetId,
        String archiMateType,
        String accessType,
        String name,
        Map<String, ArchiMateProperty> properties) {
    public ArchiMateRelationship {
        properties = Map.copyOf(properties);
    }

    public ArchiMateRelationship(String id, String sourceId, String targetId,
                                String archiMateType, String accessType, String name) {
        this(id, sourceId, targetId, archiMateType, accessType, name, Map.of());
    }
}
