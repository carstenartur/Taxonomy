package com.taxonomy.portfolio.repository;

/** Scalar input for deterministic solution proposals; associations are needed only for new links. */
public record CurrentElementMappingReadRow(
        String snapshotId, Long requirementId, String nodeCode, double directScore, double relevance) {
}
