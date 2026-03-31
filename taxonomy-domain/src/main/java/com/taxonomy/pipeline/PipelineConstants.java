package com.taxonomy.pipeline;

/**
 * Centralised constants for the analysis and architecture-view pipeline.
 *
 * <p>Thresholds and limits that are shared across multiple services
 * (e.g. anchor selection in {@code RequirementArchitectureViewService}
 * and {@code ArchitectureGraphQueryServiceImpl}) live here so that
 * there is exactly one source of truth for each value.
 */
public final class PipelineConstants {

    private PipelineConstants() { /* utility class */ }

    // ── Anchor selection ────────────────────────────────────────────────

    /** Minimum LLM score (0–100) to be automatically selected as an anchor. */
    public static final int ANCHOR_THRESHOLD_HIGH = 70;

    /**
     * Fallback threshold when fewer than {@link #MIN_ANCHORS} nodes
     * score at or above {@link #ANCHOR_THRESHOLD_HIGH}.
     */
    public static final int ANCHOR_THRESHOLD_LOW = 50;

    /** Minimum number of anchors to try to select (using the fallback threshold if necessary). */
    public static final int MIN_ANCHORS = 3;

    // ── Leaf enrichment ─────────────────────────────────────────────────

    /** Maximum number of top-scoring leaf nodes to add per taxonomy root during enrichment. */
    public static final int MAX_LEAF_ENRICHMENT = 3;

    /** Minimum score (0–100) for a leaf node to be included in the enrichment step. */
    public static final int LEAF_ENRICHMENT_MIN_SCORE = 5;
}
