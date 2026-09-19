package com.taxonomy.portfolio.dto;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;

import java.util.List;

/** Contracts for one atomic, human-reviewed document import. */
public final class PortfolioImportReviewDtos {

    private PortfolioImportReviewDtos() {
    }

    public enum ImportDecision {
        NEW_REQUIREMENT,
        NEW_VERSION
    }

    public record ReviewedImportItem(
            ImportDecision decision,
            Long targetRequirementId,
            String requirementKey,
            String title,
            String text,
            RequirementType requirementType,
            Integer priority,
            Criticality criticality,
            SourceReference source) {
    }

    public record ReviewedImportRequest(
            List<ReviewedImportItem> items,
            boolean analyzeAfterImport,
            String provider,
            Integer maxArchitectureNodes,
            String idempotencyKey) {
    }

    public record ReviewedImportResult(
            List<RequirementView> newRequirements,
            List<RequirementView> versionedRequirements,
            AnalysisJobView analysisJob) {
    }

    public record PersistedReviewImport(
            List<RequirementView> newRequirements,
            List<RequirementView> versionedRequirements) {
        public List<Long> affectedRequirementIds() {
            return java.util.stream.Stream.concat(
                            newRequirements.stream(), versionedRequirements.stream())
                    .map(RequirementView::id)
                    .distinct()
                    .toList();
        }
    }
}
