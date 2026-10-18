package com.taxonomy.portfolio.repository;

import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;

import java.time.Instant;

/** Full immutable result data, without hydrating the snapshot's composite-key associations. */
public record AnalysisSnapshotReadRow(
        String id, Long projectId, Long requirementId, String requirementKey,
        Long requirementVersionId, int requirementVersionNumber, String jobId,
        AnalysisStatus status, String provider, String modelName, String taxonomyFingerprint,
        String promptFingerprint, String workspaceId, String branchName, String commitSha,
        Instant createdAt, long durationMs, int warningCount, String errorMessage,
        String analysisPayload, String gapAnalysisPayload, String patternDetectionPayload,
        String recommendationPayload) {

    public SnapshotSummary summary() {
        return new SnapshotSummary(id, projectId, requirementId, requirementKey,
                requirementVersionId, requirementVersionNumber, jobId, status, provider,
                modelName, taxonomyFingerprint, promptFingerprint, workspaceId, branchName,
                commitSha, createdAt, durationMs, warningCount, errorMessage);
    }
}
