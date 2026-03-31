package com.taxonomy.dto;

/**
 * Classifies how a node was included in the architecture view.
 *
 * <p>Each value describes a distinct provenance so the UI can present
 * structured explainability instead of free-text reasoning strings.
 *
 * <p>Use {@link #messageKey()} to obtain the i18n property key for
 * the display label (e.g. {@code node.origin.direct.scored}).
 */
public enum NodeOrigin {

    /** Node received an LLM score at or above the anchor threshold. */
    DIRECT_SCORED("node.origin.direct.scored"),

    /** Intermediate node on the hierarchical scoring path (root → leaf). */
    TRACE_INTERMEDIATE("node.origin.trace.intermediate"),

    /** Reached via BFS relation traversal (hop-decay propagation). */
    PROPAGATED("node.origin.propagated"),

    /** Introduced through a seed relation (see {@link com.taxonomy.model.SeedType}). */
    SEED_CONTEXT("node.origin.seed.context"),

    /** Added as a concrete leaf during post-propagation enrichment. */
    ENRICHED_LEAF("node.origin.enriched.leaf"),

    /**
     * Origin was promoted from a weaker provenance (e.g. {@code PROPAGATED} or
     * {@code TRACE_INTERMEDIATE}) because the node was selected for the impact
     * presentation.  Nodes whose original origin is already strong
     * ({@code DIRECT_SCORED}, {@code SEED_CONTEXT}, {@code ENRICHED_LEAF})
     * keep their original origin when selected for impact — only the
     * {@code selectedForImpact} flag is set on those nodes.
     */
    IMPACT_PROMOTED("node.origin.impact.promoted");

    private final String messageKey;

    NodeOrigin(String messageKey) {
        this.messageKey = messageKey;
    }

    /** Returns the i18n message key for the display label of this origin. */
    public String messageKey() {
        return messageKey;
    }
}
