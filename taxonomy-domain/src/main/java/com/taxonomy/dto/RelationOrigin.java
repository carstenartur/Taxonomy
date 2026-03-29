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
 */
public enum RelationOrigin {

    /** Loaded from the seed CSV (TYPE_DEFAULT, FRAMEWORK_SEED, or SOURCE_DERIVED). */
    TAXONOMY_SEED("relation.origin.taxonomy.seed"),

    /** Discovered through BFS traversal during relevance propagation. */
    PROPAGATED_TRACE("relation.origin.propagated.trace"),

    /** Derived as a cross-category leaf-to-leaf impact relation. */
    IMPACT_DERIVED("relation.origin.impact.derived"),

    /** Proposed by gap analysis or embedding-based similarity. */
    SUGGESTED_CANDIDATE("relation.origin.suggested.candidate"),

    /** Supported or confirmed by LLM inference. */
    LLM_SUPPORTED("relation.origin.llm.supported");

    private final String messageKey;

    RelationOrigin(String messageKey) {
        this.messageKey = messageKey;
    }

    /** Returns the i18n message key for the display label of this origin. */
    public String messageKey() {
        return messageKey;
    }
}
