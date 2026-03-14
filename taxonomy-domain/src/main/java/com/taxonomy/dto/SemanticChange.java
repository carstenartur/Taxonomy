package com.taxonomy.dto;

/**
 * A single semantic change between two architecture versions.
 *
 * <p>Represents one addition, removal, or modification of an element
 * or relation, together with before/after values where applicable.
 *
 * @param changeType     ADD, REMOVE, or MODIFY
 * @param category       ELEMENT or RELATION
 * @param id             the element ID or relation key
 * @param description    human-readable summary (e.g. "Element CP-1023 title changed")
 * @param beforeValue    the value before the change (null for additions)
 * @param afterValue     the value after the change (null for removals)
 */
public record SemanticChange(
    String changeType,
    String category,
    String id,
    String description,
    String beforeValue,
    String afterValue
) {}
