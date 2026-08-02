package com.taxonomy.portfolio.dto;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictType;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.LifecycleStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.MappingOrigin;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectSolutionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementSolutionRole;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.SolutionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Framework-neutral REST contracts for the project requirement portfolio. */
public final class PortfolioDtos {

    private PortfolioDtos() {
    }

    // ── Projects and requirements ───────────────────────────────────────────

    public record CreateProjectRequest(
            String projectKey,
            String title,
            String description,
            ProjectStatus status,
            String targetArchitecture,
            LocalDate targetDate,
            BigDecimal budgetAmount,
            String budgetCurrency) {
    }

    public record UpdateProjectRequest(
            String title,
            String description,
            ProjectStatus status,
            String targetArchitecture,
            LocalDate targetDate,
            BigDecimal budgetAmount,
            String budgetCurrency) {
    }

    public record ProjectView(
            Long id,
            String projectKey,
            String title,
            String description,
            ProjectStatus status,
            String ownerUsername,
            String workspaceId,
            String targetArchitecture,
            LocalDate targetDate,
            BigDecimal budgetAmount,
            String budgetCurrency,
            Instant createdAt,
            Instant updatedAt,
            int requirementCount,
            int solutionCount,
            int openConflictCount) {
    }

    public record SourceReference(
            Long sourceArtifactId,
            Long sourceVersionId,
            List<Long> sourceFragmentIds,
            String sectionReference,
            Integer pageNumber,
            String originalText) {
    }

    public record CreateRequirementRequest(
            String requirementKey,
            String title,
            String text,
            RequirementStatus status,
            Integer priority,
            Criticality criticality,
            RequirementType requirementType,
            ReviewStatus reviewStatus,
            String ownerUsername,
            String changeReason,
            SourceReference source) {
    }

    public record UpdateRequirementRequest(
            String title,
            RequirementStatus status,
            Integer priority,
            Criticality criticality,
            RequirementType requirementType,
            ReviewStatus reviewStatus,
            String ownerUsername) {
    }

    public record CreateRequirementVersionRequest(
            String text,
            String changeReason,
            SourceReference source) {
    }

    public record RequirementVersionView(
            Long id,
            int versionNumber,
            String text,
            String contentHash,
            String changeReason,
            String createdBy,
            Instant createdAt,
            SourceReference source) {
    }

    public record RequirementView(
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
            RequirementVersionView currentVersion) {
    }

    public record ImportRequirementCandidate(
            String requirementKey,
            String title,
            String text,
            RequirementType requirementType,
            Integer priority,
            Criticality criticality,
            SourceReference source) {
    }

    public record ImportRequirementsRequest(
            List<ImportRequirementCandidate> requirements,
            boolean analyzeAfterImport,
            String provider,
            Integer maxArchitectureNodes,
            String idempotencyKey) {
    }

    public record ImportRequirementsResult(
            List<RequirementView> requirements,
            AnalysisJobView analysisJob) {
    }

    // ── Analysis jobs and immutable snapshots ──────────────────────────────

    public record AnalyzeProjectRequest(
            List<Long> requirementIds,
            boolean all,
            String provider,
            Integer maxArchitectureNodes,
            String idempotencyKey) {
    }

    public record AnalysisJobItemView(
            Long id,
            Long requirementId,
            String requirementKey,
            Long requirementVersionId,
            int requirementVersionNumber,
            AnalysisStatus status,
            String snapshotId,
            int attempt,
            Instant startedAt,
            Instant completedAt,
            String errorMessage) {
    }

    public record AnalysisJobView(
            String id,
            Long projectId,
            AnalysisStatus status,
            String idempotencyKey,
            String provider,
            int maxArchitectureNodes,
            String requestedBy,
            String workspaceId,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            int totalItems,
            int successfulItems,
            int partialItems,
            int failedItems,
            String errorSummary,
            List<AnalysisJobItemView> items) {
    }

    public record SnapshotSummary(
            String id,
            Long projectId,
            Long requirementId,
            String requirementKey,
            Long requirementVersionId,
            int requirementVersionNumber,
            String jobId,
            AnalysisStatus status,
            String provider,
            String modelName,
            String taxonomyFingerprint,
            String promptFingerprint,
            String workspaceId,
            String branchName,
            String commitSha,
            Instant createdAt,
            long durationMs,
            int warningCount,
            String errorMessage) {
    }

