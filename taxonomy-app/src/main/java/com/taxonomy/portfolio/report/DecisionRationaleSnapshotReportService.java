package com.taxonomy.portfolio.report;

import com.taxonomy.architecture.decision.DecisionRationaleReport;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.AnalysisSnapshotProvenance;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.DecisionAnalysisInput;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Replays one immutable, tenant-scoped requirement-analysis snapshot into the
 * decision-rationale report family.
 *
 * <p>The hierarchy is read from the serialized {@link AnalysisResult}, not from the
 * current catalogue. This keeps old reports reproducible after taxonomy updates and
 * prevents a historical score map from being silently combined with a newer hierarchy.</p>
 */
@Service
public class DecisionRationaleSnapshotReportService {

    private final RequirementAnalysisSnapshotRepository snapshotRepository;
    private final PortfolioJsonCodec jsonCodec;
    private final DecisionRationaleReportService reportService;

    public DecisionRationaleSnapshotReportService(
            RequirementAnalysisSnapshotRepository snapshotRepository,
            PortfolioJsonCodec jsonCodec,
            DecisionRationaleReportService reportService) {
        this.snapshotRepository = snapshotRepository;
        this.jsonCodec = jsonCodec;
        this.reportService = reportService;
    }

    @Transactional(readOnly = true)
    public DecisionRationaleReport generate(
            Long projectId,
            String snapshotId,
            String username,
            WorkspaceContext workspaceContext,
            Locale locale) {
        if (projectId == null) {
            throw PortfolioException.validation("projectId is required");
        }
        if (snapshotId == null || snapshotId.isBlank()) {
            throw PortfolioException.validation("snapshotId is required");
        }
        Objects.requireNonNull(workspaceContext, "workspaceContext must not be null");

        String scopeKey = PortfolioScope.key(username, workspaceContext);
        RequirementAnalysisSnapshot snapshot = snapshotRepository
                .findByIdAndProjectIdAndScopeKey(snapshotId.strip(), projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis snapshot not found: " + snapshotId));
        AnalysisResult analysis = jsonCodec.read(
                snapshot.getAnalysisPayload(), AnalysisResult.class);
        if (analysis == null || analysis.getScores() == null || analysis.getScores().isEmpty()) {
            throw PortfolioException.conflict(
                    "The analysis snapshot does not contain reportable score evidence");
        }
        if (analysis.getTree() == null || analysis.getTree().isEmpty()) {
            throw PortfolioException.conflict(
                    "The analysis snapshot predates frozen taxonomy-hierarchy evidence and cannot be rendered reproducibly");
        }

        ViewContext historicalViewContext = historicalViewContext(snapshot, analysis);
        AnalysisSnapshotProvenance provenance = new AnalysisSnapshotProvenance(
                snapshot.getId(),
                snapshot.getProjectId(),
                snapshot.getRequirementId(),
                snapshot.getRequirementVersionId(),
                snapshot.getRequirementVersion().getVersionNumber(),
                snapshot.getCreatedAt(),
                snapshot.getCreatedBy(),
                snapshot.getModelName(),
                snapshot.getTaxonomyFingerprint(),
                snapshot.getPromptFingerprint());
        DecisionAnalysisInput input = new DecisionAnalysisInput(
                snapshot.getRequirementVersion().getText(),
                analysis.getScores(),
                analysis.getReasons(),
                firstNonBlank(snapshot.getProvider(), analysis.getProvider()),
                snapshot.getStatus().name(),
                analysis.getDiscrepancies(),
                analysis.getProductCoverageGaps(),
                analysis.getTree(),
                provenance);
        return reportService.generate(input, workspaceContext, historicalViewContext, locale);
    }

    private ViewContext historicalViewContext(
            RequirementAnalysisSnapshot snapshot,
            AnalysisResult analysis) {
        ViewContext original = analysis.getViewContext();
        boolean matchingOriginal = original != null
                && Objects.equals(original.basedOnCommit(), snapshot.getCommitSha())
                && Objects.equals(original.basedOnBranch(), snapshot.getBranchName());
        Instant commitTimestamp = matchingOriginal ? original.commitTimestamp() : null;
        boolean includesProvisional = matchingOriginal
                ? original.includesProvisionalRelations()
                : analysis.getProvisionalRelations() != null
                        && !analysis.getProvisionalRelations().isEmpty();
        return new ViewContext(
                valueOrUnknown(snapshot.getCommitSha()),
                valueOrUnknown(snapshot.getBranchName()),
                commitTimestamp,
                includesProvisional,
                matchingOriginal && original.projectionStale(),
                matchingOriginal && original.indexStale());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "unknown";
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.strip();
    }
}
