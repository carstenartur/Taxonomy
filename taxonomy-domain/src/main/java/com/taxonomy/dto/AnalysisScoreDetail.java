package com.taxonomy.dto;

/**
 * Immutable interpretation of one raw analysis score.
 *
 * @param nodeCode node identity
 * @param kind semantic meaning of {@code rawScore}
 * @param rawScore original 0–100 score returned by the analysis
 * @param effectiveRelevance comparable 0–100 relevance used by generic downstream consumers
 * @param parentCode direct hierarchical parent, when known
 * @param parentScore direct parent relevance used to derive a product's effective relevance
 */
public record AnalysisScoreDetail(
        String nodeCode,
        AnalysisScoreKind kind,
        int rawScore,
        int effectiveRelevance,
        String parentCode,
        Integer parentScore) {

    public AnalysisScoreDetail {
        if (nodeCode == null || nodeCode.isBlank()) {
            throw new IllegalArgumentException("nodeCode must not be blank");
        }
        nodeCode = nodeCode.strip();
        kind = kind == null ? AnalysisScoreKind.HIERARCHICAL_RELEVANCE : kind;
        if (rawScore < 0 || rawScore > 100) {
            throw new IllegalArgumentException("rawScore must be between 0 and 100");
        }
        if (effectiveRelevance < 0 || effectiveRelevance > 100) {
            throw new IllegalArgumentException("effectiveRelevance must be between 0 and 100");
        }
        parentCode = parentCode == null || parentCode.isBlank() ? null : parentCode.strip();
        if (parentScore != null && (parentScore < 0 || parentScore > 100)) {
            throw new IllegalArgumentException("parentScore must be between 0 and 100");
        }
    }

    public boolean isProductSuitability() {
        return kind == AnalysisScoreKind.PRODUCT_SUITABILITY;
    }
}