    public record ElementMappingView(
            Long id,
            String snapshotId,
            String nodeCode,
            String nodeTitle,
            String taxonomyRoot,
            int directScore,
            double relevance,
            double confidence,
            MappingOrigin mappingOrigin,
            String hierarchyPath,
            String presenceReason,
            boolean selectedForImpact,
            ReviewStatus reviewStatus,
            ActionStatus actionStatus,
            String actionEvidence,
            String decisionBy,
            Instant decisionAt,
            String decisionComment) {
    }

    public record RelationMappingView(
            Long id,
            String snapshotId,
            String sourceCode,
            String targetCode,
            String relationType,
            String relationOrigin,
            String relationCategory,
            double relevance,
            double confidence,
            String presenceReason,
            ReviewStatus reviewStatus,
            String decisionBy,
            Instant decisionAt,
            String decisionComment) {
    }

    public record ReviewElementMappingRequest(
            ReviewStatus reviewStatus,
            ActionStatus actionStatus,
            String actionEvidence,
            String comment) {
    }

    public record ReviewRelationMappingRequest(
            ReviewStatus reviewStatus,
            String comment) {
    }

    public record SnapshotDetail(
            SnapshotSummary summary,
            AnalysisResult analysis,
            GapAnalysisView gapAnalysis,
            PatternDetectionView patternDetection,
            ArchitectureRecommendation recommendation,
            List<ElementMappingView> elementMappings,
            List<RelationMappingView> relationMappings) {
    }

    public record SnapshotDiff(
            String olderSnapshotId,
            String newerSnapshotId,
            Map<String, ScoreChange> scoreChanges,
            List<String> addedElements,
            List<String> removedElements,
            List<String> addedRelations,
            List<String> removedRelations,
            boolean taxonomyFingerprintChanged,
            boolean promptFingerprintChanged,
            boolean providerChanged) {
    }

    public record ScoreChange(Integer oldScore, Integer newScore) {
    }

    // ── Solutions ───────────────────────────────────────────────────────────

    public record CreateSolutionRequest(
            String solutionKey,
            String title,
            String description,
            SolutionType solutionType,
            OperatingModel operatingModel,
            LifecycleStatus lifecycleStatus,
            Integer maturityLevel,
            String responsibleOrganization,
            BigDecimal costAmount,
            String costCurrency,
            String riskNotes,
            Integer leadTimeDays,
            Map<String, String> extensionAttributes) {
    }

    public record UpdateSolutionRequest(
            String title,
            String description,
            SolutionType solutionType,
            OperatingModel operatingModel,
            LifecycleStatus lifecycleStatus,
            Integer maturityLevel,
            String ownerUsername,
            String responsibleOrganization,
            BigDecimal costAmount,
            String costCurrency,
            String riskNotes,
            Integer leadTimeDays,
            Map<String, String> extensionAttributes) {
    }

    public record UpsertTaxonomyCoverageRequest(
            String nodeCode,
            int coveragePercent,
            String evidence,
            ReviewStatus reviewStatus) {
    }

    public record TaxonomyCoverageView(
            Long id,
            String nodeCode,
            int coveragePercent,
            String evidence,
            ReviewStatus reviewStatus,
            String updatedBy,
            Instant updatedAt) {
    }

    public record SolutionView(
            Long id,
            String solutionKey,
            String title,
            String description,
            SolutionType solutionType,
            OperatingModel operatingModel,
            LifecycleStatus lifecycleStatus,
            int maturityLevel,
            String ownerUsername,
            String responsibleOrganization,
            BigDecimal costAmount,
            String costCurrency,
            String riskNotes,
            Integer leadTimeDays,
            Map<String, String> extensionAttributes,
            Instant createdAt,
            Instant updatedAt,
            List<TaxonomyCoverageView> taxonomyCoverage) {
    }

    public record AddProjectSolutionRequest(
            Long solutionId,
            ProjectSolutionStatus status,
            ActionStatus actionStatus,
            Integer priority,
            String rationale) {
    }

    public record UpdateProjectSolutionRequest(
            ProjectSolutionStatus status,
            ActionStatus actionStatus,
            Integer priority,
            String rationale) {
    }

    public record LinkRequirementSolutionRequest(
            Long requirementId,
            String snapshotId,
            int coveragePercent,
            RequirementSolutionRole role,
            ReviewStatus reviewStatus,
            String evidence) {
    }

    public record RequirementSolutionLinkView(
            Long id,
            Long requirementId,
            String requirementKey,
            String snapshotId,
            int coveragePercent,
            RequirementSolutionRole role,
            ReviewStatus reviewStatus,
            String evidence,
            String updatedBy,
            Instant updatedAt) {
    }

