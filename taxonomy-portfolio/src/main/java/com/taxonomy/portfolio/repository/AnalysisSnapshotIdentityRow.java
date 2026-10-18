package com.taxonomy.portfolio.repository;

/** Exact immutable source identity used while promoting an already-ranked snapshot. */
public record AnalysisSnapshotIdentityRow(String id, Long requirementId, Long requirementVersionId) {
}
