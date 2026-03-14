package com.taxonomy.dsl.mapping;

import java.util.Map;

/**
 * Generic representation of a relation from an external architecture framework.
 *
 * @param sourceId   identifier of the source element in the external model
 * @param targetId   identifier of the target element in the external model
 * @param type       the relation type in the external framework (e.g. {@code "Realization"}, {@code "Implements"})
 * @param properties additional framework-specific properties
 */
public record ExternalRelation(
        String sourceId,
        String targetId,
        String type,
        Map<String, String> properties
) {}
