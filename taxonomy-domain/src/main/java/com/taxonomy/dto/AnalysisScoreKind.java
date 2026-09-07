package com.taxonomy.dto;

/**
 * Declares the meaning of one analysis score.
 *
 * <p>Only {@link #PRODUCT_SUITABILITY} is conditional on another node and therefore must not be
 * compared directly with absolute hierarchical relevance values.</p>
 */
public enum AnalysisScoreKind {
    /** Independent 0–100 relevance of one taxonomy root. */
    ROOT_RELEVANCE,

    /** Absolute relevance carried through the hierarchical parent budget. */
    HIERARCHICAL_RELEVANCE,

    /** Independent 0–100 suitability of a concrete product, conditional on its product family. */
    PRODUCT_SUITABILITY
}
