package com.nato.taxonomy.archimate;

/**
 * Represents an ArchiMate relationship between two elements.
 * {@code accessType} is non-null only for Access relationships ("Read" or "Write").
 */
public record ArchiMateRelationship(
        String id,
        String sourceId,
        String targetId,
        String archiMateType,
        String accessType,
        String name) {}
