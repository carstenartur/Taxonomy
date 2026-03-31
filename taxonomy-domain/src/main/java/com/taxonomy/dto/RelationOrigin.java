package com.taxonomy.dto;

/**
 * Classifies how a relationship was derived or discovered.
 *
 * <p>Each value identifies the provenance of a relation in the
 * architecture view, enabling the UI to distinguish confirmed
 * seed relations from inferred or suggested ones.
 *
 * <p>Use {@link #messageKey()} to obtain the i18n property key for
 * the display label (e.g. {@code relation.origin.taxonomy.seed}).
 *
 * <p>Use {@link #category()} to obtain the coarse display category
 * ({@code "seed"}, {@code "trace"}, or {@code "impact"}) that the
 * UI uses to partition relations into sections.
 */
public enum RelationOrigin {

    /** Loaded from the seed CSV (TYPE_DEFAULT, FRAMEWORK_SEED, or SOURCE_DERIVED). */
    TAXONOMY_SEED("relation.origin.taxonomy.seed", RequirementRelationshipView.CATEGORY_SEED),

    /** Discovered through BFS traversal during relevance propagation. */
    PROPAGATED_TRACE("relation.origin.propagated.trace", RequirementRelationshipView.CATEGORY_TRACE),

    /** Derived as a cross-category leaf-to-leaf impact relation. */
    IMPACT_DERIVED("relation.origin.impact.derived", RequirementRelationshipView.CATEGORY_IMPACT),

    /** Proposed by gap analysis or embedding-based similarity. */
    SUGGESTED_CANDIDATE("relation.origin.suggested.candidate", RequirementRelationshipView.CATEGORY_IMPACT),

    /** Supported or confirmed by LLM inference. */
    LLM_SUPPORTED("relation.origin.llm.supported", RequirementRelationshipView.CATEGORY_IMPACT);

    private final String messageKey;
    private final String category;

    RelationOrigin(String messageKey, String category) {
        this.messageKey = messageKey;
        this.category = category;
    }

    /** Returns the i18n message key for the display label of this origin. */
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the coarse display category for this origin.
     *
     * <p>The three categories map directly to the UI sections:
     * <ul>
     *   <li>{@code "seed"}   — structural seed relations (root-to-root)</li>
     *   <li>{@code "trace"}  — scoring-trace / propagation relations</li>
     *   <li>{@code "impact"} — cross-category leaf-to-leaf impact relations</li>
     * </ul>
     */
    public String category() {
        return category;
    }
}
