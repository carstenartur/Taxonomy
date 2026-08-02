package com.taxonomy.dsl.merge;

/**
 * One semantic three-way merge conflict in a Taxonomy DSL document.
 *
 * @param blockIdentity stable block identity ({@code kind + header tokens})
 * @param propertyKey property that conflicts, or {@code null} for a whole-block conflict
 * @param reason human-readable conflict classification
 * @param baseValue value in the merge base
 * @param oursValue value in the target branch
 * @param theirsValue value in the incoming branch
 */
public record TaxDslMergeConflict(
        String blockIdentity,
        String propertyKey,
        String reason,
        String baseValue,
        String oursValue,
        String theirsValue) {

    /** Compact identifier suitable for REST responses and Git conflict panels. */
    public String identifier() {
        return propertyKey == null || propertyKey.isBlank()
                ? blockIdentity
                : blockIdentity + ":" + propertyKey;
    }
}
