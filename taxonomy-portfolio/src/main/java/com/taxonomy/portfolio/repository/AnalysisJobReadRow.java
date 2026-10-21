package com.taxonomy.portfolio.repository;

import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;

import java.time.Instant;

/** One statement's job and item metadata, without hydrating result payload associations. */
public record AnalysisJobReadRow(
        String id, Long projectId, AnalysisStatus status, String idempotencyKey,
        String provider, int maxArchitectureNodes, String requestedBy, String workspaceId,
        Instant createdAt, Instant startedAt, Instant completedAt,
        int totalItems, int successfulItems, int partialItems, int failedItems, String errorSummary,
        String providerPluginId, String providerPluginVersion, String providerPluginSha256,
        String providerConfigRevision, AnalysisReasonLength analysisReasonLength, com.taxonomy.dto.AnalysisLanguage analysisLanguage,
        Long itemId, Long requirementId, String requirementKey, Long requirementVersionId,
        Integer requirementVersionNumber, AnalysisStatus itemStatus, String snapshotId, Integer attempt,
        Instant itemStartedAt, Instant itemCompletedAt, String itemErrorMessage) {
    public AnalysisJobReadRow(String id, Long projectId, AnalysisStatus status, String idempotencyKey,
            String provider, int maxArchitectureNodes, String requestedBy, String workspaceId,
            Instant createdAt, Instant startedAt, Instant completedAt, int totalItems, int successfulItems,
            int partialItems, int failedItems, String errorSummary, String providerPluginId, String providerPluginVersion,
            String providerPluginSha256, String providerConfigRevision, AnalysisReasonLength analysisReasonLength,
            Long itemId, Long requirementId, String requirementKey, Long requirementVersionId,
            Integer requirementVersionNumber, AnalysisStatus itemStatus, String snapshotId, Integer attempt,
            Instant itemStartedAt, Instant itemCompletedAt, String itemErrorMessage) {
        this(id, projectId, status, idempotencyKey, provider, maxArchitectureNodes, requestedBy, workspaceId,
                createdAt, startedAt, completedAt, totalItems, successfulItems, partialItems, failedItems,
                errorSummary, providerPluginId, providerPluginVersion, providerPluginSha256, providerConfigRevision,
                analysisReasonLength, null, itemId, requirementId, requirementKey, requirementVersionId,
                requirementVersionNumber, itemStatus, snapshotId, attempt, itemStartedAt, itemCompletedAt, itemErrorMessage);
    }

}
