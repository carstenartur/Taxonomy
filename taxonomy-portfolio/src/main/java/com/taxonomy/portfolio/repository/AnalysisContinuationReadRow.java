package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;

/** One statement's continuation authority, without hydrating snapshot or version associations. */
public record AnalysisContinuationReadRow(
        Long itemId, String jobId, Long projectId, String scopeKey,
        Long requirementId, Long requirementVersionId, AnalysisStatus itemStatus, String snapshotId,
        AnalysisStatus jobStatus, String provider, String idempotencyKey, Long currentRequirementVersionId,
        String matchedSnapshotId, String snapshotJobId, Long snapshotRequirementId,
        Long snapshotRequirementVersionId) {
}
