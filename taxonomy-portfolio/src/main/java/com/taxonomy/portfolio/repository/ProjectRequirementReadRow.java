package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;

import java.time.Instant;

/** Scalar requirement and version fields; reading a view must not resolve its selected snapshot. */
public record ProjectRequirementReadRow(
        Long id,
        Long projectId,
        String requirementKey,
        String title,
        RequirementStatus status,
        int priority,
        Criticality criticality,
        RequirementType requirementType,
        ReviewStatus reviewStatus,
        String ownerUsername,
        Long currentVersionId,
        String currentAnalysisSnapshotId,
        Instant createdAt,
        Instant updatedAt,
        Long versionId,
        Integer versionNumber,
        String text,
        String contentHash,
        String changeReason,
        String createdBy,
        Instant versionCreatedAt,
        Long sourceArtifactId,
        Long sourceVersionId,
        String sourceFragmentIdsJson,
        String sectionReference,
        Integer pageNumber,
        String originalText) {
}
