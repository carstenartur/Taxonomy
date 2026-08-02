package com.taxonomy.dto;

/**
 * Optional business identity attached to an analysis request.
 *
 * <p>Ad-hoc analyses use {@code null}. Project analyses supply all fields so
 * generated hypotheses and evidence can be traced back to the immutable
 * snapshot that caused them.</p>
 */
public record AnalysisProvenance(
        Long projectId,
        Long requirementId,
        String snapshotId,
        String analysisSessionId) {

    public AnalysisProvenance {
        if (analysisSessionId != null && analysisSessionId.isBlank()) {
            throw new IllegalArgumentException("analysisSessionId must not be blank");
        }
        if (snapshotId != null && snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
    }
}
