package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;

/** Locked work identity without hydrating immutable snapshot associations or payloads. */
public record AnalysisItemClaimRow(Long itemId, Long requirementId, Long requirementVersionId,
        AnalysisStatus status, String snapshotId, int attempt) { }
