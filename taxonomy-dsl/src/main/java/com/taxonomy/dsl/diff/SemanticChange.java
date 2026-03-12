package com.taxonomy.dsl.diff;

/**
 * A single semantic change derived from a {@link ModelDiff}.
 *
 * <p>Each instance describes <em>what</em> changed at the architecture level
 * (e.g. "Element title changed"), which entity was affected, and provides
 * a human-readable summary suitable for change logs and reviews.
 *
 * @param changeType   the category of the change
 * @param entityKind   {@code "element"} or {@code "relation"}
 * @param entityId     the element ID or relation composite key ({@code source|type|target})
 * @param description  human-readable summary (e.g. "Title changed from 'Auth' to 'Authentication'")
 * @param beforeValue  optional previous value (may be {@code null})
 * @param afterValue   optional new value (may be {@code null})
 */
public record SemanticChange(
        SemanticChangeType changeType,
        String entityKind,
        String entityId,
        String description,
        String beforeValue,
        String afterValue
) {

    /** Convenience factory for element-level changes. */
    public static SemanticChange element(SemanticChangeType type, String elementId, String description) {
        return new SemanticChange(type, "element", elementId, description, null, null);
    }

    /** Convenience factory for element-level changes with before/after values. */
    public static SemanticChange element(SemanticChangeType type, String elementId, String description,
                                         String beforeValue, String afterValue) {
        return new SemanticChange(type, "element", elementId, description, beforeValue, afterValue);
    }

    /** Convenience factory for relation-level changes. */
    public static SemanticChange relation(SemanticChangeType type, String relationKey, String description) {
        return new SemanticChange(type, "relation", relationKey, description, null, null);
    }

    /** Convenience factory for relation-level changes with before/after values. */
    public static SemanticChange relation(SemanticChangeType type, String relationKey, String description,
                                          String beforeValue, String afterValue) {
        return new SemanticChange(type, "relation", relationKey, description, beforeValue, afterValue);
    }
}
