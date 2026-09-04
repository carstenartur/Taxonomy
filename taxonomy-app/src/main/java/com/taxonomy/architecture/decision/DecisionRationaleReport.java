package com.taxonomy.architecture.decision;

import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.dto.ViewContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, format-neutral model for a hierarchical decision rationale report.
 *
 * <p>The model intentionally distinguishes a missing score (not evaluated) from an
 * explicit score of {@code 0} (evaluated and rejected). Renderers must preserve that
 * distinction and must never infer or change scores, hierarchy links, or provenance.</p>
 */
public record DecisionRationaleReport(
        String title,
        String languageTag,
        String requirement,
        ReportStatus status,
        ReportMetadata metadata,
        ExecutiveSummary executiveSummary,
        List<DecisionChapter> chapters,
        List<LeafCandidate> leadingLeaves,
        List<String> warnings,
        List<ProductCoverageGap> productCoverageGaps,
        List<TaxonomyDiscrepancy> discrepancies,
        ViewContext viewContext,
        Map<String, AnalysisScoreDetail> scoreDetails) {

    public DecisionRationaleReport {
        languageTag = normalized(languageTag, "en");
        requirement = normalized(requirement, "");
        status = status == null ? ReportStatus.DRAFT_INCOMPLETE : status;
        chapters = immutable(chapters);
        leadingLeaves = immutable(leadingLeaves);
        warnings = immutable(warnings);
        productCoverageGaps = immutable(productCoverageGaps);
        discrepancies = immutable(discrepancies);
        scoreDetails = scoreDetails == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(scoreDetails));
    }

    /** Backward-compatible constructor used by the hierarchy builder before score adaptation. */
    public DecisionRationaleReport(
            String title,
            String languageTag,
            String requirement,
            ReportStatus status,
            ReportMetadata metadata,
            ExecutiveSummary executiveSummary,
            List<DecisionChapter> chapters,
            List<LeafCandidate> leadingLeaves,
            List<String> warnings,
            List<ProductCoverageGap> productCoverageGaps,
            List<TaxonomyDiscrepancy> discrepancies,
            ViewContext viewContext) {
        this(title, languageTag, requirement, status, metadata, executiveSummary,
                chapters, leadingLeaves, warnings, productCoverageGaps, discrepancies,
                viewContext, Map.of());
    }

    public enum ReportStatus {
        FINAL,
        FINAL_WITH_WARNINGS,
        DRAFT_INCOMPLETE,
        NO_RESULT
    }

    public record ReportMetadata(
            Instant generatedAt,
            String generatedBy,
            String taxonomyApplicationVersion,
            String taxonomyBuildCommit,
            String taxonomyCatalogueFile,
            String taxonomyDataVersion,
            String taxonomyCatalogueResourceSha256,
            String taxonomyDataFingerprintSha256,
            String analysisSnapshotFingerprintSha256,
            String taxonomyDataSource,
            int taxonomyNodeCount,
            int taxonomyRootCount,
            String repositoryId,
            String workspaceId,
            String branch,
            String basedOnCommit,
            Instant basedOnCommitTimestamp,
            boolean projectionStale,
            boolean indexStale,
            String analysisProvider,
            String analysisStatus,
            String analysisModel,
            String analysisSnapshotId,
            Long projectId,
            Long requirementId,
            Long requirementVersionId,
            Integer requirementVersionNumber,
            Instant analysisCreatedAt,
            String analysisCreatedBy,
            String recordedTaxonomyFingerprintSha256,
            String promptFingerprintSha256,
            boolean hierarchyFromImmutableSnapshot,
            String reportTimeZone,
            int suppliedReasonCount,
            int evaluatedNodeCount,
            int positiveNodeCount,
            double completenessPercent) {

        public ReportMetadata withAnalysisSnapshotFingerprintSha256(String fingerprint) {
            return new ReportMetadata(
                    generatedAt,
                    generatedBy,
                    taxonomyApplicationVersion,
                    taxonomyBuildCommit,
                    taxonomyCatalogueFile,
                    taxonomyDataVersion,
                    taxonomyCatalogueResourceSha256,
                    taxonomyDataFingerprintSha256,
                    fingerprint,
                    taxonomyDataSource,
                    taxonomyNodeCount,
                    taxonomyRootCount,
                    repositoryId,
                    workspaceId,
                    branch,
                    basedOnCommit,
                    basedOnCommitTimestamp,
                    projectionStale,
                    indexStale,
                    analysisProvider,
                    analysisStatus,
                    analysisModel,
                    analysisSnapshotId,
                    projectId,
                    requirementId,
                    requirementVersionId,
                    requirementVersionNumber,
                    analysisCreatedAt,
                    analysisCreatedBy,
                    recordedTaxonomyFingerprintSha256,
                    promptFingerprintSha256,
                    hierarchyFromImmutableSnapshot,
                    reportTimeZone,
                    suppliedReasonCount,
                    evaluatedNodeCount,
                    positiveNodeCount,
                    completenessPercent);
        }
    }

    public record ExecutiveSummary(
            LeafCandidate leadingLeaf,
            List<PathStep> path,
            String conciseConclusion,
            String methodologyNote) {

        public ExecutiveSummary {
            path = immutable(path);
        }
    }

    public record LeafCandidate(
            String code,
            String title,
            int score,
            String taxonomyRoot,
            int depth,
            String hierarchyPath,
            String reason,
            ReasonSource reasonSource) {
    }

    public record PathStep(
            int position,
            String code,
            String title,
            Integer absoluteScore,
            Double localSharePercent,
            String reason,
            ReasonSource reasonSource) {
    }

    public record DecisionChapter(
            int number,
            String parentCode,
            String parentTitle,
            String parentDescription,
            Integer parentScore,
            int hierarchyLevel,
            boolean complete,
            String decisionSummary,
            String comparativeRationale,
            List<ChildDecision> children,
            List<String> missingChildCodes) {

        public DecisionChapter {
            children = immutable(children);
            missingChildCodes = immutable(missingChildCodes);
        }
    }

    public enum Disposition {
        CONTINUED,
        LEAF_CANDIDATE,
        REJECTED,
        NOT_EVALUATED
    }

    public enum ReasonSource {
        AI_SCORING,
        DETERMINISTIC_TRACE,
        MISSING
    }

    public record ChildDecision(
            String code,
            String title,
            String description,
            Integer absoluteScore,
            Double localSharePercent,
            Integer rank,
            boolean leadingSibling,
            Disposition disposition,
            String reason,
            ReasonSource reasonSource,
            boolean leaf) {
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static <T> List<T> immutable(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
