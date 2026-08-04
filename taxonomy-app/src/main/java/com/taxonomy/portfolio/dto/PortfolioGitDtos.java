package com.taxonomy.portfolio.dto;

import java.time.Instant;
import java.util.List;

/** REST contracts for Git-backed project portfolio collaboration. */
public final class PortfolioGitDtos {

    private PortfolioGitDtos() {
    }

    public record ExportedPortfolioDsl(
            String workspaceId,
            String username,
            String activeBranch,
            String headCommit,
            String dsl,
            int projectCount,
            int requirementCount,
            int solutionCount,
            int productCount,
            Instant exportedAt) {
    }

    public record CommitPortfolioRequest(
            String branch,
            String message) {
    }

    public record PortfolioCommitResult(
            String branch,
            String commitId,
            String parentCommitId,
            int projectCount,
            int requirementCount,
            int solutionCount,
            int productCount,
            int conflictCount,
            Instant committedAt) {
    }

    public record MaterializePortfolioRequest(
            String branch,
            String expectedHead) {
    }

    public record MaterializationPreview(
            String branch,
            String targetHead,
            String currentFingerprint,
            String targetFingerprint,
            int currentLineCount,
            int targetLineCount,
            int addedLines,
            int removedLines,
            boolean changed,
            boolean destructiveChangePossible,
            List<String> addedPreview,
            List<String> removedPreview) {
    }

    public record MaterializePortfolioResult(
            String branch,
            String commitId,
            int projectsUpserted,
            int requirementsUpserted,
            int requirementVersionsCreated,
            int solutionsUpserted,
            int projectSolutionsUpserted,
            int productsUpserted,
            int conflictsUpserted,
            int unresolvedReferences,
            Instant materializedAt) {
    }

    public record MergePortfolioRequest(
            String sourceBranch,
            String targetBranch,
            String message) {
    }

    public record MergePortfolioResult(
            String sourceBranch,
            String targetBranch,
            String sourceHead,
            String targetHeadBefore,
            String mergeCommitId,
            String strategy,
            int ancestorCount,
            int projectCount,
            int requirementCount,
            int solutionCount,
            int productCount,
            int conflictCount,
            Instant mergedAt) {
    }
}