    public record ProjectSolutionView(
            Long id,
            Long projectId,
            SolutionView solution,
            ProjectSolutionStatus status,
            ActionStatus actionStatus,
            int priority,
            String rationale,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<RequirementSolutionLinkView> requirements,
            List<SolutionProductCandidateView> productCandidates) {
    }

    // ── Products ────────────────────────────────────────────────────────────

    public record CreateProductRequest(
            String productKey,
            String manufacturer,
            String productFamily,
            String productName,
            String editionVersion,
            ProductStatus productStatus,
            LocalDate endOfSupport,
            String licenseModel,
            OperatingModel operatingModel,
            String supportedPlatforms,
            String securityFeatures,
            String complianceFeatures,
            BigDecimal costAmount,
            String costCurrency,
            String costBasis,
            String sourceReference,
            Instant verifiedAt) {
    }

    public record UpdateProductRequest(
            String manufacturer,
            String productFamily,
            String productName,
            String editionVersion,
            ProductStatus productStatus,
            LocalDate endOfSupport,
            String licenseModel,
            OperatingModel operatingModel,
            String supportedPlatforms,
            String securityFeatures,
            String complianceFeatures,
            BigDecimal costAmount,
            String costCurrency,
            String costBasis,
            String sourceReference,
            Instant verifiedAt) {
    }

    public record ProductView(
            Long id,
            String productKey,
            String manufacturer,
            String productFamily,
            String productName,
            String editionVersion,
            ProductStatus productStatus,
            LocalDate endOfSupport,
            String licenseModel,
            OperatingModel operatingModel,
            String supportedPlatforms,
            String securityFeatures,
            String complianceFeatures,
            BigDecimal costAmount,
            String costCurrency,
            String costBasis,
            String sourceReference,
            Instant verifiedAt,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<TaxonomyCoverageView> taxonomyCoverage) {
    }

    public record UpsertProductCandidateRequest(
            Long productId,
            int coveragePercent,
            String hardExclusions,
            String strengths,
            String weaknesses,
            String openEvidence,
            double confidence,
            ReviewStatus reviewStatus,
            ProductSelectionStatus selectionStatus) {
    }

    public record SolutionProductCandidateView(
            Long id,
            Long projectSolutionId,
            ProductView product,
            int coveragePercent,
            String hardExclusions,
            String strengths,
            String weaknesses,
            String openEvidence,
            double confidence,
            ReviewStatus reviewStatus,
            ProductSelectionStatus selectionStatus,
            String updatedBy,
            Instant updatedAt) {
    }

    // ── Aggregation, matrices and conflicts ────────────────────────────────

    public record MatrixView(
            List<String> rows,
            List<String> columns,
            Map<String, Map<String, Integer>> values) {
    }

    public record AggregatedTaxonomyNode(
            String nodeCode,
            String title,
            String taxonomyRoot,
            int requirementCount,
            double averageRelevance,
            int maximumDirectScore,
            List<String> requirementKeys,
            List<String> snapshotIds,
            Map<ActionStatus, Integer> actionStatusCounts) {
    }

    public record ConflictView(
            Long id,
            Long projectId,
            Long requirementAId,
            String requirementAKey,
            Long requirementBId,
            String requirementBKey,
            ConflictType conflictType,
            ConflictStatus status,
            String title,
            String evidence,
            double confidence,
            String resolutionNote,
            Instant detectedAt,
            String reviewedBy,
            Instant reviewedAt) {
    }

    public record ReviewConflictRequest(
            ConflictStatus status,
            String resolutionNote) {
    }

    public record PortfolioMetrics(
            int totalRequirements,
            int analyzedRequirements,
            int confirmedRequirements,
            int requirementsWithoutConfirmedSolution,
            int totalSolutions,
            Map<ActionStatus, Integer> solutionsByAction,
            int totalProductCandidates,
            int selectedProducts,
            int openConflicts,
            int staleSnapshots) {
    }

    public record ProjectPortfolioView(
            ProjectView project,
            PortfolioMetrics metrics,
            List<RequirementView> requirements,
            List<AggregatedTaxonomyNode> taxonomyNodes,
            List<ProjectSolutionView> solutions,
            List<ConflictView> conflicts,
            MatrixView requirementTaxonomyMatrix,
            MatrixView requirementSolutionMatrix,
            MatrixView solutionProductMatrix) {
    }
}
