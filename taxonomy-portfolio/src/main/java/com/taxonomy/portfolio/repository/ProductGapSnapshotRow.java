package com.taxonomy.portfolio.repository;

/** One frozen analysis row streamed under the project and workspace predicates. */
public record ProductGapSnapshotRow(String id, Long requirementId, String requirementKey, Long requirementVersionId,
                                    int requirementVersionNumber, String taxonomyFingerprint, String analysisPayload) { }
