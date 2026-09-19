package com.taxonomy.relations.service;

/** Owner-defined operation that links an exact repository/workspace analysis projection. */
@FunctionalInterface
public interface AnalysisHypothesisLinkPort {
    void linkSnapshot(String repositoryId, String workspaceId, String analysisSessionId,
                      Long projectId, Long requirementId, String snapshotId);
}
